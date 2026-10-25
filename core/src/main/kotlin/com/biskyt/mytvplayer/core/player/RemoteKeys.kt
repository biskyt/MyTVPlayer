package com.biskyt.mytvplayer.core.player

/** Remote control buttons the player reacts to (mapped from Android key codes by the app). */
enum class RemoteKey {
    LEFT,
    RIGHT,
    UP,
    DOWN,
    OK,
    INFO,
    BACK,
    PLAY_PAUSE,
    PLAY,
    PAUSE,
    STOP,
    REWIND,
    FAST_FORWARD,
    GUIDE,
    MENU,
    CHANNEL_UP,
    CHANNEL_DOWN,
    DIGIT,
}

/** The media buttons (play/pause, stop, rewind, fast forward): they work whatever is open over the video. */
val RemoteKey.isMedia: Boolean
    get() = this in mediaButtons

private val mediaButtons =
    setOf(RemoteKey.PLAY_PAUSE, RemoteKey.PLAY, RemoteKey.PAUSE, RemoteKey.STOP, RemoteKey.REWIND, RemoteKey.FAST_FORWARD)

/**
 * The buttons a panel over the video (the channel guide, a menu) passes on to the player, whose own keys it would
 * otherwise never see: the media buttons, Guide and Menu. The player acts on them as [KeyActionMapper] says.
 */
val RemoteKey.passedOnByPanels: Boolean
    get() = isMedia || this == RemoteKey.GUIDE || this == RemoteKey.MENU

sealed interface PlayerAction {
    data class SeekBy(val ms: Long) : PlayerAction
    data class Zap(val step: Int) : PlayerAction
    data class Digit(val value: Int) : PlayerAction
    data object ToggleOverlay : PlayerAction
    data object PauseAndShowControls : PlayerAction
    data object HideOverlay : PlayerAction
    data object TogglePlayPause : PlayerAction
    data object Play : PlayerAction
    data object Pause : PlayerAction
    data object StopAndExit : PlayerAction
    data object OpenGuide : PlayerAction
    data object CloseGuide : PlayerAction
    data object OpenMenu : PlayerAction
    data object CloseMenu : PlayerAction
    data object None : PlayerAction
}

/** What is currently shown on top of the video; decides how keys are interpreted. */
enum class OverlayState { NONE, CONTROLS, GUIDE }

/**
 * Pure mapping from a remote key to a player action, so the behaviour is easy to test:
 * left/right always skip (by the user's settings); the channel buttons zap on live TV, and up/down too when
 * [upDownZaps] (else, with nothing open, they show the controls); OK pauses and shows the controls, but on live TV
 * with [upDownZaps] it only shows them (there's no other easy way to); Back closes the topmost overlay or stops
 * playback; Menu opens the More menu, or closes it when [menuOpen].
 * The media buttons (play/pause, rewind, fast forward, stop) work whatever is open.
 */
class KeyActionMapper(private val skipBackMs: Long, private val skipForwardMs: Long, private val upDownZaps: Boolean = false) {

    fun map(key: RemoteKey, overlay: OverlayState, isLive: Boolean, digit: Int = 0, menuOpen: Boolean = false): PlayerAction {
        if (overlay == OverlayState.GUIDE) {
            // The guide panel handles its own navigation; Back/Guide close it, Menu opens the More menu in its place,
            // and the media buttons still work.
            return when (key) {
                RemoteKey.BACK, RemoteKey.GUIDE -> PlayerAction.CloseGuide
                RemoteKey.MENU -> PlayerAction.OpenMenu
                else -> if (key.isMedia) map(key, OverlayState.NONE, isLive) else PlayerAction.None
            }
        }
        return when (key) {
            RemoteKey.LEFT, RemoteKey.REWIND -> PlayerAction.SeekBy(-skipBackMs)
            RemoteKey.RIGHT, RemoteKey.FAST_FORWARD -> PlayerAction.SeekBy(skipForwardMs)
            RemoteKey.UP, RemoteKey.DOWN -> when {
                // Zapping with up/down works with the controls showing too.
                isLive && upDownZaps -> PlayerAction.Zap(if (key == RemoteKey.UP) -1 else 1)
                overlay != OverlayState.NONE -> PlayerAction.None
                else -> PlayerAction.ToggleOverlay
            }
            RemoteKey.CHANNEL_UP -> if (isLive) PlayerAction.Zap(-1) else PlayerAction.None
            RemoteKey.CHANNEL_DOWN -> if (isLive) PlayerAction.Zap(1) else PlayerAction.None
            RemoteKey.OK -> when {
                overlay != OverlayState.NONE || (isLive && upDownZaps) -> PlayerAction.ToggleOverlay
                else -> PlayerAction.PauseAndShowControls
            }
            RemoteKey.INFO -> PlayerAction.ToggleOverlay
            RemoteKey.BACK -> if (overlay == OverlayState.CONTROLS) PlayerAction.HideOverlay else PlayerAction.StopAndExit
            RemoteKey.STOP -> PlayerAction.StopAndExit
            RemoteKey.PLAY_PAUSE -> PlayerAction.TogglePlayPause
            RemoteKey.PLAY -> PlayerAction.Play
            RemoteKey.PAUSE -> PlayerAction.Pause
            RemoteKey.GUIDE -> if (isLive) PlayerAction.OpenGuide else PlayerAction.None
            RemoteKey.MENU -> if (menuOpen) PlayerAction.CloseMenu else PlayerAction.OpenMenu
            RemoteKey.DIGIT -> if (isLive) PlayerAction.Digit(digit) else PlayerAction.None
        }
    }
}

/**
 * Where scrubbing goes next: [stepMs] on from [fromMs], kept within [range]. Holding the button ([repeat] is how many
 * times it has repeated) speeds up, so the length of a film can be crossed in a few seconds.
 */
fun scrubTarget(fromMs: Long, stepMs: Long, repeat: Int, range: LongRange): Long {
    val speed = (1 + repeat / SCRUB_SPEED_UP_EVERY).coerceAtMost(SCRUB_MAX_SPEED)
    return (fromMs + stepMs * speed).coerceIn(range)
}

private const val SCRUB_SPEED_UP_EVERY = 5
private const val SCRUB_MAX_SPEED = 12
