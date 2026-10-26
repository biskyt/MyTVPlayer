package com.biskyt.mytvplayer.data

import androidx.room.withTransaction
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.appString
import com.biskyt.mytvplayer.core.m3u.M3uCatchup
import com.biskyt.mytvplayer.core.m3u.M3uParser
import com.biskyt.mytvplayer.core.xtream.XtreamCategory
import com.biskyt.mytvplayer.core.xtream.XtreamClient
import com.biskyt.mytvplayer.core.xtream.XtreamCredentials
import com.biskyt.mytvplayer.core.xtream.XtreamException
import com.biskyt.mytvplayer.core.xtream.XtreamLiveStream
import com.biskyt.mytvplayer.data.db.AppDatabase
import com.biskyt.mytvplayer.data.db.CAT_LIVE
import com.biskyt.mytvplayer.data.db.CAT_MOVIE
import com.biskyt.mytvplayer.data.db.CAT_SERIES
import com.biskyt.mytvplayer.data.db.CategoryEntity
import com.biskyt.mytvplayer.data.db.ChannelEntity
import com.biskyt.mytvplayer.data.db.ChannelPrefs
import com.biskyt.mytvplayer.data.db.KIND_M3U
import com.biskyt.mytvplayer.data.db.PlaylistEntity
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody

/** Playlists (Xtream accounts and M3U playlists) and downloading their channel lists. */
class PlaylistRepository(
    private val db: AppDatabase,
    private val http: OkHttpClient,
    private val settings: SettingsRepository,
    private val epg: EpgRepository,
    /** Where M3U playlists are downloaded to while being read (deleted afterwards). */
    private val workDir: File,
    private val categoryStore: CategoryStore
) {
    private val playlists = db.playlists()
    private val categories = db.categories()
    private val channels = db.channels()

    fun observePlaylists(): Flow<List<PlaylistEntity>> = playlists.observeAll()

    suspend fun get(id: Long): PlaylistEntity? = playlists.get(id)

    suspend fun allIds(): List<Long> = playlists.all().map { it.id }

    fun client(p: PlaylistEntity) = client(p.server, p.username, p.password)

    private fun client(server: String, username: String, password: String) =
        XtreamClient(http, XtreamCredentials(server, username, password))

    /**
     * Reads the account's expiry date and connection limit, from an Xtream login or from an Xtream panel's M3U link
     * (`…/get.php?username=…&password=…`). Optional: if it fails, what was known is kept.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // any failure (network, odd answers) leaves it as it was
    private suspend fun refreshAccount(p: PlaylistEntity) {
        val client = if (p.isM3u) xtreamLoginIn(p.server)?.let { (server, user, pass) -> client(server, user, pass) } else client(p)
        // A plain M3U link has no account (e.g. one that used to be a provider's link): nothing to show.
        if (client == null) return playlists.setAccount(p.id, null, null)
        val account = try {
            client.account()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        playlists.setAccount(p.id, account.expiresAtEpochSec, account.maxConnections)
    }

    /** Checks the login works; throws XtreamException with a readable message otherwise. */
    suspend fun testLogin(server: String, username: String, password: String) {
        val active = withUserXtreamErrors { client(server, username, password).account().active }
        if (!active) throw XtreamException(appString(R.string.data_account_inactive))
    }

    /**
     * Saves a new playlist and makes it the active one. Everything that can fail (login, category download) happens
     * before anything is written, so a failed attempt leaves nothing behind.
     */
    suspend fun add(name: String, server: String, username: String, password: String): Long {
        val c = client(server.trim(), username.trim(), password)
        val active = withUserXtreamErrors { c.account().active }
        if (!active) throw XtreamException(appString(R.string.data_account_inactive))
        val remote = withUserXtreamErrors { c.liveCategories() }
        val id =
            insertAndActivate(PlaylistEntity(name = name, server = server.trim(), username = username.trim(), password = password), remote)
        // Movie and series categories too, so they can be chosen before the first download. Providers without them
        // (or with a broken list) get them on a later sync.
        runCatching { categoryStore.store(id, CAT_MOVIE, c.movieCategories()) }
        runCatching { categoryStore.store(id, CAT_SERIES, c.seriesCategories()) }
        return id
    }

    /**
     * Sets up the free channels, films and series; the new playlist's id, or a message for the user. Never throws (but
     * for cancellation): any failure (network, full storage…) must leave a way forward, on first launch above all.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun addFree(): Result<Long> = try {
        Result.success(addM3u(appString(R.string.data_free_playlist_name), FREE_PLAYLIST_URL, FREE_MOVIES_URL, FREE_SERIES_URL))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(Exception((e as? IOException)?.let(::userMessage) ?: appString(R.string.data_free_failed), e))
    }

    /**
     * Saves a new M3U playlist (after checking it can be downloaded and read) and makes it the active one, with
     * optional movie and series playlists. A provider's M3U link (`…/get.php?username=…`) is added as its login
     * instead, which brings the provider's movies, series and catch-up; if the panel won't answer logins, as a playlist.
     */
    suspend fun addM3u(name: String, url: String, moviesUrl: String? = null, seriesUrl: String? = null): Long {
        // Only a plain link, with no movie or series playlists of its own: one asking for another stream format or
        // a selection of channels stays the playlist it is, as before.
        val plainLink = moviesUrl.isNullOrBlank() && seriesUrl.isNullOrBlank() && isPlainPanelLink(url)
        if (plainLink) {
            xtreamLoginIn(url)?.let { (server, user, pass) ->
                try {
                    return add(name, server, user, pass)
                } catch (_: IOException) {
                    // Not every panel answers logins as well as playlists: carry on with the playlist.
                }
            }
        }
        val remote = withM3u(url.trim()) { file -> m3uGroups(file) }
        val p = PlaylistEntity(
            name = name,
            kind = KIND_M3U,
            server = url.trim(),
            username = "",
            password = "",
            moviesUrl = moviesUrl?.trim()?.ifEmpty { null },
            seriesUrl = seriesUrl?.trim()?.ifEmpty { null }
        )
        return insertAndActivate(p, remote)
    }

    private suspend fun insertAndActivate(p: PlaylistEntity, remote: List<XtreamCategory>): Long {
        val id = db.withTransaction {
            playlists.insert(
                p.copy(
                    name = p.name.ifBlank { appString(R.string.data_playlist_default_name) }
                )
            ).also { categoryStore.store(it, CAT_LIVE, remote, groupByName = p.isM3u) }
        }
        settings.update { it.copy(activePlaylistId = id) }
        return id
    }

    /** Checks an M3U URL can be downloaded and looks like a playlist, reading only its first line. */
    suspend fun testM3u(url: String) = withContext(Dispatchers.IO) {
        fetch(url.trim()) { body -> checkPlaylist(body.charStream().buffered()) }
    }

    /** Checks a playlist's address (M3U) or login (Xtream) works; throws XtreamException with a readable message. */
    suspend fun testConnection(m3u: Boolean, server: String, username: String, password: String) =
        if (m3u) testM3u(server) else testLogin(server, username, password)

    suspend fun update(p: PlaylistEntity) {
        val old = playlists.get(p.id)
        if (old != null && (old.server != p.server || old.username != p.username || old.password != p.password)) {
            testConnection(p.isM3u, p.server, p.username, p.password)
            // Perhaps another provider now: what the old one fell back to may not apply.
            settings.forgetStreamFormat(p.id)
        }
        playlists.upsert(p)
    }

    /**
     * Hides a category, as unticking it in Settings › Languages & categories does: its channels (or titles) go now,
     * without downloading anything, and updates leave it out.
     */
    suspend fun hideCategory(cat: CategoryEntity) {
        val categories = db.categories()
        db.withTransaction {
            categories.setIncluded(cat.playlistId, cat.type, listOf(cat.categoryId), false)
            when (cat.type) {
                CAT_LIVE -> db.channels().deleteCategory(cat.playlistId, cat.categoryId)
                CAT_MOVIE -> db.vod().deleteMovieCategory(cat.playlistId, cat.categoryId)
                else -> db.vod().deleteSeriesCategory(cat.playlistId, cat.categoryId)
            }
            categories.refreshCounts(cat.playlistId, cat.type)
        }
    }

    suspend fun delete(id: Long) {
        db.withTransaction {
            playlists.deleteChannels(id)
            playlists.deleteCategories(id)
            playlists.deleteFavourites(id)
            playlists.deleteRecents(id)
            playlists.deleteMappings(id)
            playlists.deleteGuideLinks(id)
            playlists.deleteMovies(id)
            playlists.deleteSeries(id)
            playlists.deleteEpisodes(id)
            playlists.deleteProgress(id)
            playlists.deleteVodFavourites(id)
            playlists.delete(id)
        }
        db.epg().providerSource(id)?.let { epg.removeSource(it.id) }
        epg.clearUnused()
        settings.forgetStreamFormat(id)
        val next = playlists.all().firstOrNull()?.id ?: 0
        if (settings.current().activePlaylistId == id) settings.update { it.copy(activePlaylistId = next) }
    }

    /**
     * Downloads the category list. Existing choices are kept; new categories are included when their detected
     * language group matches one the user already includes (or, the first time, the TV's own country/language).
     */
    suspend fun refreshCategories(playlistId: Long) {
        val p = playlists.get(playlistId) ?: return
        val remote = if (p.isM3u) withM3u(p.server) { m3uGroups(it) } else client(p).liveCategories()
        categoryStore.store(playlistId, CAT_LIVE, remote, groupByName = p.isM3u)
    }

    /**
     * Downloads the channels of included categories. Only a few categories → one request each;
     * otherwise one full request, dropping excluded rows as they stream in. Returns the channel count.
     */
    suspend fun syncChannels(playlistId: Long, onProgress: (Int) -> Unit = {}): Int {
        val p = playlists.get(playlistId) ?: return 0
        // Alongside the download: a slow account answer mustn't hold up the channels.
        return coroutineScope {
            launch { refreshAccount(p) }
            if (p.isM3u) syncM3u(p, onProgress) else syncXtream(p, playlistId, onProgress)
        }
    }

    private suspend fun syncXtream(p: PlaylistEntity, playlistId: Long, onProgress: (Int) -> Unit): Int {
        val client = client(p)
        refreshCategories(playlistId)
        val out = ChannelWriter(playlistId, onProgress)
        categoryStore.forIncluded(playlistId, CAT_LIVE, { s: XtreamLiveStream ->
            s.categoryId
        }, { cat, emit -> client.liveStreams(cat, emit) }) { s ->
            out.add(
                ChannelEntity(
                    playlistId = playlistId, streamId = s.streamId, number = s.number, name = s.name, categoryId = s.categoryId,
                    logoUrl = s.logoUrl, providerEpgId = s.epgChannelId, archiveDays = s.archiveDays,
                    hidden = out.hidden(s.streamId), sortOrder = out.sortOrder(s.streamId), syncStamp = out.stamp
                )
            )
        }
        val count = out.finish()
        epg.ensureProviderSource(p, client.xmltvUrl())
        return count
    }

    /**
     * M3U: the whole playlist comes in one file, read in a single pass that collects both its groups (stored as
     * categories) and the channels of included groups. Channel ids are derived from the guide id and name so
     * favourites and the user's choices survive providers rewriting stream URLs.
     */
    private suspend fun syncM3u(p: PlaylistEntity, onProgress: (Int) -> Unit): Int = withM3u(p.server, reuse = p.lastSyncAt == 0L) { file ->
        var known = categories.all(p.id, CAT_LIVE)
        if (known.isEmpty()) {
            // Not normally reached (adding a playlist stores its groups), but the defaults need every group up front.
            categoryStore.store(p.id, CAT_LIVE, m3uGroups(file), groupByName = true)
            known = categories.all(p.id, CAT_LIVE)
        }
        // Included: existing groups as the user chose (entries in none as "Other" was, before 1.1.0); new groups by the
        // rule categoryStore.store applies when it records them below, from the same saved choices.
        val includesNew = categoryStore.includesNew(known, groupByName = true)
        val chosen = known.associate { it.categoryId to it.included }
        val include = HashMap(chosen)
        val groups = LinkedHashSet<String>()
        val out = ChannelWriter(p.id, onProgress)
        val ids = HashSet<Long>()
        var index = 0
        val guides = file.bufferedReader().use { reader ->
            M3uParser.parse(reader) { e ->
                index++
                // Ids are given to every entry, so a duplicate's id doesn't depend on which groups are included.
                val id = uniqueId("${e.tvgId.orEmpty()}|${e.name}", ids)
                val group = M3uCategories.idOf(e)
                groups += group
                if (!include.getOrPut(group) { M3uCategories.inherited(chosen, group) ?: includesNew(M3uCategories.category(group)) }) {
                    return@parse
                }
                val catchup = M3uCatchup.typeFor(e.catchup, e.catchupSource, e.url)
                out.add(
                    ChannelEntity(
                        playlistId = p.id, streamId = id, number = e.number ?: index, name = e.name, categoryId = group,
                        logoUrl = e.logo, providerEpgId = e.tvgId?.substringBefore('@'),
                        archiveDays = if (catchup == null) 0 else M3uCatchup.daysFor(e.catchupDays),
                        hidden = out.hidden(id), sortOrder = out.sortOrder(id), syncStamp = out.stamp,
                        streamUrl = e.url, userAgent = e.userAgent, referrer = e.referrer,
                        catchupType = catchup, catchupSource = e.catchupSource
                    )
                )
            }
        }
        categoryStore.store(p.id, CAT_LIVE, groups.map(M3uCategories::category), groupByName = true)
        val count = out.finish()
        guides.firstOrNull()?.let { epg.ensureProviderSource(p, it) }
        count
    }

    /**
     * Writes one sync's channels in batches (keeping the user's hidden/order choices), then removes channels the
     * provider no longer lists and updates category counts.
     */
    private inner class ChannelWriter(private val playlistId: Long, onProgress: (Int) -> Unit) {
        val stamp = System.currentTimeMillis()
        private var prefs: Map<Long, ChannelPrefs>? = null
        private val batch = Batch(BATCH, channels::upsert, onProgress)

        private suspend fun prefs() = prefs ?: channels.prefs(playlistId).associateBy { it.streamId }.also { prefs = it }

        suspend fun hidden(id: Long) = prefs()[id]?.hidden ?: false

        suspend fun sortOrder(id: Long) = prefs()[id]?.sortOrder

        suspend fun add(ch: ChannelEntity) = batch.add(ch)

        suspend fun finish(): Int {
            batch.flush()
            db.withTransaction {
                channels.deleteStale(playlistId, stamp)
                categories.refreshCounts(playlistId, CAT_LIVE)
            }
            playlists.setLastSync(playlistId, stamp)
            return batch.count
        }
    }

    /**
     * Runs [block] on a downloaded copy of an M3U playlist. With [reuse], a copy from the last few minutes is used
     * instead, so adding a playlist and then loading its channels downloads it once. Each download goes to its own
     * temp file and is then swapped in whole, so concurrent readers never see a half-written file.
     */
    suspend fun <T> withM3u(url: String, reuse: Boolean = false, block: suspend (File) -> T): T = withContext(Dispatchers.IO) {
        workDir.mkdirs()
        val now = System.currentTimeMillis()
        // Old copies are only deleted well after they stop being reused, so a slow reader never loses its file.
        workDir.listFiles()?.forEach { if (now - it.lastModified() > KEEP_DOWNLOAD_MS) it.delete() }
        val cached = File(workDir, "playlist-${stableId(url)}.m3u")
        if (!reuse || !cached.exists() || now - cached.lastModified() > REUSE_DOWNLOAD_MS) {
            val temp = File.createTempFile("download", ".tmp", workDir)
            try {
                fetch(url) { body -> temp.outputStream().use { body.byteStream().copyTo(it) } }
                temp.bufferedReader().use { checkPlaylist(it) }
                if (!temp.renameTo(cached)) throw XtreamException(appString(R.string.data_playlist_save_failed))
            } finally {
                temp.delete()
            }
        }
        block(cached)
    }

    /** Runs [block] on a successful download's body; failures become readable XtreamExceptions. */
    private fun <T> fetch(url: String, block: (ResponseBody) -> T): T {
        val request = runCatching { Request.Builder().url(url).build() }.getOrNull()
            ?: throw XtreamException(appString(R.string.data_playlist_address_invalid))
        return try {
            http.newCall(request).execute().use {
                if (!it.isSuccessful) throw XtreamException(appString(R.string.data_playlist_refused, it.code))
                block(it.body!!)
            }
        } catch (e: XtreamException) {
            throw e
        } catch (e: IOException) {
            throw XtreamException(appString(R.string.data_playlist_download_failed, e.message.toString()), e)
        }
    }

    private fun checkPlaylist(reader: BufferedReader) {
        val first = reader.lineSequence().firstOrNull { it.isNotBlank() }
        if (first == null || !M3uParser.looksLikePlaylist(first)) throw XtreamException(appString(R.string.data_not_m3u))
    }

    /** Distinct group titles in playlist order; channels without one go in a category with no name (see M3uCategories). */
    private suspend fun m3uGroups(file: File): List<XtreamCategory> {
        val groups = LinkedHashSet<String>()
        file.bufferedReader().use { reader -> M3uParser.parse(reader) { groups += M3uCategories.idOf(it) } }
        return groups.map(M3uCategories::category)
    }

    /** A [stableId] not yet in [taken] (which it's added to): the same name listed twice gets a second id. */
    fun uniqueId(key: String, taken: MutableSet<Long>): Long {
        var id = stableId(key)
        while (!taken.add(id)) id = stableId("$id#")
        return id
    }

    /** 64-bit FNV-1a hash, kept positive: a stable channel (or M3U movie or episode) id from text. */
    fun stableId(key: String): Long {
        var h = -0x340d631b7bdddcdbL
        for (c in key) {
            h = h xor c.code.toLong()
            h *= 0x100000001b3L
        }
        return h and Long.MAX_VALUE
    }

    companion object {
        /** A panel link with nothing beyond the login and the usual playlist type and output (MPEG-TS, like logins play). */
        internal fun isPlainPanelLink(m3uUrl: String): Boolean {
            val url = m3uUrl.trim().toHttpUrlOrNull() ?: return false
            val allowed = setOf("username", "password", "type", "output")
            return url.queryParameterNames.all { it in allowed } &&
                url.queryParameter("output").let { it == null || it == "ts" || it == "mpegts" }
        }

        /** The server, user name and password in an Xtream panel's M3U link (`http://host:port/get.php?username=…`). */
        fun xtreamLoginIn(m3uUrl: String): Triple<String, String, String>? {
            val url = m3uUrl.trim().toHttpUrlOrNull()?.takeIf { it.pathSegments.lastOrNull() == "get.php" } ?: return null
            val user = url.queryParameter("username")?.takeIf { it.isNotEmpty() } ?: return null
            val pass = url.queryParameter("password") ?: return null
            val server = url.newBuilder().removePathSegment(url.pathSize - 1).query(null).build().toString().trimEnd('/')
            return Triple(server, user, pass)
        }

        /**
         * Free TV that's legal to watch, with no sign-up (github.com/biskyt/legalFreeIPTVplaylist): live channels
         * streamed by their broadcasters or free ad-supported services, and public-domain or openly licensed films and
         * series. Offered on first launch; users can add their own providers.
         */
        private const val FREE_BASE = "https://biskyt.github.io/legalFreeIPTVplaylist"
        const val FREE_PLAYLIST_URL = "$FREE_BASE/live.m3u"
        const val FREE_MOVIES_URL = "$FREE_BASE/movies.m3u"
        const val FREE_SERIES_URL = "$FREE_BASE/series.m3u"

        /** The free playlist's addresses: now, and before 0.9.4 (iptv-org's channels by country, where users have it). */
        val FREE_PLAYLIST_URLS = setOf(FREE_PLAYLIST_URL, "https://iptv-org.github.io/iptv/index.country.m3u")
        private const val REUSE_DOWNLOAD_MS = 10 * 60_000L
        private const val KEEP_DOWNLOAD_MS = 60 * 60_000L

        private const val BATCH = 500
    }
}
