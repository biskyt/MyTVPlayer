package com.biskyt.mytvplayer.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.withTransaction
import com.biskyt.mytvplayer.core.sync.ChoiceKey
import com.biskyt.mytvplayer.core.sync.DeviceCode
import com.biskyt.mytvplayer.core.sync.GoogleAuth
import com.biskyt.mytvplayer.core.sync.GoogleTokens
import com.biskyt.mytvplayer.core.sync.ProgressFile
import com.biskyt.mytvplayer.core.sync.SyncException
import com.biskyt.mytvplayer.core.sync.SyncKey
import com.biskyt.mytvplayer.core.sync.SyncStore
import com.biskyt.mytvplayer.core.sync.SyncedChoice
import com.biskyt.mytvplayer.core.sync.SyncedDelete
import com.biskyt.mytvplayer.core.sync.SyncedProgress
import com.biskyt.mytvplayer.data.db.AppDatabase
import com.biskyt.mytvplayer.data.db.CAT_SERIES
import com.biskyt.mytvplayer.data.db.MAX_SQL_ARGS
import com.biskyt.mytvplayer.data.db.SyncChoiceEntity
import com.biskyt.mytvplayer.data.db.SyncTombstoneEntity
import com.biskyt.mytvplayer.data.db.WatchProgressEntity
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The sign-in for the sync between TVs. It is kept with the settings, but it is not part of [Settings], so a backup
 * never holds it.
 */
class SyncAccount(private val prefs: DataStore<Preferences>) {
    /**
     * [refreshToken] is null when the sync is off. [pending]: this TV has changes not sent yet (kept, so that they are
     * sent after a restart too), or its file has playlist keys of an older [ProgressFile.KEY_FORMAT].
     */
    data class State(
        val refreshToken: String? = null,
        val email: String? = null,
        val lastSyncedAt: Long = 0,
        val pending: Boolean = false
    ) {
        val on: Boolean get() = refreshToken != null
    }

    private object K {
        val token = stringPreferencesKey("sync_refresh_token")
        val email = stringPreferencesKey("sync_email")
        val last = longPreferencesKey("sync_last")
        val device = stringPreferencesKey("sync_device")
        val pending = booleanPreferencesKey("sync_pending")

        /** The [ProgressFile.KEY_FORMAT] of this TV's last file (none: 1). */
        val keys = intPreferencesKey("sync_key_format")
    }

    val state: Flow<State> = prefs.data.map {
        val oldKeys = it[K.token] != null && (it[K.keys] ?: 1) < ProgressFile.KEY_FORMAT
        State(it[K.token], it[K.email], it[K.last] ?: 0, it[K.pending] == true || oldKeys)
    }.distinctUntilChanged()

    suspend fun signIn(refreshToken: String, email: String?) {
        prefs.edit {
            it[K.token] = refreshToken
            if (email == null) it.remove(K.email) else it[K.email] = email
            it.remove(K.last)
        }
    }

    suspend fun signOut() {
        prefs.edit {
            it.remove(K.token)
            it.remove(K.email)
            it.remove(K.last)
            it.remove(K.pending)
        }
    }

    suspend fun setPending(pending: Boolean) {
        prefs.edit { if (pending) it[K.pending] = true else it.remove(K.pending) }
    }

    /** A sync ended at [at]; [pending]: changes came while it ran. */
    suspend fun synced(at: Long, pending: Boolean) {
        prefs.edit {
            if (it[K.token] == null) return@edit
            it[K.last] = at
            it[K.keys] = ProgressFile.KEY_FORMAT
            if (pending) it[K.pending] = true else it.remove(K.pending)
        }
    }

    /** A random name for this TV, made once. It names this TV's file in the sync folder. */
    suspend fun device(): String {
        prefs.data.first()[K.device]?.let { return it }
        var device = ""
        prefs.edit { device = it[K.device] ?: UUID.randomUUID().toString().also { new -> it[K.device] = new } }
        return device
    }
}

/**
 * Keeps the place in films and episodes, and the user's choices (favourites, recently watched channels, hidden
 * channels, ticked and restricted categories, guides), the same on all the user's TVs, through the app's own folder (MyTVPlayer) in
 * their Google Drive. It is off until the user signs in (Settings › Sync between TVs).
 *
 * - Each TV writes only its own file: all its progress, what it forgot, and its choices with when each last changed
 *   (see [ProgressFile] and ChoiceLog). The newest change to each wins.
 * - [choices] applies the choices from other TVs. When they change the lists to download, [onChoices] downloads them
 *   again.
 * - **A sync** reads the other TVs' files that changed since the last sync, keeps the newest change to each item, then
 *   sends this TV's file if this TV has changes. Items of playlists this TV does not have are left out. Open pages show
 *   what a sync brings in, as their lists come from the database.
 * - **When:** when the app opens, when Home or a film or series page opens, and when playback stops or the user
 *   forgets something. While a film plays, saves only mark that there are changes. Leaving the app with changes not
 *   sent finishes the sync in the background ([finish], from ProgressSyncWorker).
 * - **Never in the way:** a sync runs in the background, and nothing waits for it. Only one runs at a time: a request
 *   made while one runs is dropped, not queued.
 * - **Off:** nothing runs, nothing is sent, and nothing more is written to the database.
 */
@Suppress("TooManyFunctions") // small steps of one job
class ProgressSync(
    private val db: AppDatabase,
    val account: SyncAccount,
    /** Null when the app was built without a Google client: the setting is then hidden. */
    private val auth: GoogleAuth?,
    private val scope: CoroutineScope,
    private val store: (token: suspend (renew: Boolean) -> String) -> SyncStore,
    private val clock: () -> Long = System::currentTimeMillis,
    /** This TV's choices. Asked for at each sync, as the repositories that apply them are made after this. */
    private val choices: () -> ChoiceTarget = { ChoiceTarget.NONE },
    /** Choices from another TV changed what a playlist downloads (see [ChoiceTarget.apply]). */
    private val onChoices: (playlistId: Long, reload: Reload) -> Unit = { _, _ -> },
) : ProgressListener {
    val available: Boolean get() = auth != null

    /** Null until it is read from storage. */
    private val state = account.state.stateIn(scope, SharingStarted.Eagerly, null)

    override val on: Boolean get() = state.value?.on == true

    /** This TV has changes that the other TVs may not have yet: not sent, or a sync is sending them. */
    val hasChanges: Boolean get() = on && (dirty.get() || synchronized(this) { running?.isActive == true })

    private val _failure = MutableStateFlow<SyncException?>(null)

    /** The last sync did not finish (no connection, for example). The next one tries again. */
    val failure: StateFlow<SyncException?> = _failure.asStateFlow()

    private val vod = db.vod()
    private val dirty = AtomicBoolean(false)

    @Volatile private var access: GoogleTokens? = null

    /** The other TVs' files already read: id to the time they last changed. */
    private val seen = ConcurrentHashMap<String, String>()

    @Volatile private var lastSyncAt = 0L

    @Volatile private var ownName: String? = null

    /** The playlists at the last sync: when one is added, the other TVs' files are read again for it. */
    @Volatile private var lastPlaylists: Set<String> = emptySet()

    /**
     * Choices from files already read, for items that this TV does not have yet (it loads them later, or its lists are
     * older). Each sync tries them again. They are kept only as long as [seen], in memory.
     */
    private val waiting = ConcurrentHashMap<ChoiceKey, SyncedChoice>()

    @Volatile private var stopping = false
    private var running: Deferred<Unit>? = null

    init {
        // Changes not sent before the app last closed.
        scope.launch { if (state.filterNotNull().first().pending) dirty.set(true) }
    }

    // --- sign-in ---

    /** The code for the user to type at Google's address. */
    suspend fun startSignIn(): DeviceCode = checkNotNull(auth).start()

    /** Waits for the user to sign in with [code], then starts the first sync. */
    suspend fun finishSignIn(code: DeviceCode) {
        val tokens = checkNotNull(auth).await(code)
        val refresh = tokens.refreshToken ?: throw SyncException("Google sent no refresh token")
        access = tokens
        account.signIn(refresh, tokens.email)
        state.first { it?.on == true }
        forgetSeen()
        // Sends all of this TV's progress the first time.
        markChanged()
        syncSoon()
    }

    /**
     * Turns the sync off, in the background (leaving the page does not stop it): deletes this TV's file (if Drive can be
     * reached) and forgets the sign-in. No sync starts meanwhile.
     */
    fun stop() = scope.launch {
        stopping = true
        try {
            synchronized(this@ProgressSync) { running }?.cancelAndJoin()
            quietly {
                val s = store(::token)
                val own = ownName()
                s.list().firstOrNull { it.name == own }?.let { s.delete(it.id) }
            }
            signedOut()
        } finally {
            stopping = false
        }
    }

    private suspend fun signedOut() {
        access = null
        forgetSeen()
        dirty.set(false)
        account.signOut()
        state.first { it?.on == false }
    }

    private suspend fun token(renew: Boolean): String {
        access?.takeIf { !renew && it.expiresAt - EARLY_MS > clock() }?.let { return it.accessToken }
        val refresh = state.value?.refreshToken ?: throw SyncException("Not signed in", reason = SyncException.Reason.SIGNED_OUT)
        return try {
            checkNotNull(auth).refresh(refresh).also { access = it }.accessToken
        } catch (e: SyncException) {
            if (e.reason == SyncException.Reason.SIGNED_OUT) signedOut()
            throw e
        }
    }

    // --- when to sync ---

    /** The progress changed on this TV. [stopped]: playback stopped, or the user forgot something: sync now. */
    override fun changed(stopped: Boolean) {
        if (!on) return
        markChanged()
        if (stopped) syncSoon()
    }

    private fun markChanged() {
        if (dirty.compareAndSet(false, true)) scope.launch { account.setPending(true) }
    }

    /**
     * Starts a sync in the background and returns at once. Nothing happens while the sync is off, while a sync runs
     * (this request is dropped), or when this TV has no changes and synced less than [FRESH_MS] ago (unless [force]).
     */
    fun syncSoon(force: Boolean = false) {
        val s = state.value
        when {
            // Not read from storage yet (the app is starting): look once it is.
            s == null -> scope.launch { if (state.filterNotNull().first().on) syncSoon(force) }
            !s.on -> Unit
            force || dirty.get() || clock() - lastSyncAt >= FRESH_MS -> start()
        }
    }

    /**
     * Lets a running sync end, then syncs once more if this TV still has changes (or [always]). For leaving the app,
     * where the last changes must not wait for the next time it opens. Throws if the sync fails.
     */
    suspend fun finish(always: Boolean = false) {
        // In a new process (the app was closed): what was saved first.
        if (account.state.first().pending) dirty.set(true)
        synchronized(this) { running }?.join()
        if (on && (always || dirty.get())) start()?.await()
    }

    /** Null when a sync runs already: only one at a time. */
    @Synchronized
    private fun start(): Deferred<Unit>? {
        if (stopping || running?.isActive == true) return null
        return scope.async {
            try {
                syncNow()
                _failure.value = null
            } catch (e: SyncException) {
                _failure.value = e
                throw e
            }
        }.also { running = it }
    }

    // --- the sync ---

    private suspend fun syncNow() {
        lastSyncAt = clock()
        // Changes saved before a restart, or a file with older playlist keys (read here too: start-up may come first).
        if (account.state.first().pending) dirty.set(true)
        val s = store(::token)
        val own = ownName()
        val playlists = playlistKeys()
        // A playlist, or a type of category, added since: the other TVs' files may hold its progress or choices.
        // A playlist added since: the other TVs' files may hold its progress and its choices.
        if (!lastPlaylists.containsAll(playlists.values)) forgetSeen()
        lastPlaylists = playlists.values.toSet()
        // Before the merge, so that this TV's older choices count against the other TVs'.
        choices().seed()
        val files = s.list()
        val changed = files.filter { ProgressFile.isProgressFile(it.name) && it.name != own && seen[it.id] != it.modifiedTime }
        // A failed download fails the sync. A file this version cannot read is left out until it changes.
        val read = changed.map { f -> s.download(f.id).let { quietly { ProgressFile.decode(it) } } }
        val readable = read.filterNotNull()
        if (read.isNotEmpty() && merge(ProgressFile.newest(readable), playlists)) markChanged()
        val choices = ProgressFile.newestChoices(readable).toMutableMap()
        waiting.forEach { (key, c) -> if ((choices[key]?.at ?: Long.MIN_VALUE) < c.at) choices[key] = c }
        mergeChoices(choices, playlists).forEach { (pid, reload) -> onChoices(pid, reload) }
        changed.forEach { seen[it.id] = it.modifiedTime }
        val ownFile = files.firstOrNull { it.name == own }
        if (dirty.getAndSet(false) || ownFile == null) {
            try {
                val bytes = localFile(playlists).encode()
                if (ownFile == null) s.create(own, bytes) else s.update(ownFile.id, bytes)
            } catch (e: SyncException) {
                dirty.set(true)
                throw e
            }
        }
        account.synced(clock(), pending = dirty.get())
    }

    private suspend fun ownName(): String = ownName ?: ProgressFile.fileName(account.device()).also { ownName = it }

    private suspend fun localFile(keys: Map<Long, String>): ProgressFile {
        val keep = clock() - TimeUnit.DAYS.toMillis(SyncTombstoneEntity.KEEP_DAYS)
        vod.pruneTombstones(keep)
        db.choices().pruneNone(keep)
        return ProgressFile(
            rows = db.backup().progress().mapNotNull { r -> keys[r.playlistId]?.let { r.toSynced(it) } },
            deleted = vod.tombstones().mapNotNull { t -> keys[t.playlistId]?.let { SyncedDelete(it, t.kind, t.itemId, t.deletedAt) } },
            choices = choices().local().mapNotNull { c -> keys[c.playlistId]?.let { SyncedChoice(it, c.kind, c.itemId, c.value, c.at) } }
        )
    }

    private suspend fun playlistKeys(): Map<Long, String> =
        db.playlists().all().associate { it.id to ProgressFile.playlistKey(it.kind, it.server, it.name) }

    /**
     * Takes each change that is newer than what this TV has. Returns whether something this TV had was deleted: its
     * own file must then lose it too.
     */
    private suspend fun merge(newest: Map<SyncKey, ProgressFile.Change>, keys: Map<Long, String>): Boolean = db.withTransaction {
        val playlists = keys.entries.groupBy({ it.value }, { it.key })
        val rows = db.backup().progress().associateBy { Triple(it.playlistId, it.kind, it.itemId) }
        val forgotten = vod.tombstones().associateBy({ Triple(it.playlistId, it.kind, it.itemId) }, { it.deletedAt })
        val touched = HashSet<Long>()
        var deleted = false
        newest.forEach { (key, change) ->
            playlists[key.playlist].orEmpty().forEach item@{ pid ->
                if (change is ProgressFile.Deleted && key.kind == CAT_SERIES) {
                    if (vod.deleteSeriesProgressBefore(pid, key.id, change.at) > 0) deleted = true
                    return@item
                }
                val id = Triple(pid, key.kind, key.id)
                val local = rows[id]
                val series = (change as? ProgressFile.Saved)?.row?.seriesId?.let { forgotten[Triple(pid, CAT_SERIES, it)] }
                val since = maxOf(local?.updatedAt ?: Long.MIN_VALUE, forgotten[id] ?: Long.MIN_VALUE, series ?: Long.MIN_VALUE)
                if (change.at <= since || (change is ProgressFile.Deleted && local == null)) return@item
                when (change) {
                    is ProgressFile.Saved -> vod.saveProgress(change.row.toEntity(pid))
                    is ProgressFile.Deleted -> {
                        vod.deleteProgress(pid, key.kind, key.id)
                        deleted = true
                    }
                }
                touched += pid
            }
        }
        // The same rules as this TV's own saves.
        touched.forEach { db.keepProgressRules(it) }
        deleted
    }

    /**
     * Takes each choice that is newer than this TV's. A choice for an item that this TV does not have yet waits in
     * [waiting]. Returns what must download again, for each playlist.
     */
    private suspend fun mergeChoices(newest: Map<ChoiceKey, SyncedChoice>, keys: Map<Long, String>): Map<Long, Reload> {
        waiting.clear()
        if (newest.isEmpty()) return emptyMap()
        val target = choices()
        val pids = keys.entries.groupBy({ it.value }, { it.key })
        val reload = HashMap<Long, Reload>()
        // Guides before the guide channels chosen in them.
        val groups = newest.values.groupBy { it.playlist to it.kind }.entries.sortedBy { it.key.second != ChoiceKinds.GUIDE }
        groups.forEach { (playlistKind, choices) ->
            val (key, kind) = playlistKind
            pids[key].orEmpty().forEach { pid ->
                val local = choices.map { it.id }.chunked(MAX_SQL_ARGS).flatMap { db.choices().get(pid, kind, it) }
                    .associate { it.itemId to it.at }
                val newer = choices.filter { it.at > (local[it.id] ?: Long.MIN_VALUE) }
                if (newer.isEmpty()) return@forEach
                val r = reload.getOrPut(pid, ::Reload)
                // Not through the log: each keeps the other TV's time.
                val taken = withContext(ChoiceLog.Applying) { applyGroup(kind) { target.apply(pid, kind, newer, r) } }
                db.choices().upsert(taken.map { SyncChoiceEntity(pid, kind, it.id, it.value, it.at) })
                (newer - taken.toSet()).forEach { waiting[it.key] = it }
            }
        }
        return reload.filterValues { it.any }
    }

    /**
     * One transaction for a group, so that many choices (all categories ticked, for example) write at once. Not for
     * guides: matching a channel to a guide again reads the database from other threads.
     */
    private suspend fun <T> applyGroup(kind: String, block: suspend () -> T): T =
        if (kind == ChoiceKinds.GUIDE || kind == ChoiceKinds.GUIDE_CHANNEL) block() else db.withTransaction { block() }

    private fun forgetSeen() {
        seen.clear()
        waiting.clear()
    }

    private inline fun <T> quietly(block: () -> T): T? = try {
        block()
    } catch (_: SyncException) {
        null
    }

    private companion object {
        /** A sync this recent is new enough, when this TV has no changes. */
        const val FRESH_MS = 30_000L

        /** An access token this close to its end is renewed first. */
        const val EARLY_MS = 60_000L
    }
}

private fun WatchProgressEntity.toSynced(playlist: String) = SyncedProgress(
    playlist, kind, itemId, positionMs, durationMs, updatedAt, title, imageUrl, seriesId, season, episode, episodeTitle, hasNext
)

private fun SyncedProgress.toEntity(playlistId: Long) = WatchProgressEntity(
    playlistId, kind, id, position, duration, updatedAt, title, image, seriesId, season, episode, episodeTitle, hasNext
)
