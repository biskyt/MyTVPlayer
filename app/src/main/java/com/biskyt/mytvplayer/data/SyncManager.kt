package com.biskyt.mytvplayer.data

import androidx.annotation.StringRes
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.appString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What a background refresh is doing, for the small status line in the UI. */
sealed interface SyncState {
    data object Idle : SyncState

    /** [playlist]'s channels, [loaded] so far. */
    data class Channels(val playlist: String, val loaded: Int) : SyncState

    /** [playlist]'s movies and series, [loaded] so far. */
    data class Vod(val playlist: String, val loaded: Int) : SyncState
    data class Guide(val step: GuideStep) : SyncState

    /** [message] is worked out when shown, so it's in the language shown then. */
    data class Failed(val error: Throwable, @StringRes val fallback: Int) : SyncState {
        val message: String get() = userMessage(error) ?: appString(fallback)
    }
}

/** A step of a guide update, put in words when shown (so in the language shown then); [fraction]: how far along, if known. */
sealed interface GuideStep {
    val fraction: Float?

    data class Downloading(val guide: String, override val fraction: Float? = null) : GuideStep

    data class Reading(val guide: String) : GuideStep {
        override val fraction: Float? get() = null
    }

    /** Matching channels to the guide: [done] of [total] (0 when not counted yet). */
    data class Matching(val done: Int = 0, val total: Int = 0) : GuideStep {
        override val fraction get() = if (total > 0) done.toFloat() / total else null
    }

    /** Loading programmes from [guide] (null: from the guide, unnamed). */
    data class Loading(val guide: String?, override val fraction: Float? = null) : GuideStep
}

/**
 * Runs channel and guide refreshes one at a time in the app's scope and reports progress.
 * Used both from the UI (e.g. after adding a playlist) and the daily background worker. The guide is updated for the
 * playlist in use: switching to another updates its guides that are out of date.
 */
class SyncManager(
    private val scope: CoroutineScope,
    private val playlists: PlaylistRepository,
    private val vod: VodRepository,
    private val epg: EpgRepository,
    private val settings: SettingsRepository
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    val state: StateFlow<SyncState> = _state.asStateFlow()

    init {
        // Not the playlist in use at start: only a switch to another (from any screen), which stops the old one's work.
        // collectLatest: a further switch also stops this one's guide update.
        scope.launch {
            settings.activePlaylistId.drop(1).collectLatest { id ->
                running?.takeIf { it.first != id }?.second?.cancel()
                updateOutOfDateGuides(id)
            }
        }
    }

    /** Refreshes a playlist's channels, then the guide, then movies and series (the slowest, so last). */
    fun refresh(playlistId: Long): Job = scope.launch { refreshNow(playlistId) }

    fun refreshAll(): Job = scope.launch { refreshAllNow() }

    /**
     * The playlist in use: its channels, movies and series, then its guides that are out of date (as they are a day on;
     * one just loaded, e.g. by switching to the playlist, isn't loaded again). Playlists not in use are left alone.
     */
    suspend fun refreshAllNow() {
        inUse().forEach {
            refreshNow(it, includeGuide = false)
            updateOutOfDateGuides(it)
        }
    }

    /**
     * After switching to [playlistId] on the Playlists screen: updates its channels, movies and series (its guides, and
     * stopping the old one's work, follow the setting: see init).
     */
    fun switchTo(playlistId: Long): Job = scope.launch { refreshNow(playlistId, includeGuide = false) }

    /** Looks for guides for channels without one. A failure also shows in [state]. */
    suspend fun matchMissingGuides(): Result<MatchResult> =
        attempt(R.string.common_match_failed) { epg.matchMissing(inUse()) { _state.value = SyncState.Guide(it) } }

    /** Runs [block] while no refresh is running, e.g. replacing everything from a backup. */
    suspend fun <T> whileIdle(block: suspend () -> T): T = mutex.withLock { block() }

    /**
     * [refresh], waiting for it: the outcome is returned (with the error of each guide that couldn't be loaded, by source
     * id, as [updateGuide]), and also shows in [state].
     */
    suspend fun refreshNow(playlistId: Long, includeGuide: Boolean = true): Result<Map<Long, String>> =
        attempt(R.string.data_refresh_failed) {
            ifInUse(playlistId) { refreshPlaylist(playlistId, includeGuide) }.orEmpty()
        }

    private suspend fun refreshPlaylist(playlistId: Long, includeGuide: Boolean): Map<Long, String> {
        val name = playlists.get(playlistId)?.name.orEmpty()
        _state.value = SyncState.Channels(name, 0)
        playlists.syncChannels(playlistId) { _state.value = SyncState.Channels(name, it) }
        val guideErrors = if (includeGuide) importGuide(listOf(playlistId)) else emptyMap()
        // Read again (by syncVod): updating the channels stores to the playlist (its account, M3U state).
        syncVod(playlistId)
        return guideErrors
    }

    /**
     * Updates the guide of the playlist in use now, with this update's own outcome (another refresh may change [state]
     * straight after): the error of each guide that couldn't be loaded, by source id. With [only], just that guide is
     * downloaded (e.g. one just added); the others are used as last loaded.
     */
    suspend fun updateGuide(only: Long? = null): Result<Map<Long, String>> = attempt(R.string.common_guide_update_failed) {
        inUse().firstOrNull()?.let { id -> ifInUse(id) { importGuide(listOf(id), only) } }.orEmpty()
    }

    /** Updates those of [playlistId]'s guides that are out of date, if any (as [updateGuide]); none if it's not in use. */
    suspend fun updateOutOfDateGuides(playlistId: Long): Result<Map<Long, String>> = attempt(R.string.common_guide_update_failed) {
        ifInUse(playlistId) { importGuide(listOf(playlistId), outOfDate = true) }.orEmpty()
    }

    /** Only movies and series (after their categories change). */
    fun refreshVod(playlistId: Long): Job = scope.launch {
        guarded(R.string.data_vod_failed) { ifInUse(playlistId) { syncVod(playlistId) } }
    }

    /** The playlist whose work is running now, and that work: a switch to another playlist stops it. */
    @Volatile private var running: Pair<Long, Job>? = null

    /**
     * [block], the work for [playlistId], when it's the playlist in use (null, without running it, for one that isn't:
     * those are left alone). A switch to another playlist stops it (see init), and the caller then gets null: the work
     * runs in a job of its own, so the caller (e.g. the daily refresh's housekeeping after it) carries on.
     */
    private suspend fun <T> ifInUse(playlistId: Long, block: suspend () -> T): T? {
        if (settings.current().activePlaylistId != playlistId) return null
        val job = scope.async { block() }
        val work = playlistId to job
        running = work
        try {
            return job.await()
        } catch (e: CancellationException) {
            if (job.isCancelled && currentCoroutineContext().isActive) return null // stopped by a switch
            throw e
        } finally {
            job.cancel() // the caller stopped waiting: the work stops too (a no-op once it has finished)
            if (running === work) running = null
        }
    }

    private suspend fun syncVod(playlistId: Long) {
        val p = playlists.get(playlistId) ?: return
        // An M3U playlist without movie or series playlists still syncs: to clear any it had before.
        if (p.hasVod) _state.value = SyncState.Vod(p.name, 0)
        vod.sync(p) { _state.value = SyncState.Vod(p.name, it) }
    }

    private suspend fun importGuide(playlistIds: List<Long>, only: Long? = null, outOfDate: Boolean = false) =
        epg.importAll(playlistIds, only, outOfDate) { _state.value = SyncState.Guide(it) }

    /** The playlist in use, if there's one. */
    private suspend fun inUse(): List<Long> = listOfNotNull(settings.current().activePlaylistId.takeIf { it != 0L })

    /** [attempt] when only the state needs to show the outcome. */
    private suspend fun <T> guarded(@StringRes failure: Int, block: suspend () -> T): T? = attempt(failure, block).getOrNull()

    /**
     * Runs one refresh at a time; any failure (network, bad data, full storage) is shown in [state] and returned
     * (with its message for the user), never allowed to crash the app.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> attempt(@StringRes failure: Int, block: suspend () -> T): Result<T> = mutex.withLock {
        try {
            Result.success(block().also { _state.value = SyncState.Idle })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val failed = SyncState.Failed(e, failure)
            _state.value = failed
            Result.failure(Exception(failed.message, e))
        }
    }
}
