package com.biskyt.mytvplayer.ui.player

import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.media3.ui.compose.state.rememberPresentationState
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.core.player.OverlayState
import com.biskyt.mytvplayer.core.player.RemoteKey
import com.biskyt.mytvplayer.core.player.isMedia
import com.biskyt.mytvplayer.core.player.passedOnByPanels
import com.biskyt.mytvplayer.data.programmeTitle
import com.biskyt.mytvplayer.player.PlaybackError
import com.biskyt.mytvplayer.ui.LocalNavigator
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.appViewModel
import com.biskyt.mytvplayer.ui.common.AppIcons
import com.biskyt.mytvplayer.ui.common.ChannelLogo
import com.biskyt.mytvplayer.ui.common.Clock
import com.biskyt.mytvplayer.ui.common.FlashLabel
import com.biskyt.mytvplayer.ui.common.LocalOverVideo
import com.biskyt.mytvplayer.ui.common.LocalTouch
import com.biskyt.mytvplayer.ui.common.Muted
import com.biskyt.mytvplayer.ui.common.PillButton
import com.biskyt.mytvplayer.ui.common.ProgrammeAbout
import com.biskyt.mytvplayer.ui.common.ProgressLine
import com.biskyt.mytvplayer.ui.common.TvCard
import com.biskyt.mytvplayer.ui.common.digit
import com.biskyt.mytvplayer.ui.common.formatRuntime
import com.biskyt.mytvplayer.ui.common.formatTime
import com.biskyt.mytvplayer.ui.common.ignoreHeldSelect
import com.biskyt.mytvplayer.ui.common.numberLabel
import com.biskyt.mytvplayer.ui.common.onAnyTouch
import com.biskyt.mytvplayer.ui.common.onPlainTap
import com.biskyt.mytvplayer.ui.common.rememberFirstFocus
import com.biskyt.mytvplayer.ui.common.rememberMinuteClock
import com.biskyt.mytvplayer.ui.theme.LocalAccent
import com.biskyt.mytvplayer.ui.theme.Palette

@Composable
fun PlayerScreen(screen: Screen.Player) {
    val appContext = LocalContext.current.applicationContext
    val vm = appViewModel { PlayerViewModel(it, appContext, screen) }
    vm.nowNext.PauseWhenHidden()
    PlayerFrame(
        vm,
        controls = { buttons, root -> LiveControls(vm, buttons, root) },
        extras = { if (vm.numberEntry.typed.isNotEmpty()) FlashLabel(vm.numberEntry.typed, Modifier.align(Alignment.TopEnd)) }
    )
    if (vm.overlay == OverlayState.GUIDE) GuidePanel(vm)
}

/**
 * The video with everything common to live and on-demand playback: keys, the loading and error states, flash
 * messages, side sheets, and stopping when the app is left. [controls] draws the overlay shown on OK.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun PlayerFrame(
    vm: PlaybackViewModel,
    controls: @Composable (buttons: FocusRequester, root: FocusRequester) -> Unit,
    extras: @Composable BoxScope.() -> Unit = {}
) {
    val nav = LocalNavigator.current
    val root = remember { FocusRequester() }
    val buttons = remember { FocusRequester() }
    var videoFocused by remember { mutableStateOf(false) }
    val acted = remember { arrayOfNulls<Key>(1) }

    if (vm.exit) {
        LaunchedEffect(Unit) { nav.pop() }
        return
    }
    BackHandler { vm.back() }
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        view.keepScreenOn = true
        // Leaving the app (Home button) stops playback instead of streaming in the background.
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> vm.onAppHidden()
                Lifecycle.Event.ON_START -> vm.onAppShown()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            view.keepScreenOn = false
            // Another screen opened over the player (e.g. from the More menu): stop as when leaving the app. Coming
            // back adds the observer again, and its ON_START starts playback again.
            if (!vm.exit && !vm.released) vm.onAppHidden()
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(root)
            .onFocusChanged { videoFocused = it.isFocused }
            .wakeKeys(vm)
            // A fresh press is a press of its own, even if the last one's release went to another window (a panel),
            // or a button took it.
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) acted[0] = null
                false
            }
            .onKeyEvent { e -> playerKey(e, vm, buttons, videoFocused, acted) }
            .focusable()
            // Touch: any touch keeps the controls up; a tap on the video itself shows or hides them.
            .onAnyTouch {
                vm.wake()
                if (vm.overlay == OverlayState.CONTROLS) vm.showControls(keepOverlay = true)
            }
            .onPlainTap(vm::tapVideo)
    ) {
        vm.engine?.let { engine ->
            val presentation = rememberPresentationState(engine.player)
            PlayerSurface(
                player = engine.player,
                surfaceType = SURFACE_TYPE_SURFACE_VIEW,
                modifier = Modifier.picture(vm.picture, presentation.videoSizeDp)
            )
            // Over the picture's area, so picture subtitles land where the stream puts them; within the screen, so a
            // zoomed picture doesn't push them off it.
            Subtitles(engine.player, Modifier.picture(vm.picture, presentation.videoSizeDp, onScreen = true))
        }
        if (vm.buffering && vm.error == null) {
            Text(
                stringResource(R.string.common_loading),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.align(Alignment.Center)
            )
        }
        vm.error?.let { ErrorBox(it, vm) }
        if (vm.overlay == OverlayState.CONTROLS) {
            // With the video focused (Up from the buttons), left and right skip: the bar shows its handle (no remote: none).
            CompositionLocalProvider(LocalBarSelected provides (videoFocused && !LocalTouch.current)) { controls(buttons, root) }
            Clock(
                Modifier.align(Alignment.TopEnd).padding(top = 27.dp, end = 56.dp)
                    .clip(RoundedCornerShape(9.dp)).background(PanelShade).padding(horizontal = 14.dp, vertical = 6.dp),
                MaterialTheme.typography.headlineSmall,
                Palette.text
            )
            // A touch screen has no remote with a Back button in reach: this goes back to the list.
            if (LocalTouch.current) {
                RoundButton(
                    AppIcons.Back,
                    stringResource(R.string.player_back),
                    modifier = Modifier.align(Alignment.TopStart).padding(24.dp),
                    onClick = vm::stop
                )
            }
        }
        vm.message?.let { FlashLabel(it, Modifier.align(Alignment.TopCenter)) }
        extras()
        // Over everything, and a tap on it only lights the screen again.
        if (vm.dimmed) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = DIM_ALPHA)).onPlainTap {})
    }
    vm.sheet?.let { PlayerSheet(it, vm) }
    FocusFollowsOverlay(vm, root)
}

/** How dark the paused screen goes when it dims. */
private const val DIM_ALPHA = 0.85f

private val volumeKeys = setOf(Key.VolumeUp, Key.VolumeDown, Key.VolumeMute)

/**
 * Every key pressed on the player puts off dimming the paused screen. On a dimmed screen, the whole press (down to up,
 * so its release doesn't press a button either) only lights it again; the media and volume buttons also do their
 * job, so play plays straight away.
 */
@Composable
private fun Modifier.wakeKeys(vm: PlaybackViewModel): Modifier {
    val swallowing = remember { arrayOfNulls<Key>(1) }
    return onPreviewKeyEvent { e ->
        // A fresh press is a press of its own, even if the last swallowed one's release went to another window.
        if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) swallowing[0] = null
        when {
            e.key == swallowing[0] -> { // its release, or held
                if (e.type == KeyEventType.KeyUp) swallowing[0] = null
                true
            }
            e.type != KeyEventType.KeyDown -> false
            vm.dimmed && remoteKey(e.key)?.isMedia != true && e.key !in volumeKeys -> {
                swallowing[0] = e.key
                vm.wake()
                true
            }
            else -> {
                vm.wake()
                false
            }
        }
    }
}

/** Focus goes back to the video when nothing's over it (play/pause is the controls' own: see [ControlsBar]). */
@Composable
private fun FocusFollowsOverlay(vm: PlaybackViewModel, root: FocusRequester) {
    LaunchedEffect(vm.overlay, vm.sheet, vm.controlsFocus) {
        val toButtons = vm.overlay == OverlayState.CONTROLS && vm.controlsFocus != PlaybackViewModel.ControlsFocus.VIDEO
        if (vm.sheet == null && vm.overlay != OverlayState.GUIDE && !toButtons) runCatching { root.requestFocus() }
    }
}

/**
 * A remote key on its way out of the controls: while a control button has focus the D-pad and OK belong to it; every
 * other key (channel, digits, media) still works. The release of a press the player acted on ([acted]) is the
 * player's too, else the TV acts on it (the Options button's, with focus moved to More, would open the TV's menu).
 */
private fun playerKey(e: KeyEvent, vm: PlaybackViewModel, buttons: FocusRequester, videoFocused: Boolean, acted: Array<Key?>): Boolean =
    when {
        // Back is the player's whole press, acting once on release as the system's Back does (held, it doesn't stop
        // playback too): left to Compose, its press would first move focus out of the buttons, to the bar.
        e.key == Key.Back -> true.also { if (e.type == KeyEventType.KeyUp) vm.back() }
        e.type == KeyEventType.KeyUp -> (e.key == acted[0]).also { acted[0] = null }
        e.type != KeyEventType.KeyDown -> false
        else -> (videoFocused || e.key !in buttonKeys) && handleKey(e, vm, buttons).also { if (it) acted[0] = e.key }
    }

/** Remote keys on the video: left/right skip (and scrub, held), down with controls showing moves into the buttons. */
private fun handleKey(e: KeyEvent, vm: PlaybackViewModel, buttons: FocusRequester): Boolean {
    if (vm.overlay == OverlayState.CONTROLS && e.key == Key.DirectionDown && !vm.upDownZaps) {
        return runCatching { buttons.requestFocus() }.isSuccess
    }
    val key = remoteKey(e.key)
    if (key != null) return vm.onKey(key, repeat = e.nativeKeyEvent.repeatCount)
    val digit = e.key.digit() ?: return false
    return vm.onKey(RemoteKey.DIGIT, digit)
}

private fun remoteKey(key: Key): RemoteKey? = when (key) {
    Key.DirectionLeft -> RemoteKey.LEFT
    Key.DirectionRight -> RemoteKey.RIGHT
    Key.DirectionUp -> RemoteKey.UP
    Key.DirectionDown -> RemoteKey.DOWN
    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> RemoteKey.OK
    Key.Info -> RemoteKey.INFO
    Key.MediaPlayPause -> RemoteKey.PLAY_PAUSE
    Key.MediaPlay -> RemoteKey.PLAY
    Key.MediaPause -> RemoteKey.PAUSE
    Key.MediaStop -> RemoteKey.STOP
    Key.MediaRewind -> RemoteKey.REWIND
    Key.MediaFastForward -> RemoteKey.FAST_FORWARD
    Key.Guide -> RemoteKey.GUIDE
    Key.Menu -> RemoteKey.MENU
    Key.ChannelUp, Key.PageUp -> RemoteKey.CHANNEL_UP
    Key.ChannelDown, Key.PageDown -> RemoteKey.CHANNEL_DOWN
    else -> null
}

/**
 * The remote's buttons inside a panel over the video (a window of its own, so they'd never reach the player) that it
 * passes on to the player ([passedOnByPanels]): the media buttons, Guide and Menu, which open their panel in this one's
 * place or close it. A button the player doesn't act on when pressed is left to the TV (Guide on a film: the TV's
 * own), the whole press; one it acts on is kept, the whole press, ahead of the panel's own buttons.
 */
@Composable
internal fun Modifier.panelKeys(vm: PlaybackViewModel): Modifier {
    // The buttons held now that are kept, decided as each is pressed (what it does may change what the player acts on,
    // e.g. close this panel). The rest of a press that opened this panel never gets here (see ignoreHeldButtons).
    val kept = remember { mutableSetOf<Key>() }
    return onAnyTouch(vm::wake).onPreviewKeyEvent { e ->
        if (e.type == KeyEventType.KeyDown) vm.wake() // any key or touch puts off dimming the paused screen
        val key = remoteKey(e.key)?.takeIf { it.passedOnByPanels } ?: return@onPreviewKeyEvent false
        val repeat = e.nativeKeyEvent.repeatCount
        if (e.type == KeyEventType.KeyDown && repeat == 0) if (vm.actsOn(key)) kept += e.key else kept -= e.key
        if (e.key !in kept) return@onPreviewKeyEvent false
        if (e.type == KeyEventType.KeyDown) vm.onKey(key, repeat = repeat) else kept -= e.key
        true
    }
}

@Composable
private fun LiveControls(vm: PlayerViewModel, buttons: FocusRequester, root: FocusRequester) {
    val ch = vm.channel ?: return
    LaunchedEffect(ch.streamId) { vm.nowNext.request(ch.streamId) }
    val nn = vm.nowNext.entries[ch.streamId]
    val details = vm.nowDetails
    val accent = LocalAccent.current
    val now by rememberMinuteClock()
    ControlsPanel {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            ChannelLogo(ch.logoUrl, ch.name, 110.dp, 66.dp)
            Column(Modifier.weight(1f)) {
                Muted(listOf(ch.numberLabel, ch.name).filter { it.isNotEmpty() }.joinToString(" · "))
                Text(
                    nn?.now?.let { programmeTitle(it.title) } ?: ch.name,
                    style = MaterialTheme.typography.displaySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                details?.let { ProgrammeAbout(it, maxLines = 2) }
            }
            nn?.next?.let { next ->
                Column(Modifier.width(320.dp), horizontalAlignment = Alignment.End) {
                    Text(
                        stringResource(R.string.player_up_next, formatTime(next.startMs)),
                        style = MaterialTheme.typography.labelMedium,
                        color = accent.color
                    )
                    Text(
                        programmeTitle(next.title),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        nn?.now?.let { current ->
            // Whole minutes, rounded up: "1 min left" until it ends (and nothing once it has, until the guide moves on).
            val minutesLeft = (current.stopMs - now + MINUTE_MS - 1) / MINUTE_MS
            Timeline(
                formatTime(current.startMs),
                nn.progress(now),
                formatTime(current.stopMs),
                after = if (minutesLeft > 0) stringResource(R.string.common_time_left, formatRuntime((minutesLeft * 60).toInt())) else null
            )
        }
        ControlsBar(vm, buttons, root) {
            PillButton(stringResource(R.string.player_channel_guide), vm::openGuide)
            if (details?.description != null || details?.subtitle != null) {
                PillButton(stringResource(R.string.player_about_programme), { vm.sheet = PlaybackViewModel.Sheet.ABOUT })
            }
            if (ch.archiveDays > 0) {
                val nav = LocalNavigator.current
                PillButton(
                    stringResource(R.string.common_start_over),
                    { nav.replace(Screen.CatchupPlayer(ch.playlistId, ch.streamId, filter = vm.filter)) }
                )
            }
        }
    }
}

private val PANEL_FADE = 80.dp

private const val MINUTE_MS = 60_000L

/** Behind the controls (and the clock): dark enough for text over a bright picture, light enough to see it through. */
private val PanelShade = Color(0xBF080A0D)

/** Whether the remote is on the progress bar (the video has focus, so left and right skip): a film's shows its handle. */
internal val LocalBarSelected = compositionLocalOf { false }

/** The shaded panel at the bottom of the video that holds the controls. */
@Composable
internal fun ControlsPanel(content: @Composable ColumnScope.() -> Unit) {
    val fadePx = with(LocalDensity.current) { PANEL_FADE.toPx() }
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                .onPlainTap {} // a tap beside a button keeps the controls up, rather than hiding them
                // Fades in above the top line, then dark behind every line however tall the controls are, so the text
                // reads over a bright picture too.
                .background(Brush.verticalGradient(listOf(Color.Transparent, PanelShade), endY = fadePx))
                .padding(start = 56.dp, end = 56.dp, top = PANEL_FADE, bottom = 36.dp)
        ) {
            CompositionLocalProvider(LocalOverVideo provides true) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
            }
        }
    }
}

/** Start time, the [SeekBar], end time, and [after] it when given. */
@Composable
internal fun Timeline(
    start: String,
    fraction: Float,
    end: String,
    scrub: Float? = null,
    handle: Boolean = false,
    after: String? = null,
    onSeek: ((Float) -> Unit)? = null
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Muted(start)
        SeekBar(fraction, scrub, handle, onSeek, Modifier.weight(1f))
        Muted(end)
        after?.let { Muted(it) }
    }
}

/**
 * The progress bar: while scrubbing, a marker at [scrub] (the same scale as [fraction]), else at [fraction] when
 * [handle] (the bar is selected). Touch: a tap or a drag along it ([onSeek]) picks where to play from.
 */
@Composable
internal fun SeekBar(fraction: Float, scrub: Float?, handle: Boolean, onSeek: ((Float) -> Unit)?, modifier: Modifier) {
    // A taller strip than the bar, for fingers.
    val latest by rememberUpdatedState(onSeek)
    val seek = if (onSeek == null) {
        Modifier
    } else {
        Modifier.pointerInput(Unit) {
            detectTapGestures { latest?.invoke(it.x / size.width) }
        }.pointerInput(Unit) {
            detectHorizontalDragGestures { change, _ -> latest?.invoke(change.position.x / size.width) }
        }
    }
    Box(modifier.then(seek).padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
        ProgressLine(fraction, Modifier.fillMaxWidth(), height = 6.dp, marker = scrub ?: fraction.takeIf { handle })
    }
}

/**
 * The row of buttons under the video: [leading] on the left, play controls in the middle, tracks and more on the right.
 * Play/pause ([buttons]) takes focus as the controls come up (unless they came up for skipping).
 */
@Composable
internal fun ControlsBar(
    vm: PlaybackViewModel,
    buttons: FocusRequester,
    root: FocusRequester,
    leading: @Composable RowScope.() -> Unit = {}
) {
    val more = remember { FocusRequester() }
    // As they come up (when what's playing is known), and again when a panel over them closes.
    LaunchedEffect(vm.sheet, vm.controlsFocus, vm.optionsPresses) {
        if (vm.sheet != null) return@LaunchedEffect
        when (vm.controlsFocus) {
            PlaybackViewModel.ControlsFocus.PLAY_PAUSE -> runCatching { buttons.requestFocus() }
            PlaybackViewModel.ControlsFocus.MORE -> runCatching { more.requestFocus() }
            PlaybackViewModel.ControlsFocus.VIDEO -> Unit
        }
    }
    Row(
        // The OK that brought the controls up is still down: its release (and repeats) mustn't press play/pause too.
        Modifier.ignoreHeldSelect().onPreviewKeyEvent { e ->
            val upDown = e.key == Key.DirectionUp || e.key == Key.DirectionDown
            when {
                e.type != KeyEventType.KeyDown -> false
                // Up and down change channel, if set to, wherever focus is.
                upDown && vm.upDownZaps -> vm.onKey(if (e.key == Key.DirectionUp) RemoteKey.UP else RemoteKey.DOWN)
                else -> {
                    // Moving between the buttons keeps the controls up.
                    vm.showControls(keepOverlay = true)
                    // Up from the buttons returns to the video, where left/right skip again.
                    e.key == Key.DirectionUp && runCatching { root.requestFocus() }.isSuccess
                }
            }
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        leading()
        Box(Modifier.weight(1f))
        RoundButton(AppIcons.Rewind, stringResource(R.string.common_skip_back)) { vm.onKey(RemoteKey.LEFT) }
        RoundButton(
            if (vm.isPlaying) AppIcons.Pause else AppIcons.Play,
            stringResource(if (vm.isPlaying) R.string.player_pause else R.string.common_play),
            primary = true,
            modifier = Modifier.focusRequester(buttons)
        ) { vm.togglePlayPause() }
        RoundButton(AppIcons.Forward, stringResource(R.string.common_skip_forward)) { vm.onKey(RemoteKey.RIGHT) }
        RoundButton(AppIcons.Stop, stringResource(R.string.player_stop)) { vm.stop() }
        Box(Modifier.weight(1f))
        PillButton(stringResource(R.string.player_audio), { vm.sheet = PlaybackViewModel.Sheet.AUDIO })
        PillButton(stringResource(R.string.player_subtitles), { vm.sheet = PlaybackViewModel.Sheet.SUBTITLES })
        PillButton(stringResource(R.string.player_more), { vm.sheet = PlaybackViewModel.Sheet.MORE }, Modifier.focusRequester(more))
    }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, primary: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val accent = LocalAccent.current
    TvCard(
        onClick = onClick,
        modifier = modifier,
        shape = CircleShape,
        color = if (primary) accent.color else Palette.surfaceHigh,
        focusedColor = if (primary) accent.color else Palette.surfaceHigh
    ) {
        Icon(
            icon,
            label,
            tint = if (primary) accent.onAccent else Palette.text,
            modifier = Modifier.padding(if (primary) 18.dp else 14.dp).size(24.dp)
        )
    }
}

@Composable
private fun ErrorBox(error: PlaybackError, vm: PlaybackViewModel) {
    val retry = rememberFirstFocus(error)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.width(560.dp)
        ) {
            Text(error.message, style = MaterialTheme.typography.titleLarge)
            error.reason?.let { Muted(it, maxLines = 4) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton(stringResource(R.string.common_retry), vm::retry, retry, primary = true)
                PillButton(stringResource(R.string.player_back), vm::stop)
            }
        }
    }
}

/** Keys a focused control button handles itself. */
private val buttonKeys =
    setOf(Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown, Key.DirectionCenter, Key.Enter, Key.NumPadEnter)
