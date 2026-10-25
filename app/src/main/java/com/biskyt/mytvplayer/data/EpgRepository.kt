package com.biskyt.mytvplayer.data

import android.util.Xml
import androidx.room.withTransaction
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.appString
import com.biskyt.mytvplayer.core.epg.ChannelMatcher
import com.biskyt.mytvplayer.core.epg.ChannelNames
import com.biskyt.mytvplayer.core.epg.EpgCandidate
import com.biskyt.mytvplayer.core.epg.EpgMatch
import com.biskyt.mytvplayer.core.epg.XmltvChannel
import com.biskyt.mytvplayer.core.epg.XmltvParser
import com.biskyt.mytvplayer.data.db.AppDatabase
import com.biskyt.mytvplayer.data.db.ChannelEpgMappingEntity
import com.biskyt.mytvplayer.data.db.ChannelProgramme
import com.biskyt.mytvplayer.data.db.EpgChannelEntity
import com.biskyt.mytvplayer.data.db.EpgSourceEntity
import com.biskyt.mytvplayer.data.db.MAX_SQL_ARGS
import com.biskyt.mytvplayer.data.db.PlaylistEntity
import com.biskyt.mytvplayer.data.db.ProgrammeEntity
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Which channels automatic matching looks at, and how sure it must be. */
enum class MatchScope(val threshold: Double) {
    /** Every channel without a manual choice (when a guide is first loaded). */
    ALL(ChannelMatcher.AUTO_THRESHOLD),

    /** Channels new since the last update, or whose guide channel has gone. */
    NEW(ChannelMatcher.AUTO_THRESHOLD),

    /** As [NEW], plus channels tried before without a match (a guide's channel list changed). */
    RETRY(ChannelMatcher.AUTO_THRESHOLD),

    /** Channels without a guide, near-certain matches only (the "Match channels without a guide" button). */
    MISSING(ChannelMatcher.MISSING_THRESHOLD)
}

/** How many channels without a guide were checked, and how many got one. */
data class MatchResult(val checked: Int, val matched: Int)

/**
 * TV guide data: XMLTV sources, which guide channel each playlist channel uses, and programme lookups.
 *
 * To keep storage small on low-end boxes, every source's channel list is stored (needed for matching) but programmes
 * are only stored for guide channels that some playlist channel actually uses, within a few days of now. The
 * downloaded file is kept (gzipped) so a newly mapped channel can be filled in without downloading again.
 */
class EpgRepository(
    private val db: AppDatabase,
    private val http: OkHttpClient,
    private val cacheDir: File,
    /** Runs the debounced re-imports after manual guide changes. */
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val epg = db.epg()

    private val _changes = MutableStateFlow(0)

    /** Ticks whenever programme data or a channel's guide choice changes, so screens can reload now/next. */
    val changes: StateFlow<Int> = _changes

    private fun changed() = _changes.update { it + 1 }

    /**
     * Built from every guide channel and reused while matching (building it is the slow part). Dropped after each
     * import's matching step and when the matching screen closes, so it doesn't hold memory while watching TV.
     */
    @Volatile private var matcherCache: ChannelMatcher? = null

    private suspend fun matcher(): ChannelMatcher = matcherCache ?: ChannelMatcher(
        // Normalized afresh rather than from nameNorm, so guides stored by an older version follow today's rules.
        epg.enabledChannels().map { EpgCandidate(it.sourceId, it.xmlId, it.displayName, ChannelNames.normalize(it.displayName)) }
    ).also { matcherCache = it }

    fun observeSources() = epg.observeSources()

    fun observeMatchCounts(playlistId: Long) = epg.observeMatchCounts(playlistId)

    suspend fun coverage() = epg.coverage()

    fun pageForMatching(playlistId: Long, query: String, status: Int) = androidx.paging.Pager(androidx.paging.PagingConfig(pageSize = 40)) {
        epg.pageForMatching(playlistId, likeArg(query), status)
    }.flow

    /** The playlist's own guide, named once when added ("My TV guide"); like any saved name, never translated after. */
    suspend fun ensureProviderSource(p: PlaylistEntity, xmltvUrl: String) {
        val existing = epg.providerSource(p.id)
        if (existing == null) {
            epg.insertSource(
                EpgSourceEntity(name = appString(R.string.data_provider_guide_name, p.name), url = xmltvUrl, playlistId = p.id)
            )
        } else if (existing.url != xmltvUrl) {
            epg.upsertSource(existing.copy(url = xmltvUrl, lastImportAt = 0)) // a different guide: match everything again
        }
    }

    /** Named after its address when [name] is blank. */
    suspend fun addSource(name: String, url: String): Long =
        epg.insertSource(EpgSourceEntity(name = name.trim().ifEmpty { url.trim() }, url = url.trim()))

    /** Adds those of [guides] not added already (by address). */
    suspend fun addMissing(guides: List<FreeGuide>) {
        val added = sourceUrls()
        guides.filter { it.url !in added }.forEach { addSource(it.sourceName, it.url) }
    }

    suspend fun sourceUrls(): Set<String> = epg.sourceUrls().toHashSet()

    /**
     * Switched off, a guide's programmes go and channels matched to it automatically are matched afresh at the next
     * update (to the other guides); the user's own choices stay, for when it's back. Switched on, the next update
     * downloads it again (to read it in full) and tries it for channels that found no guide.
     */
    suspend fun setSourceEnabled(source: EpgSourceEntity, enabled: Boolean) {
        db.withTransaction {
            epg.setSourceEnabled(source.id, enabled)
            if (enabled) epg.deleteFailedAutoMappings() else clearSwitchedOff()
        }
        if (enabled) metaFile(source.id).delete() // no "not changed" from the server: its programmes went
        releaseMatcher()
        changed()
    }

    /** What the guides switched off left (see [setSourceEnabled]); also for guides switched off by an older version. */
    private suspend fun clearSwitchedOff() {
        epg.clearSwitchedOffProgrammes()
        epg.deleteSwitchedOffAutoMappings()
    }

    suspend fun removeSource(id: Long) {
        db.withTransaction {
            epg.clearProgrammes(id)
            epg.clearChannels(id)
            epg.deleteMappingsForSource(id)
            epg.deleteSource(id)
        }
        sourceFile(id).delete()
        matcherCache = null
        changed()
    }

    /**
     * Downloads and imports every enabled source, then re-runs automatic matching for [playlistIds]. A source that
     * fails doesn't stop the others: returns each failed source's error, by id (also kept as its lastError). With
     * [only], just that source is downloaded again; the others are used as last downloaded.
     */
    suspend fun importAll(playlistIds: List<Long>, only: Long? = null, onProgress: (GuideStep) -> Unit = {}): Map<Long, String> =
        withContext(Dispatchers.IO) {
            val errors = HashMap<Long, String>()
            db.withTransaction { clearSwitchedOff() }
            val sources = epg.enabledSources()
            val downloads = downloadAll(sources.filter { only == null || it.id == only || !sourceFile(it.id).exists() }, errors, onProgress)
            // Sources not read in full afterwards: which guide channels they supply before matching, so only what
            // matching adds is read.
            val mappedBefore = sources.filterNot { readInFull(it, it.id in downloads.fresh) }
                .associate { it.id to epg.mappedXmlIds(it.id).toHashSet() }
            onProgress(GuideStep.Matching())
            playlistIds.forEach { autoMatch(it, downloads.scope, onProgress) }
            releaseMatcher()
            val late = HashMap<Long, List<EpgChannelEntity>>()
            for (s in sources) {
                importProgrammesOf(s, s.id in downloads.fresh, mappedBefore[s.id].orEmpty(), errors, onProgress)
                    ?.let { if (it.isNotEmpty()) late[s.id] = it }
            }
            if (late.isNotEmpty()) addLateChannels(late, playlistIds, onProgress)
            epg.pruneProgrammes(clock() - KEEP_PAST_MS)
            changed()
            errors
        }

    /** What [downloadAll] found: the sources downloaded anew, and how much matching that calls for. */
    private class Downloads(val fresh: Set<Long>, val scope: MatchScope)

    /** Downloads [sources] and reads their channels, adding each failure to [errors]. */
    private suspend fun downloadAll(
        sources: List<EpgSourceEntity>,
        errors: MutableMap<Long, String>,
        onProgress: (GuideStep) -> Unit
    ): Downloads {
        // Everything is matched again only when a guide loads for the first time; otherwise matches are kept, and
        // channels without one are tried again only if a guide's channel list changed.
        var firstLoad = false
        var guideChanged = false
        val fresh = HashSet<Long>()
        for (s in sources) {
            val result = load(s, onProgress)
            result.exceptionOrNull()?.let { errors[s.id] = describe(it) }
            val count = result.getOrNull() ?: continue // failed, or not changed on the server
            fresh += s.id
            if (s.lastImportAt == 0L) firstLoad = true
            if (count != s.channelCount) guideChanged = true
        }
        val scope = when {
            firstLoad -> MatchScope.ALL
            guideChanged -> MatchScope.RETRY
            else -> MatchScope.NEW
        }
        return Downloads(fresh, scope)
    }

    /** All of a source's programmes are read if it was just downloaded, or those stored are running short. */
    private fun readInFull(s: EpgSourceEntity, fresh: Boolean) = fresh || clock() - s.lastImportAt > REREAD_AFTER_MS

    /**
     * Stores a source's programmes after matching: all of them if it was just downloaded ([fresh]) or its stored ones
     * are running short; otherwise only for channels newly matched to it (since [mappedBefore]), or nothing. Returns
     * channels found after programmes (see [addLateChannels]), or null if it failed (added to [errors]).
     */
    private suspend fun importProgrammesOf(
        s: EpgSourceEntity,
        fresh: Boolean,
        mappedBefore: Set<String>,
        errors: MutableMap<Long, String>,
        onProgress: (GuideStep) -> Unit
    ): List<EpgChannelEntity>? {
        if (!sourceFile(s.id).exists()) return emptyList()
        val mapped = epg.mappedXmlIds(s.id).toHashSet()
        val full = readInFull(s, fresh)
        val wanted = if (full) mapped else mapped - mappedBefore
        if (wanted.isEmpty() && !full) return emptyList()
        return runCatching {
            importProgrammes(
                s.id,
                wanted,
                onlyThese = !full,
                known = if (full) epg.channelXmlIds(s.id).toHashSet() else null,
                onProgress = { percent -> onProgress(GuideStep.Loading(s.name, percent / 100f)) }
            ).also {
                // Unchanged but read again in full: counts from now. (Not a guide never read well, nor one that failed.)
                if (full && !fresh && s.lastImportAt != 0L && s.id !in errors) epg.setLastImport(s.id, clock())
            }
        }.onFailure { e ->
            // E.g. a file cut off part-way: the channels at its start read fine, so say so here.
            epg.setSourceError(s.id, GuideError.saved(e))
            errors[s.id] = describe(e)
        }.getOrNull()
    }

    /**
     * A guide that lists channels after programmes (against the XMLTV format, e.g. guides joined end to end): its
     * channel list was read short. Adds the rest, matches again and reloads its programmes; later updates read such a
     * guide in full ([interleavedMarker]).
     */
    private suspend fun addLateChannels(late: Map<Long, List<EpgChannelEntity>>, playlistIds: List<Long>, onProgress: (GuideStep) -> Unit) {
        for ((id, rows) in late) {
            rows.chunked(BATCH).forEach { epg.upsertChannels(it) }
            interleavedMarker(id).createNewFile()
        }
        matcherCache = null
        playlistIds.forEach { autoMatch(it, MatchScope.RETRY, onProgress) }
        releaseMatcher()
        late.keys.forEach { id -> runCatching { importProgrammes(id, epg.mappedXmlIds(id).toHashSet()) } }
    }

    /**
     * Downloads one source and reads its channels: their number, null if the file hadn't changed on the server (nothing
     * is read again), or why it failed (also stored with the source).
     */
    private suspend fun load(s: EpgSourceEntity, onProgress: (GuideStep) -> Unit): Result<Int?> {
        onProgress(GuideStep.Downloading(s.name))
        val result = runCatching {
            val tags =
                download(s) { percent -> onProgress(GuideStep.Downloading(s.name, percent / 100f)) }
                    ?: return@runCatching null
            onProgress(GuideStep.Reading(s.name))
            // The tags only once the file has read well: a bad copy is fetched again next time, not kept as unchanged.
            importChannels(s).also { metaFile(s.id).writeText(tags) }
        }
        if (result.getOrNull() == null && result.isSuccess) {
            if (s.lastError != null) epg.setSourceError(s.id, null)
            return result
        }
        epg.setImportResult(
            s.id,
            at = if (result.isSuccess) clock() else s.lastImportAt,
            channelCount = result.getOrNull() ?: s.channelCount,
            error = result.exceptionOrNull()?.let(GuideError::saved)
        )
        return result
    }

    /**
     * Matches a playlist's channels ([scope] says which) to guide channels. A channel with nothing good enough is stored
     * with no guide, so later updates know it was tried. Returns a row for every channel checked.
     */
    suspend fun autoMatch(
        playlistId: Long,
        scope: MatchScope = MatchScope.ALL,
        onProgress: (GuideStep) -> Unit = {}
    ): List<ChannelEpgMappingEntity> = withContext(Dispatchers.Default) {
        val channels = when (scope) {
            MatchScope.ALL -> {
                val manual = epg.manualStreamIds(playlistId).toHashSet()
                db.channels().names(playlistId).filter { it.streamId !in manual }
            }
            MatchScope.NEW -> epg.namesToMatch(playlistId, retryFailed = false, visibleOnly = false)
            MatchScope.RETRY -> epg.namesToMatch(playlistId, retryFailed = true, visibleOnly = false)
            MatchScope.MISSING -> epg.namesToMatch(playlistId, retryFailed = true, visibleOnly = true)
        }
        // Building the matcher is the slow part: skip it when there's nothing to match.
        if (channels.isEmpty() && scope != MatchScope.ALL) return@withContext emptyList()
        val matcher = matcher()
        var reportedAt = 0L
        val rows = channels.mapIndexed { i, c ->
            // Matching is quick per channel: progress at most twice a second, as for downloads.
            if (i % PROGRESS_EVERY == 0 && System.currentTimeMillis() - reportedAt >= REPORT_EVERY_MS) {
                reportedAt = System.currentTimeMillis()
                onProgress(GuideStep.Matching(i, channels.size))
            }
            val m = matcher.autoMatch(c.name, c.providerEpgId, scope.threshold)
            ChannelEpgMappingEntity(
                playlistId,
                c.streamId,
                m?.candidate?.sourceId,
                m?.candidate?.xmlId,
                manual = false,
                score = m?.score ?: 0.0
            )
        }
        onProgress(GuideStep.Matching(channels.size, channels.size))
        db.withTransaction {
            if (scope == MatchScope.ALL) epg.clearAutoMappings(playlistId)
            rows.chunked(BATCH).forEach { epg.upsertMappings(it) }
        }
        rows
    }

    /**
     * Tries again, with only near-certain matches, for every visible channel without a guide (except those the user set
     * to "no guide"), then loads the programmes of the guide channels found.
     */
    suspend fun matchMissing(playlistIds: List<Long>, onProgress: (GuideStep) -> Unit = {}): MatchResult = withContext(Dispatchers.IO) {
        val checked = playlistIds.flatMap { autoMatch(it, MatchScope.MISSING, onProgress) }
        val found = checked.filter { it.sourceId != null }
        releaseMatcher()
        val names = epg.enabledSources().associate { it.id to it.name }
        found.groupBy({ it.sourceId!! }, { it.xmlId!! }).forEach { (source, xmlIds) ->
            onProgress(GuideStep.Loading(names[source]))
            runCatching { importProgrammes(source, xmlIds.toHashSet(), onlyThese = true) }
        }
        changed()
        MatchResult(checked.size, found.size)
    }

    fun releaseMatcher() {
        matcherCache = null
    }

    suspend fun suggestions(channelName: String, providerEpgId: String?, limit: Int = 8): List<EpgMatch> =
        withContext(Dispatchers.Default) {
            matcher().suggestions(channelName, providerEpgId, limit)
        }

    suspend fun searchGuideChannels(query: String) = epg.searchChannels(likeArg(query), 50)

    suspend fun mappingInfo(playlistId: Long, ids: List<Long>) = if (ids.isEmpty()) emptyList() else epg.mappingInfo(playlistId, ids)

    /** The user's choice for a channel. Programmes for the chosen guide channel are loaded from the cached file. */
    suspend fun setManual(playlistId: Long, streamId: Long, sourceId: Long, xmlId: String, offsetMinutes: Int = 0) = applyMapping(
        ChannelEpgMappingEntity(playlistId, streamId, sourceId, xmlId, manual = true, score = 1.0, offsetMinutes = offsetMinutes)
    )

    private suspend fun applyMapping(m: ChannelEpgMappingEntity) {
        epg.upsertMappings(listOf(m))
        if (m.sourceId != null && m.xmlId != null) queueProgrammeImport(m.sourceId, m.xmlId)
        changed()
    }

    private val pendingImports = HashMap<Long, MutableSet<String>>()
    private var importScheduled = false

    /**
     * Reading a guide file takes seconds on a slow box, so channels re-mapped in quick succession are collected and
     * their programmes loaded in one pass per guide. A running import is never cancelled; changes made while it runs
     * are picked up by the next one.
     */
    private fun queueProgrammeImport(sourceId: Long, xmlId: String) = synchronized(pendingImports) {
        pendingImports.getOrPut(sourceId) { HashSet() } += xmlId
        if (importScheduled) return@synchronized
        importScheduled = true
        scope.launch(Dispatchers.IO) {
            delay(REMAP_DEBOUNCE_MS)
            val work = synchronized(pendingImports) {
                importScheduled = false
                pendingImports.toMap().also { pendingImports.clear() }
            }
            work.forEach { (source, ids) -> runCatching { importProgrammes(source, ids, onlyThese = true) } }
            changed()
        }
    }

    suspend fun setNoGuide(playlistId: Long, streamId: Long) {
        epg.upsertMappings(listOf(ChannelEpgMappingEntity(playlistId, streamId, null, null, manual = true, score = 0.0)))
        changed()
    }

    suspend fun setOffset(playlistId: Long, streamId: Long, offsetMinutes: Int) {
        val m = epg.mapping(playlistId, streamId) ?: return
        epg.upsertMappings(listOf(m.copy(offsetMinutes = offsetMinutes, manual = true)))
        changed()
    }

    suspend fun resetToAutomatic(playlistId: Long, streamId: Long, channelName: String, providerEpgId: String?) {
        epg.deleteMapping(playlistId, streamId)
        val m = withContext(Dispatchers.Default) { matcher().autoMatch(channelName, providerEpgId) }
        if (m == null) {
            changed()
        } else {
            applyMapping(
                ChannelEpgMappingEntity(playlistId, streamId, m.candidate.sourceId, m.candidate.xmlId, manual = false, score = m.score)
            )
        }
    }

    /**
     * Programmes overlapping [from]..[to] for the given channels, grouped by stream id and sorted by start.
     * Lists that only show titles pass [details] = false to skip loading descriptions.
     */
    suspend fun programmes(
        playlistId: Long,
        streamIds: Collection<Long>,
        from: Long,
        to: Long,
        details: Boolean = true
    ): Map<Long, List<ChannelProgramme>> {
        if (streamIds.isEmpty()) return emptyMap()
        return streamIds.chunked(MAX_SQL_ARGS).flatMap { epg.programmes(playlistId, it, from, to, details) }.groupBy { it.streamId }
    }

    /**
     * [p] with its description (lists of programmes are read without it: descriptions are large), as the guide has
     * it; [p] itself if it has one already or the guide no longer has [p].
     */
    suspend fun withDetails(playlistId: Long, p: ChannelProgramme): ChannelProgramme {
        if (p.description != null) return p
        return programmes(playlistId, listOf(p.streamId), p.startMs, p.stopMs)[p.streamId]?.firstOrNull { it.startMs == p.startMs } ?: p
    }

    /** Deletes cached guide files whose source no longer exists. */
    suspend fun deleteOrphanFiles() = withContext(Dispatchers.IO) {
        val valid = epg.allSourceIds().flatMap { listOf(sourceFile(it).name, interleavedMarker(it).name, metaFile(it).name) }.toSet()
        File(cacheDir, "epg").listFiles()?.filter { it.name !in valid }?.forEach { it.delete() }
    }

    /** After the sources were replaced wholesale (restoring a backup): their downloaded files and matcher are stale. */
    suspend fun forgetDownloads() = withContext(Dispatchers.IO) {
        matcherCache = null
        File(cacheDir, "epg").listFiles()?.forEach { it.delete() }
        changed()
    }

    // --- import internals ---

    private fun sourceFile(id: Long) = File(cacheDir, "epg/source-$id.xml.gz")

    /** Present for a guide whose channels aren't all before its programmes: its channels are read from the whole file. */
    private fun interleavedMarker(id: Long) = File(cacheDir, "epg/source-$id.interleaved")

    /**
     * Downloads a source's file, saved gzipped (compressing it if the server sent plain XML). Returns the server's
     * version tags for it (for [metaFile]), or null when the server says it hasn't changed since the last download (then
     * the copy kept is used). [onProgress] gets the percentage done when the server gives the size.
     */
    private fun download(s: EpgSourceEntity, onProgress: (Int) -> Unit): String? {
        val target = sourceFile(s.id)
        target.parentFile?.mkdirs()
        val tmp = File(target.path + ".part")
        val meta = metaFile(s.id)
        // The server's version tags for the copy kept, if it sent any (and the copy is still here, from this address).
        val lines = meta.takeIf { target.exists() && it.exists() }?.readLines().orEmpty()
        val (etag, modified) = lines.takeIf { it.firstOrNull() == s.url }.orEmpty()
            .map { it.ifEmpty { null } }.let { it.getOrNull(1) to it.getOrNull(2) }
        val request = Request.Builder().url(s.url).apply {
            etag?.let { header("If-None-Match", it) }
            modified?.let { header("If-Modified-Since", it) }
        }.build()
        val tags = http.newCall(request).execute().use { response ->
            if (response.code == HttpURLConnection.HTTP_NOT_MODIFIED) return null
            if (!response.isSuccessful) throw GuideError.server(response.code)
            val length = response.body!!.contentLength()
            val counted = CountingInputStream(response.body!!.byteStream(), length, onProgress)
            val body = BufferedInputStream(counted)
            body.mark(2)
            val gz = body.read() == 0x1f && body.read() == 0x8b
            body.reset()
            tmp.outputStream().use { out ->
                if (gz) {
                    body.copyTo(out)
                } else {
                    object : GZIPOutputStream(out, 64 * 1024) {
                        init {
                            def.setLevel(Deflater.BEST_SPEED)
                        }
                    }.use { body.copyTo(it) }
                }
            }
            "${s.url}\n${response.header("ETag").orEmpty()}\n${response.header("Last-Modified").orEmpty()}\n"
        }
        meta.delete()
        if (!tmp.renameTo(target)) throw GuideError.notSaved()
        return tags
    }

    /** Counts what is read of a file of [length] bytes and reports the percentage done, for progress messages. */
    private class CountingInputStream(input: InputStream, private val length: Long, private val onPercent: (Int) -> Unit) :
        java.io.FilterInputStream(input) {
        private var read = 0L
        private var reported = -1

        override fun read(): Int = super.read().also { if (it >= 0) count(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count(it.toLong()) }

        private var reportedAt = 0L

        private fun count(n: Long) {
            if (length <= 0) return
            read += n
            val percent = (read * 100 / length).toInt().coerceAtMost(100)
            // Every progress message redraws the progress shown: at most twice a second.
            val now = System.currentTimeMillis()
            if (percent != reported && (now - reportedAt >= REPORT_EVERY_MS || percent == 100)) {
                reported = percent
                reportedAt = now
                onPercent(percent)
            }
        }
    }

    /** The source's file, unpacked; [onProgress] (if given) gets the percentage of it read so far. */
    private fun open(sourceId: Long, onProgress: ((Int) -> Unit)? = null): InputStream {
        val file = sourceFile(sourceId)
        val raw: InputStream = file.inputStream().let { if (onProgress != null) CountingInputStream(it, file.length(), onProgress) else it }
        return GZIPInputStream(raw.buffered(64 * 1024), 64 * 1024)
    }

    /** The address, then the server's version tags (ETag, Last-Modified), of the downloaded file: to ask next time whether it changed. */
    private fun metaFile(id: Long) = File(cacheDir, "epg/source-$id.meta")

    private fun parser(start: Long = Long.MIN_VALUE, end: Long = Long.MAX_VALUE) = XmltvParser({ Xml.newPullParser() }, start, end)

    /** Replaces the stored channel list of a source. Returns the number of channels. */
    private suspend fun importChannels(s: EpgSourceEntity): Int {
        val rows = ArrayList<EpgChannelEntity>()
        open(s.id).use { input ->
            val onChannel: suspend (XmltvChannel) -> Unit = { c ->
                val name = c.names.firstOrNull() ?: c.id
                rows += EpgChannelEntity(s.id, c.id, name, ChannelNames.normalize(name))
            }
            if (interleavedMarker(s.id).exists()) {
                parser().parse(input, { false }, onChannel) {}
            } else {
                parser().parseChannels(input, onChannel)
            }
        }
        db.withTransaction {
            epg.clearChannels(s.id)
            rows.chunked(BATCH).forEach { epg.upsertChannels(it) }
        }
        matcherCache = null
        return rows.size
    }

    /**
     * Stores programmes for [xmlIds] within the keep window, in batches as they are read. Rows are replaced in place
     * and older rows removed at the end, so screens never see a half-empty guide during an import.
     * [onlyThese] limits the clean-up to [xmlIds] (used when one channel is re-mapped).
     */
    private suspend fun importProgrammes(
        sourceId: Long,
        xmlIds: Set<String>,
        onlyThese: Boolean = false,
        known: Set<String>? = null,
        onProgress: ((Int) -> Unit)? = null
    ): List<EpgChannelEntity> {
        // With [known] (the channels read before), returns any others the file has: see addLateChannels.
        val late = ArrayList<EpgChannelEntity>()
        if (!sourceFile(sourceId).exists()) return late
        if (xmlIds.isEmpty() && known == null) {
            if (!onlyThese) epg.clearProgrammes(sourceId) // nothing uses this guide any more
            return late
        }
        val now = clock()
        val batch = ArrayList<ProgrammeEntity>(BATCH)
        val onChannel: (suspend (XmltvChannel) -> Unit)? = known?.let {
            { c ->
                if (c.id !in known) {
                    val name = c.names.firstOrNull() ?: c.id
                    late += EpgChannelEntity(sourceId, c.id, name, ChannelNames.normalize(name))
                }
            }
        }
        open(sourceId, onProgress).use { input ->
            parser(now - KEEP_PAST_MS, now + KEEP_FUTURE_MS).parse(input, wantProgrammes = {
                it in xmlIds
            }, onChannel = onChannel, onProgramme = { p ->
                batch += ProgrammeEntity(
                    sourceId, p.channelId, p.startMs, p.stopMs, p.title, p.subtitle, p.description, now, p.kind, p.season, p.episode
                )
                if (batch.size >= BATCH) {
                    epg.insertProgrammes(batch.toList())
                    batch.clear()
                }
            })
        }
        if (batch.isNotEmpty()) epg.insertProgrammes(batch)
        if (onlyThese) {
            xmlIds.chunked(MAX_SQL_ARGS).forEach { epg.deleteOlderProgrammes(sourceId, it, now) }
        } else {
            epg.deleteOlderProgrammes(sourceId, now)
        }
        return late
    }

    private companion object {
        const val BATCH = 1000

        /**
         * A guide kept from before (not downloaded again, or not changed) is read in full again after this long, so
         * its stored programmes still reach about two days ahead.
         */
        const val REREAD_AFTER_MS = 20 * 3_600_000L
        const val PROGRESS_EVERY = 100

        /** How often progress is reported, at most. */
        const val REPORT_EVERY_MS = 500L
        const val KEEP_PAST_MS = 2 * 3_600_000L
        const val KEEP_FUTURE_MS = 3 * 24 * 3_600_000L
        const val REMAP_DEBOUNCE_MS = 1_500L
    }
}
