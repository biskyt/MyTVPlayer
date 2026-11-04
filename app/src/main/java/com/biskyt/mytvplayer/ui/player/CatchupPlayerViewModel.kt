package com.biskyt.mytvplayer.ui.player

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.biskyt.mytvplayer.AppContainer
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.appString
import com.biskyt.mytvplayer.core.m3u.M3uCatchup
import com.biskyt.mytvplayer.core.player.OverlayState
import com.biskyt.mytvplayer.core.xtream.CatchupListing
import com.biskyt.mytvplayer.core.xtream.CatchupProgramme
import com.biskyt.mytvplayer.data.CatchupRepository
import com.biskyt.mytvplayer.data.db.ChannelEntity
import com.biskyt.mytvplayer.data.db.PlaylistEntity
import com.biskyt.mytvplayer.data.db.proxy
import com.biskyt.mytvplayer.ui.Screen
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * A catch-up channel as it was from a moment in the past: like watching the channel live, only earlier. The stream
 * runs on from one programme into the next up to the present (then it switches to live). Skipping reopens the
 * stream at the new moment, as timeshift streams often can't seek.
 */
class CatchupPlayerViewModel(
    c: AppContainer,
    appContext: Context,
    private val screen: Screen.CatchupPlayer,
    private val clock: () -> Long = System::currentTimeMillis
) : PlaybackViewModel(c, appContext) {
    var channel by mutableStateOf<ChannelEntity?>(null)
        private set

    /** The programme being watched (moves on as playback crosses into the next one). */
    var programme by mutableStateOf<CatchupProgramme?>(null)
        private set

    /** The moment on screen, updated while the controls are up. */
    var at by mutableLongStateOf(screen.startMs ?: clock())
        private set

    /** Set when catch-up has reached the present: the screen switches to the live channel. */
    var goLive by mutableStateOf(false)
        private set

    override val isLive = false
    override val failureMessage = R.string.player_failure_catchup

    private var playlist: PlaylistEntity? = null
    private var listing: CatchupListing? = null

    /** The moment the current stream starts at; the player's position counts from there. */
    private var openedAt = 0L
    private var tickJob: Job? = null

    init {
        viewModelScope.launch {
            startEngine()
            val p = c.playlists.get(screen.playlistId) ?: return@launch stop()
            val ch = c.channels.get(screen.playlistId, screen.streamId) ?: return@launch stop()
            playlist = p
            channel = ch
            try {
                val l = c.catchup.listing(p, ch).let { if (p.isM3u) withGuide(p, ch, it) else it }
                listing = l
                val start = screen.startMs ?: c.catchup.onNow(l)?.startMs
                if (start == null) fail(appString(R.string.player_catchup_nothing_to_start_over)) else open(start)
                startTicking()
            } catch (e: java.io.IOException) {
                fail(e.message)
            }
        }
    }

    /**
     * M3U catch-up has only hour-long slots; the TV guide's programmes, where it has them, take their place, so the
     * title, "Start over" and "from the start" follow the real programmes.
     */
    private suspend fun withGuide(p: PlaylistEntity, ch: ChannelEntity, l: CatchupListing): CatchupListing {
        val now = clock()
        val oldest = now - ch.archiveDays * CatchupRepository.DAY_MS
        val guide = c.epg.programmes(p.id, listOf(ch.streamId), oldest, now, details = false)[ch.streamId].orEmpty()
            .filter { it.startMs >= oldest }
            .map { CatchupProgramme(it.startMs, it.stopMs, it.title, it.description, hasArchive = true) }
        return M3uCatchup.withProgrammes(l, guide)
    }

    /** Where playback is now. */
    private fun position(): Long = openedAt + (engine?.player?.currentPosition ?: 0)

    private fun open(startMs: Long) {
        val p = playlist ?: return
        val ch = channel ?: return
        val l = listing ?: return
        if (startMs >= clock() - LIVE_MARGIN_MS) watchLive() else play(p, ch, l, startMs)
    }

    private fun play(p: PlaylistEntity, ch: ChannelEntity, l: CatchupListing, startMs: Long) {
        openedAt = startMs
        at = startMs
        programme = programmeAt(startMs)
        resetForNewStream()
        // An M3U channel whose catch-up address can't be made: say so, as for any stream that won't play.
        val url = try {
            c.catchup.url(p, ch, l, startMs)
        } catch (e: java.io.IOException) {
            return fail(e.message)
        }
        // Xtream archives are MPEG-TS; M3U ones may be either (told by the address when it can), and may need the
        // channel's headers.
        val hls = if (p.isM3u) M3uCatchup.isHls(url, ch.streamUrl.orEmpty(), ch.catchupType.orEmpty()) else false
        engine?.play(url, hls = hls, userAgent = ch.userAgent, referrer = ch.referrer, proxy = p.proxy)
        showControls()
    }

    /** The programme on at [t]. */
    fun programmeAt(t: Long) = listing?.programmes?.lastOrNull { it.startMs <= t }

    /** Keeps the title and timeline on the programme being watched. */
    private fun startTicking() {
        tickJob?.cancel()
        tickJob = viewModelScope.launch {
            while (isActive) {
                val t = position()
                val current = programme
                if (current == null || t >= current.stopMs || t < current.startMs) programme = programmeAt(t)
                if (overlay == OverlayState.CONTROLS) at = t
                delay(TICK_MS)
            }
        }
    }

    /** Skipping moves between the channel's oldest catch-up (or where it is, if older) and now (where it goes live). */
    override fun scrubBounds(): Pair<LongRange, Long>? {
        val ch = channel ?: return null
        if (listing == null || openedAt == 0L) return null // nothing playing yet
        val now = clock()
        val position = position()
        return minOf(now - ch.archiveDays * CatchupRepository.DAY_MS, position)..now to position
    }

    /** Reopens the stream at the new moment. */
    override fun playFrom(ms: Long) = open(ms)

    fun fromStart() {
        programme?.let { open(it.startMs) }
    }

    fun watchLive() {
        engine?.player?.stop()
        goLive = true
    }

    /**
     * The stream ran out: carry on from where it stopped (some providers cap how long one runs), or go live. A stream
     * that ends almost at once won't do better if asked again.
     */
    override fun onEnded() {
        if ((engine?.player?.currentPosition ?: 0) < MIN_PLAYED_MS) fail() else open(position())
    }

    // Coming back to the app, or trying again after an error, carries on from where playback was (the same address
    // would start again from where it first opened).
    private var resumeAt: Long? = null

    override fun onAppHidden() {
        // Not before a stream has opened (its position would count from 1970): coming back then plays what opens.
        resumeAt = position().takeIf { openedAt != 0L }
        super.onAppHidden()
    }

    override fun resume() {
        val t = resumeAt ?: return super.resume()
        resumeAt = null
        if (!exit) open(t)
    }

    override fun retry() = open(position())

    override fun stop() {
        tickJob?.cancel()
        super.stop()
    }

    private companion object {
        const val TICK_MS = 1_000L

        const val MIN_PLAYED_MS = 10_000L

        /** Closer than this to now is live. */
        const val LIVE_MARGIN_MS = 2 * 60_000L
    }
}
