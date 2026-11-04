package com.biskyt.mytvplayer.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.withTransaction
import com.biskyt.mytvplayer.core.sync.DeviceCode
import com.biskyt.mytvplayer.core.sync.GoogleAuth
import com.biskyt.mytvplayer.core.sync.GoogleTokens
import com.biskyt.mytvplayer.core.sync.ProgressFile
import com.biskyt.mytvplayer.core.sync.SyncException
import com.biskyt.mytvplayer.core.sync.SyncKey
import com.biskyt.mytvplayer.core.sync.SyncStore
import com.biskyt.mytvplayer.core.sync.SyncedDelete
import com.biskyt.mytvplayer.core.sync.SyncedProgress
import com.biskyt.mytvplayer.data.db.AppDatabase
import com.biskyt.mytvplayer.data.db.CAT_SERIES
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
 * Keeps the place in films and episodes the same on all the user's TVs, through the app's own folder (MyTVPlayer) in
 * their Google Drive. It is off until the user signs in (Settings › Sync between TVs).
 *
 * - Each TV writes only its own file: all its progress and what it forgot (see [ProgressFile]).
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
        seen.clear()
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
        seen.clear()
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
        // A playlist added since: the other TVs' files may hold its progress.
        if (!lastPlaylists.containsAll(playlists.values)) seen.clear()
        lastPlaylists = playlists.values.toSet()
        val files = s.list()
        val changed = files.filter { ProgressFile.isProgressFile(it.name) && it.name != own && seen[it.id] != it.modifiedTime }
        // A failed download fails the sync. A file this version cannot read is left out until it changes.
        val read = changed.map { f -> s.download(f.id).let { quietly { ProgressFile.decode(it) } } }
        if (read.isNotEmpty() && merge(ProgressFile.newest(read.filterNotNull()), playlists)) markChanged()
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
        vod.pruneTombstones(clock() - TimeUnit.DAYS.toMillis(SyncTombstoneEntity.KEEP_DAYS))
        return ProgressFile(
            rows = db.backup().progress().mapNotNull { r -> keys[r.playlistId]?.let { r.toSynced(it) } },
            deleted = vod.tombstones().mapNotNull { t -> keys[t.playlistId]?.let { SyncedDelete(it, t.kind, t.itemId, t.deletedAt) } }
        )
    }

    private suspend fun playlistKeys(): Map<Long, String> =
        db.playlists().all().associate { it.id to ProgressFile.playlistKey(it.kind, it.server, it.username) }

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
