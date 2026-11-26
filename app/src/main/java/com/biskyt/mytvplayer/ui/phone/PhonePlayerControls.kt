package com.biskyt.mytvplayer.ui.phone

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.core.player.RemoteKey
import com.biskyt.mytvplayer.data.programmeTitle
import com.biskyt.mytvplayer.ui.LocalNavigator
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.common.AppIcons
import com.biskyt.mytvplayer.ui.common.formatDuration
import com.biskyt.mytvplayer.ui.common.formatRuntime
import com.biskyt.mytvplayer.ui.common.formatTime
import com.biskyt.mytvplayer.ui.common.onPlainTap
import com.biskyt.mytvplayer.ui.common.rememberMinuteClock
import com.biskyt.mytvplayer.ui.player.CatchupPlayerViewModel
import com.biskyt.mytvplayer.ui.player.ClockAndStatus
import com.biskyt.mytvplayer.ui.player.LocalPlayerSettings
import com.biskyt.mytvplayer.ui.player.PlayBar
import com.biskyt.mytvplayer.ui.player.PlaybackViewModel
import com.biskyt.mytvplayer.ui.player.PlayerViewModel
import com.biskyt.mytvplayer.ui.player.VodPlayerViewModel
import com.biskyt.mytvplayer.ui.player.catchupBar
import com.biskyt.mytvplayer.ui.player.liveBar
import com.biskyt.mytvplayer.ui.player.numberAndName
import com.biskyt.mytvplayer.ui.player.vodBar
import com.biskyt.mytvplayer.ui.theme.LocalAccent
import com.biskyt.mytvplayer.ui.theme.Palette

// The player's controls on a phone or an unfolded foldable (the owner, 2026-11-25): large buttons for fingers, in
// place of the TV's row of buttons for the remote. The video, the waiting and error messages and the menus stay the
// TV player's (see PlayerFrame). Plain Compose foundation: no Material library and no media3-ui widgets are added.

/**
 * The play bar of the phone controls: where playback is ([bar]), with the times either side ([start] and [end]).
 */
internal class PhoneSeek(val bar: PlayBar, val start: String, val end: String)

/**
 * Taps on the video on a phone: one tap shows or hides the controls, as on a TV with touch; a double tap on the left
 * half skips back, on the right half forward, by the skip times in Settings.
 */
@Composable
internal fun Modifier.phoneVideoTaps(vm: PlaybackViewModel): Modifier {
    val current by rememberUpdatedState(vm)
    return pointerInput(Unit) {
        detectTapGestures(
            onDoubleTap = { at -> current.onKey(if (at.x < size.width / 2) RemoteKey.LEFT else RemoteKey.RIGHT) },
            onTap = { current.tapVideo() }
        )
    }
}

/** A film or an episode: its title, the skip buttons, the play bar, and Episodes and Next episode for an episode. */
@Composable
internal fun PhoneVodControls(vm: VodPlayerViewModel) {
    val nav = LocalNavigator.current

    // In place of this player, as the menu's "Go to series" opens the series page (progress saved first).
    fun replaceWith(screen: Screen) {
        vm.beforeLeaving()
        nav.replace(screen)
    }
    PhoneControls(vm, vm.title, vm.subtitle, { VodSeekRow(vm) }, live = false, trailing = {
        vm.nextEpisodeScreen?.let { next ->
            PhonePill(stringResource(R.string.phone_player_next_episode), light = true, icon = AppIcons.NextEpisode) { replaceWith(next) }
        }
    }) {
        vm.seriesPage?.let { page -> PhonePill(stringResource(R.string.phone_player_episodes)) { replaceWith(page) } }
    }
}

/**
 * A film's or an episode's play bar, with how far in and what is left. Its own composable, so that the position, which
 * changes each second, redraws only this row (as the TV's Position does).
 */
@Composable
private fun VodSeekRow(vm: VodPlayerViewModel) {
    val totalMs = (vm.durationSec * 1000).takeIf { it > 0 }
    // While skipping, the times shown are where playback will go.
    val posMs = vm.scrubMs ?: (vm.positionSec * 1000)
    val left = totalMs?.let { stringResource(R.string.common_time_left, formatDuration((it - posMs).coerceAtLeast(0))) }.orEmpty()
    SeekRow(PhoneSeek(vm.vodBar(), formatDuration(posMs), left))
}

/**
 * A live channel: its name and the programme on now, channel buttons in place of skipping, and the programme's
 * progress (a bar to drag back in time on a channel with catch-up, as on a TV with touch). With no channel (not read
 * yet, or not found), only the rest: Back, the clock and the buttons.
 */
@Composable
internal fun PhoneLiveControls(vm: PlayerViewModel) {
    val ch = vm.channel
    ch?.let { LaunchedEffect(it.streamId) { vm.nowNext.request(it.streamId) } }
    val nn = ch?.let { vm.nowNext.entries[it.streamId] }
    val now by rememberMinuteClock()
    val nav = LocalNavigator.current
    val catchup = (ch?.archiveDays ?: 0) > 0
    val seek = nn?.now?.let { p ->
        val bar = vm.liveBar(p.startMs..p.stopMs, nn.progress(now))
        PhoneSeek(if (catchup) bar else bar.copy(onSeek = null), formatTime(p.startMs), formatTime(p.stopMs))
    }
    val title = ch?.numberAndName().orEmpty()
    PhoneControls(vm, title, nn?.now?.let { programmeTitle(it.title) }, seek?.let { { SeekRow(it) } }, live = true) {
        if (ch != null) PhonePill(stringResource(R.string.player_channel_guide), onClick = vm::openGuide)
        if (ch != null && catchup) {
            PhonePill(stringResource(R.string.common_start_over)) {
                nav.replace(Screen.CatchupPlayer(ch.playlistId, ch.streamId, filter = vm.filter, kinds = vm.kinds))
            }
        }
    }
}

/**
 * Catch-up: the channel, the programme and how far behind live it is, the skip buttons and the programme's play bar.
 * With no channel (not read yet: one not found closes the player), only the rest, as for a live channel: Back, the clock
 * and the buttons.
 */
@Composable
internal fun PhoneCatchupControls(vm: CatchupPlayerViewModel) {
    val ch = vm.channel
    // While skipping: the programme and time playback will go to.
    val scrub = vm.scrubMs
    val at = scrub ?: vm.at
    val p = scrub?.let(vm::programmeAt) ?: vm.programme
    val behind = ((System.currentTimeMillis() - at) / 1000).toInt()
    val seek = p?.let { PhoneSeek(vm.catchupBar(p.startMs..p.stopMs), formatTime(p.startMs), formatTime(p.stopMs)) }
    val line = listOfNotNull(
        p?.let { programmeTitle(it.title) },
        stringResource(R.string.player_catchup_behind_live, formatRuntime(behind))
    ).joinToString(" · ")
    PhoneControls(vm, ch?.numberAndName().orEmpty(), line, seek?.let { { SeekRow(it) } }, live = false) {
        if (ch != null) {
            PhonePill(stringResource(R.string.player_channel_guide), onClick = vm::openGuide)
            PhonePill(stringResource(R.string.common_from_start), onClick = vm::fromStart)
            PhonePill(stringResource(R.string.player_watch_live), onClick = vm::watchLive)
        }
    }
}

/**
 * The phone overlay over the video, as the approved mock-up has it: Back, the [title] and [subtitle], the VPN and proxy
 * statuses and the clock along the top; the skip buttons ([live]: the channel buttons) around play/pause in the
 * middle; the play bar ([seek]: null when there is none) and the pill buttons at the bottom: Audio, Subtitles,
 * [pills], More, and [trailing] at the right-hand end.
 */
@Composable
private fun PhoneControls(
    vm: PlaybackViewModel,
    title: String,
    subtitle: String?,
    seek: (@Composable () -> Unit)?,
    live: Boolean,
    trailing: @Composable RowScope.() -> Unit = {},
    pills: @Composable RowScope.() -> Unit = {}
) {
    Box(Modifier.fillMaxSize().background(Shade)) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            TopRow(vm, title, subtitle)
            MiddleButtons(vm, live, Modifier.align(Alignment.CenterHorizontally))
            Column(
                // A tap beside a button keeps the controls up, rather than hiding them.
                Modifier.fillMaxWidth().onPlainTap {}.padding(start = 24.dp, end = 24.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                seek?.invoke()
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PhonePill(stringResource(R.string.player_audio)) { vm.sheet = PlaybackViewModel.Sheet.AUDIO }
                    PhonePill(stringResource(R.string.player_subtitles)) { vm.sheet = PlaybackViewModel.Sheet.SUBTITLES }
                    pills()
                    PhonePill(stringResource(R.string.player_more)) { vm.openMore() }
                    Spacer(Modifier.weight(1f))
                    trailing()
                }
            }
        }
    }
}

/** Back (it stops playback, as Back does on a TV), the title, and the statuses and clock that the TV's clock panel shows. */
@Composable
private fun TopRow(vm: PlaybackViewModel, title: String, subtitle: String?) {
    Row(
        Modifier.fillMaxWidth().onPlainTap {}.padding(start = 12.dp, end = 24.dp, top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        PhoneBackButton(vm::stop)
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = Palette.text,
                fontSize = 17.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrEmpty()) {
                Text(subtitle, color = Palette.textMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        ClockAndStatus(LocalPlayerSettings.current, phone = true)
    }
}

/** Play/pause, larger and in the accent colour, between skip back and forward (or, on a live channel, the channel buttons). */
@Composable
private fun MiddleButtons(vm: PlaybackViewModel, live: Boolean, modifier: Modifier) {
    val settings by LocalPlayerSettings.current
    val accent = LocalAccent.current.color
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(56.dp)) {
        if (live) {
            SideButton(AppIcons.ChannelUp, stringResource(R.string.phone_player_previous_channel)) { vm.onKey(RemoteKey.CHANNEL_UP) }
        } else {
            SideButton(AppIcons.SkipBack, stringResource(R.string.common_skip_back), settings?.skipBackSec?.toString()) {
                vm.onKey(RemoteKey.LEFT)
            }
        }
        PhoneIconButton(
            if (vm.isPlaying) AppIcons.Pause else AppIcons.Play,
            stringResource(if (vm.isPlaying) R.string.player_pause else R.string.common_play),
            { vm.togglePlayPause() },
            size = 76.dp,
            fill = accent,
            tint = Palette.background,
            iconSize = LARGE_ICON
        )
        if (live) {
            SideButton(AppIcons.ChannelDown, stringResource(R.string.phone_player_next_channel)) { vm.onKey(RemoteKey.CHANNEL_DOWN) }
        } else {
            SideButton(AppIcons.SkipForward, stringResource(R.string.common_skip_forward), settings?.skipForwardSec?.toString()) {
                vm.onKey(RemoteKey.RIGHT)
            }
        }
    }
}

/** A button beside play/pause: dark and see-through, with a smaller icon over the skip's seconds ([number]). */
@Composable
private fun SideButton(icon: ImageVector, label: String, number: String? = null, onClick: () -> Unit) = PhoneIconButton(
    icon,
    label,
    onClick,
    size = 60.dp,
    fill = SideFill,
    iconSize = if (number == null) LARGE_ICON else 22.dp,
    number = number
)

/** The times either side of the play bar. */
@Composable
private fun SeekRow(seek: PhoneSeek) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TimeText(seek.start)
        PhoneSeekBar(seek.bar, Modifier.weight(1f))
        TimeText(seek.end)
    }
}

@Composable
private fun TimeText(text: String) = Text(
    text,
    color = Palette.text,
    fontSize = 13.sp,
    fontWeight = FontWeight.ExtraBold,
    maxLines = 1,
    modifier = Modifier.widthIn(min = 40.dp)
)

/**
 * The play bar: thin, in the accent colour as far as playback has got, with a handle where it goes (a bar that only
 * shows progress has none). It takes touches across a strip [PhoneTouch] high: a tap or a drag picks where to play from.
 */
@Composable
private fun PhoneSeekBar(bar: PlayBar, modifier: Modifier) {
    val accent = LocalAccent.current.color
    val onSeek by rememberUpdatedState(bar.onSeek)
    val seekable = bar.onSeek != null
    val input = if (!seekable) {
        Modifier
    } else {
        Modifier.pointerInput(Unit) {
            detectTapGestures { onSeek?.invoke((it.x / size.width).coerceIn(0f, 1f)) }
        }.pointerInput(Unit) {
            detectHorizontalDragGestures { change, _ ->
                change.consume()
                onSeek?.invoke((change.position.x / size.width).coerceIn(0f, 1f))
            }
        }
    }
    val at = (bar.scrub ?: bar.fraction).coerceIn(0f, 1f)
    Canvas(modifier.height(PhoneTouch).then(input)) {
        val line = 4.dp.toPx()
        val y = size.height / 2
        val radius = CornerRadius(line / 2)
        drawRoundRect(Color.White.copy(alpha = 0.25f), Offset(0f, y - line / 2), Size(size.width, line), radius)
        drawRoundRect(accent, Offset(0f, y - line / 2), Size(size.width * bar.fraction.coerceIn(0f, 1f), line), radius)
        if (seekable) drawCircle(Palette.text, radius = 9.dp.toPx(), center = Offset(size.width * at, y))
    }
}

private val SideFill = Color(0x59000000)

/** The icon of play/pause, and of a side button with no number under it. */
private val LARGE_ICON = 30.dp

/** Dark at the top and the bottom behind the text and buttons, clear in the middle over the picture. */
private val Shade = Brush.verticalGradient(
    0f to Color.Black.copy(alpha = 0.65f),
    0.3f to Color.Transparent,
    0.62f to Color.Transparent,
    1f to Color.Black.copy(alpha = 0.75f)
)
