package com.biskyt.mytvplayer.ui.player

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import com.biskyt.mytvplayer.AppContainer
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.appString
import com.biskyt.mytvplayer.core.player.KeyActionMapper
import com.biskyt.mytvplayer.core.player.OverlayState
import com.biskyt.mytvplayer.core.player.PlayerAction
import com.biskyt.mytvplayer.core.player.RemoteKey
import com.biskyt.mytvplayer.core.player.scrubTarget
import com.biskyt.mytvplayer.data.Settings
import com.biskyt.mytvplayer.data.db.ChannelProgramme
import com.biskyt.mytvplayer.player.PlaybackEngine
import com.biskyt.mytvplayer.player.PlaybackError
import com.biskyt.mytvplayer.player.failureReason
import com.biskyt.mytvplayer.ui.common.ItemAction
import com.biskyt.mytvplayer.ui.common.formatDuration
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What every player screen shares, live TV or on demand: the engine, the overlay and side sheets, remote keys,
 * skipping, retries, the sleep timer and stopping when the app goes to the background.
 */
abstract class PlaybackViewModel(protected val c: AppContainer, private val appContext: Context) : ViewModel() {
    var engine by mutableStateOf<PlaybackEngine?>(null)
        private set
    var overlay by mutableStateOf(OverlayState.CONTROLS)
        protected set
    var isPlaying by mutableStateOf(true)
        private set
    var buffering by mutableStateOf(true)
        private set
    var error by mutableStateOf<PlaybackError?>(null)
        private set

    /** Stops on an error: [message] says what happened and what to do, [reason] why (when known). */
    /** Shows the error [message] ([failureMessage] when there's none to tell), and why: [reason]. */
    protected fun fail(message: String? = null, reason: String? = null) {
        error = PlaybackError(message ?: appString(failureMessage), reason)
        wake() // an error isn't left under the dimming
    }
    var message by mutableStateOf<String?>(null)
        private set
    var tracks by mutableStateOf(Tracks.EMPTY)
        private set
    var audioLabel by mutableStateOf("")
        private set
    var picture by mutableStateOf(PictureMode.FIT)
    var sleepMinutes by mutableStateOf(0)
        private set

    /** Which side sheet is open over the video (null = none). */
    var sheet by mutableStateOf<Sheet?>(null)

    /** The programme the About sheet tells of (live TV's, on now). */
    open val about: ChannelProgramme? get() = null

    /** Set when playback should end and the screen close. */
    var exit by mutableStateOf(false)
        private set

    enum class Sheet { AUDIO, SUBTITLES, MORE, ABOUT }

    /**
     * What can be done with what's playing, at the top of the More menu; read while drawing it, so labels follow state
     * (e.g. "Remove from favourites"). A screen opened from it stops playback until Back, like leaving the app.
     */
    open fun menuActions(): List<ItemAction> = emptyList()

    /** Before an action replaces the player with another screen (e.g. saves how far a movie got). */
    open fun beforeLeaving() = Unit

    /** True once the screen has gone for good and the player was released. */
    var released = false
        private set

    protected lateinit var settings: Settings
    private lateinit var mapper: KeyActionMapper
    private var hideJob: Job? = null
    private var dimJob: Job? = null
    private var retryJob: Job? = null
    private var messageJob: Job? = null
    private var sleepJob: Job? = null
    private val retries = RetryBudget(MAX_RETRIES)

    // How long the stream has played since its last failure: once it has really played a while, failures get fresh
    // retries; one that starts and fails again straight away would otherwise retry for ever ("Loading…", blank…).
    private var playedMs = 0L
    private var playingSince = 0L

    /** Whether the stream has played at all yet. */
    private var hasPlayed = false

    /** Live TV zaps with up/down and has a guide; on-demand video doesn't. */
    protected abstract val isLive: Boolean

    /** Shown when a stream keeps failing. */
    @get:StringRes protected abstract val failureMessage: Int

    private val listener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // Paused, the controls stay up; playing again, they go after the usual while.
            if (!playWhenReady || overlay == OverlayState.CONTROLS) showControls(keepOverlay = true)
            wake()
        }

        override fun onIsPlayingChanged(playing: Boolean) {
            isPlaying = playing
            val now = System.currentTimeMillis()
            if (playing) {
                hasPlayed = true
                playingSince = now
            } else if (playingSince > 0) {
                playedMs += now - playingSince
                playingSince = 0
            }
            onPlayingChanged(playing)
        }

        override fun onPlaybackStateChanged(state: Int) {
            // Something started a stream while the app is in the background (a lookup finishing, a retry): stop it.
            // Coming back prepares it again (see onAppShown).
            if (hidden && (state == Player.STATE_BUFFERING || state == Player.STATE_READY)) {
                engine?.player?.stop()
                return
            }
            buffering = state == Player.STATE_BUFFERING
            if (state == Player.STATE_READY) error = null
            if (state == Player.STATE_ENDED) onEnded()
            // Paused again after a retry or a stop (which ended the countdown): the dimming counts from now. A
            // countdown already running carries on, so a stream that keeps changing state still dims.
            if (!dimmed && dimJob?.isActive != true) stayLit()
        }

        override fun onTracksChanged(t: Tracks) {
            tracks = t
            engine?.applyDefaultSubtitles(t)
            audioLabel = engine?.audioOutputLabel(t).orEmpty()
        }

        override fun onPlayerError(e: PlaybackException) {
            val player = engine?.player ?: return
            // A stream that failed again after a retry, before it ever played, may play another way.
            if (engine?.retryAsHls(e) == true || (!hasPlayed && retries.used > 0 && tryOtherFormat())) return
            // Streams drop out now and then; retry a few times before showing an error.
            if (playedSinceFailure() >= STEADY_MS) retries.reset()
            playedMs = 0
            playingSince = 0
            if (!retries.take()) {
                fail(reason = failureReason(e, engine?.currentUrl))
                return
            }
            if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                player.seekToDefaultPosition()
                player.prepare()
                return
            }
            retryJob?.cancel()
            retryJob = viewModelScope.launch {
                delay(1_000L * retries.used)
                player.prepare()
            }
        }
    }

    /** The sound's real format arrives once its output starts (after the tracks): the label follows it. */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private val audioFormatListener = object : androidx.media3.exoplayer.analytics.AnalyticsListener {
        override fun onAudioInputFormatChanged(
            eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
            format: androidx.media3.common.Format,
            decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?
        ) {
            audioLabel = engine?.audioOutputLabel(playing = format).orEmpty()
        }
    }

    /** Reads the settings and creates the engine; subclasses call this first when they start. */
    protected suspend fun startEngine(): PlaybackEngine {
        settings = c.settings.current()
        mapper = KeyActionMapper(settings.skipBackSec * 1_000L, settings.skipForwardSec * 1_000L, settings.zapWithUpDown)
        return PlaybackEngine(appContext, settings).also {
            it.player.addListener(listener)
            it.player.addAnalyticsListener(audioFormatListener)
            it.onStalled = listener::onPlayerError
            it.onUnsupported = { url -> fail(appString(R.string.player_unsupported_format), failureReason(null, url)) }
            engine = it
        }
    }

    /** Forgets the previous stream's failures before playing another. */
    protected fun resetForNewStream() {
        cancelScrub()
        retryJob?.cancel()
        error = null
        retries.reset()
        playedMs = 0
        playingSince = 0
        hasPlayed = false
    }

    private fun playedSinceFailure() = playedMs + if (playingSince > 0) System.currentTimeMillis() - playingSince else 0

    protected open fun onPlayingChanged(playing: Boolean) = Unit

    /** A stream that keeps failing before it ever played may be tried another way (live TV: the other format); true if so. */
    protected open fun tryOtherFormat(): Boolean = false

    protected open fun onEnded() = Unit

    // Live TV overrides these; on demand they do nothing.
    protected open fun zap(step: Int) = Unit

    protected open fun typeDigit(digit: Int) = Unit

    open fun openGuide() = Unit

    open fun closeGuide() {
        overlay = OverlayState.NONE
    }

    /** A remote key; [repeat] counts the repeats while it's held. */
    fun onKey(key: RemoteKey, digit: Int = 0, repeat: Int = 0): Boolean {
        if (!::mapper.isInitialized) return false
        // A held OK, Menu or Guide does it once (its repeats would toggle the controls, the pause, a menu or the guide);
        // one the player doesn't act on is the TV's, repeats and all.
        if (repeat > 0 && (key == RemoteKey.OK || (key == RemoteKey.MENU || key == RemoteKey.GUIDE) && actsOn(key))) return true
        val action = actionFor(key, digit)
        if (overlay != OverlayState.NONE) showControls(keepOverlay = true)
        return perform(action, repeat)
    }

    private fun perform(action: PlayerAction, repeat: Int = 0): Boolean {
        val player = engine?.player
        when (action) {
            is PlayerAction.SeekBy -> {
                // From the media buttons with a panel open: it closes, so the play bar shows where playback goes.
                sheet = null
                if (overlay == OverlayState.GUIDE) closeGuide()
                scrub(action.ms, repeat)
            }
            is PlayerAction.Zap -> zap(action.step)
            is PlayerAction.Digit -> typeDigit(action.value)
            PlayerAction.ToggleOverlay -> if (overlay == OverlayState.NONE) showControls() else hideOverlay()
            PlayerAction.PauseAndShowControls -> {
                player?.pause()
                showControls()
            }
            PlayerAction.HideOverlay -> hideOverlay()
            PlayerAction.TogglePlayPause -> player?.let { if (it.isPlaying) it.pause() else it.play() }
            PlayerAction.Play -> player?.play()
            PlayerAction.Pause -> player?.pause()
            PlayerAction.StopAndExit -> stop()
            PlayerAction.OpenGuide -> {
                // From the Guide button with a panel open: the guide takes its place.
                sheet = null
                openGuide()
            }
            PlayerAction.CloseGuide -> closeGuide()
            PlayerAction.OpenMenu -> {
                // From the Menu button with the channel guide open: the menu takes its place.
                if (overlay == OverlayState.GUIDE) closeGuide()
                sheet = Sheet.MORE
            }
            PlayerAction.CloseMenu -> sheet = null
            PlayerAction.None -> return false
        }
        return true
    }

    /** Whether the player acts on [key] now (else it's left to the TV: Guide on a film opens the TV's own guide). */
    fun actsOn(key: RemoteKey): Boolean = ::mapper.isInitialized && actionFor(key) != PlayerAction.None

    private fun actionFor(key: RemoteKey, digit: Int = 0) = mapper.map(key, overlay, isLive, digit, menuOpen = sheet == Sheet.MORE)

    /** Back closes the topmost overlay, otherwise stops playback (also safe while the player is still starting). */
    fun back() {
        if (!::mapper.isInitialized) stop() else perform(mapper.map(RemoteKey.BACK, overlay, isLive))
    }

    /** Whether the app is in the background, where nothing may stream. */
    private var hidden = false

    /** Called when the app goes to the background / comes back: nothing is left streaming in the background. */
    open fun onAppHidden() {
        hidden = true
        cancelScrub()
        engine?.player?.stop()
    }

    fun onAppShown() {
        hidden = false
        resume()
    }

    /** Back in the app: plays again what was stopped. */
    protected open fun resume() {
        engine?.player?.let {
            // Nothing in it yet (what to play is still loading, as the screen's first "shown" comes straight away):
            // prepared empty, it would end at once and close the player.
            if (it.playbackState == Player.STATE_IDLE && it.mediaItemCount > 0 && !exit) {
                it.prepare()
                it.play()
            }
        }
    }

    open fun stop() {
        cancelScrub()
        engine?.player?.stop()
        exit = true
    }

    fun togglePlayPause() = perform(PlayerAction.TogglePlayPause)

    /** While skipping with ◀ ▶: where playback goes once the button rests (shown on the play bar), else null. */
    var scrubMs by mutableStateOf<Long?>(null)
        private set
    private var scrubJob: Job? = null

    /**
     * The span skipping moves within, and where playback is now; null where it can't skip (e.g. most live TV).
     * On demand: the whole video.
     */
    protected open fun scrubBounds(): Pair<LongRange, Long>? {
        val p = engine?.player ?: return null
        if (!p.isCurrentMediaItemSeekable) return null
        // Short of the very end, which would end it (and close a film); unknown length: unbounded.
        val end = p.duration.takeIf { it > 0 }?.let { (it - END_MARGIN_MS).coerceAtLeast(0) } ?: Long.MAX_VALUE
        return 0L..end to p.currentPosition
    }

    /** Plays from [ms] (a point within [scrubBounds]). */
    protected open fun playFrom(ms: Long) {
        engine?.player?.seekTo(ms)
    }

    /**
     * ◀ ▶: moves the point to play from by [ms] (faster while held, [repeat]), shown on the play bar; playback only
     * jumps there once the button rests, so scrubbing through a film doesn't reload the stream at every step.
     */
    private fun scrub(ms: Long, repeat: Int) {
        val (range, now) = scrubBounds() ?: return flash(appString(R.string.player_skipping_unavailable))
        if (overlay == OverlayState.NONE) showControls(forSkip = true)
        moveScrub(scrubTarget(scrubMs ?: now, ms, repeat, range), now)
    }

    /** Touch: tapping or dragging along the play bar moves the point to play from to [fraction] of [span]. */
    fun scrubTo(span: LongRange, fraction: Float) {
        // Not seekable: said once, not at every step of a drag.
        val (range, now) = scrubBounds() ?: return if (message == null) flash(appString(R.string.player_skipping_unavailable)) else Unit
        showControls(keepOverlay = true)
        val target = span.first + ((span.last - span.first) * fraction.coerceIn(0f, 1f)).toLong()
        moveScrub(target.coerceIn(range), now)
    }

    private fun moveScrub(target: Long, now: Long) {
        scrubMs = target
        val offset = target - now
        flash((if (offset < 0) "−" else "+") + formatDuration(kotlin.math.abs(offset)))
        scrubJob?.cancel()
        scrubJob = viewModelScope.launch {
            delay(SCRUB_SETTLE_MS)
            scrubMs = null
            playFrom(target)
        }
    }

    /** Forgets a scrub not yet played from (another stream, starting over, leaving). */
    protected fun cancelScrub() {
        scrubJob?.cancel()
        scrubMs = null
    }

    /** Whether up and down change channel (live TV, if set to; else only the channel buttons do). */
    val upDownZaps: Boolean get() = isLive && ::settings.isInitialized && settings.zapWithUpDown

    /** Whether the controls showing put focus on play/pause; not when they came up for skipping (◀ ▶ keep skipping). */
    var focusPlayPause by mutableStateOf(true) // the controls are up as playback starts
        private set

    /**
     * Whether a stream is paused (not just waiting for data), with the app in view and no error showing: the controls
     * stay up meanwhile.
     */
    private val paused: Boolean
        get() {
            val player = engine?.player ?: return false
            return !player.playWhenReady && player.playbackState != Player.STATE_IDLE && error == null && !hidden
        }

    /** Paused and left alone a while: the screen is dimmed, against burn-in on OLED screens. */
    var dimmed by mutableStateOf(false)
        private set

    /** Any button pressed or touch: lights a dimmed screen again, and puts off dimming it. */
    fun wake() {
        dimmed = false
        stayLit()
    }

    /** While paused, dims the screen once nothing has been pressed for a while. */
    private fun stayLit() {
        dimJob?.cancel()
        if (!paused) return
        dimJob = viewModelScope.launch {
            delay(DIM_AFTER_MS)
            if (!paused) return@launch // stopped meanwhile: left the app, or an error
            // The panels are windows of their own, over the dimming: they close.
            sheet = null
            if (overlay == OverlayState.GUIDE) {
                closeGuide()
                showControls()
            }
            dimmed = true
        }
    }

    /** Shows the controls (and keeps them up a while longer); [forSkip] when skipping brings them up. */
    fun showControls(keepOverlay: Boolean = false, forSkip: Boolean = false) {
        if (overlay != OverlayState.CONTROLS && (!keepOverlay || overlay == OverlayState.NONE)) {
            overlay = OverlayState.CONTROLS
            focusPlayPause = !forSkip
        }
        hideJob?.cancel()
        hideJob = viewModelScope.launch {
            delay(OVERLAY_TIMEOUT_MS)
            if (overlay == OverlayState.CONTROLS && sheet == null && !paused) overlay = OverlayState.NONE
        }
    }

    /** Touch: tapping the video shows the controls, or hides them. */
    fun tapVideo() {
        when (overlay) {
            OverlayState.NONE -> showControls()
            OverlayState.CONTROLS -> hideOverlay()
            OverlayState.GUIDE -> Unit
        }
    }

    fun hideOverlay() {
        hideJob?.cancel()
        overlay = OverlayState.NONE
    }

    /** Keeps the controls up (e.g. while the guide panel is open) until something else hides them. */
    protected fun cancelAutoHide() {
        hideJob?.cancel()
    }

    open fun retry() {
        resetForNewStream()
        engine?.player?.prepare()
    }

    fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        sleepMinutes = minutes
        if (minutes > 0) {
            sleepJob = viewModelScope.launch {
                delay(minutes * 60_000L)
                stop()
            }
            flash(appString(R.string.player_sleep_timer_minutes, minutes))
        }
    }

    private var trackJob: Job? = null

    /**
     * Plays track [index] of [group] ([type]: audio or subtitles, called [label]), then says whether it
     * is: what's shown is what the player did, not just what was asked.
     */
    fun chooseTrack(type: Int, group: Tracks.Group, index: Int, label: String) {
        val engine = engine ?: return
        engine.selectTrack(type, group, index)
        trackJob?.cancel()
        trackJob = viewModelScope.launch {
            delay(TRACK_CHECK_MS)
            val audio = type == C.TRACK_TYPE_AUDIO
            val said = when {
                !engine.isPlaying(group, index) ->
                    if (audio) R.string.player_audio_switch_failed else R.string.player_subtitles_switch_failed
                audio -> R.string.player_audio_switched
                else -> R.string.player_subtitles_switched
            }
            flash(appString(said, label))
        }
    }

    /** Shows [text] over the video for a moment. */
    fun flash(text: String) {
        message = text
        messageJob?.cancel()
        messageJob = viewModelScope.launch {
            delay(1_500)
            message = null
        }
    }

    override fun onCleared() {
        released = true
        engine?.player?.removeListener(listener)
        engine?.player?.removeAnalyticsListener(audioFormatListener)
        engine?.release()
    }

    private companion object {
        /** How long a track change is given to take effect before saying how it went. */
        const val TRACK_CHECK_MS = 1_500L
        const val MAX_RETRIES = 3

        /** Having played this long (in all) since the last failure counts as working: later failures get fresh retries. */
        const val STEADY_MS = 20_000L
        const val OVERLAY_TIMEOUT_MS = 5_000L

        /** How long the paused screen is left alone before it dims. */
        const val DIM_AFTER_MS = 120_000L

        /** How long ◀ ▶ rest before playback jumps to where they scrubbed to. */
        const val SCRUB_SETTLE_MS = 700L

        /** Scrubbing stops this far before the end of a video. */
        const val END_MARGIN_MS = 5_000L
    }
}
