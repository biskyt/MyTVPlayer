package com.biskyt.mytvplayer.ui.phone

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.container
import com.biskyt.mytvplayer.core.player.RemoteKey
import com.biskyt.mytvplayer.data.programmeTitle
import com.biskyt.mytvplayer.ui.LocalNavigator
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.common.AppIcons
import com.biskyt.mytvplayer.ui.common.NowNext
import com.biskyt.mytvplayer.ui.common.ProxyStatus
import com.biskyt.mytvplayer.ui.common.StatusLook
import com.biskyt.mytvplayer.ui.common.VpnStatus
import com.biskyt.mytvplayer.ui.common.formatDuration
import com.biskyt.mytvplayer.ui.common.formatRuntime
import com.biskyt.mytvplayer.ui.common.formatTime
import com.biskyt.mytvplayer.ui.common.numberLabel
import com.biskyt.mytvplayer.ui.common.onPlainTap
import com.biskyt.mytvplayer.ui.common.rememberMinuteClock
import com.biskyt.mytvplayer.ui.common.rememberProxy
import com.biskyt.mytvplayer.ui.common.rememberVpn
import com.biskyt.mytvplayer.ui.player.CatchupPlayerViewModel
import com.biskyt.mytvplayer.ui.player.PlaybackViewModel
import com.biskyt.mytvplayer.ui.player.PlayerViewModel
import com.biskyt.mytvplayer.ui.player.VodPlayerViewModel
import com.biskyt.mytvplayer.ui.theme.LocalAccent
import com.biskyt.mytvplayer.ui.theme.Palette

// The player's controls on a phone or an unfolded foldable (phone spike, 2026-11-25): large buttons for fingers, in
// place of the TV's row of buttons for the remote. The video, the waiting and error messages and the menus stay the
// TV player's (see PlayerFrame). Plain Compose foundation: no Material library and no media3-ui widgets are added.

/**
 * The play bar of the phone controls: where playback is ([fraction], or [scrub] while skipping), with the times either
 * side. [onSeek] (null: the bar only shows progress) takes the fraction a tap or a drag along the bar picks.
 */
internal class PhoneSeek(val fraction: Float, val scrub: Float?, val start: String, val end: String, val onSeek: ((Float) -> Unit)?)

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
    val totalMs = (vm.durationSec * 1000).takeIf { it > 0 }
    val scrub = vm.scrubMs
    // While skipping, the times shown are where playback will go.
    val posMs = scrub ?: (vm.positionSec * 1000)
    fun fraction(ms: Long) = totalMs?.let { ms.toFloat() / it } ?: 0f
    val seek = PhoneSeek(
        fraction(vm.positionSec * 1000),
        scrub?.let(::fraction),
        formatDuration(posMs),
        totalMs?.let { "−" + formatDuration((it - posMs).coerceAtLeast(0)) }.orEmpty(),
        totalMs?.let { t -> { f -> vm.scrubTo(0L..t, f) } }
    )

    // In place of this player, as the menu's "Go to series" opens the series page (progress saved first).
    fun replaceWith(screen: Screen) {
        vm.beforeLeaving()
        nav.replace(screen)
    }
    PhoneControls(vm, vm.title, vm.subtitle, seek, live = false, trailing = {
        vm.nextEpisodeScreen?.let { next ->
            PhonePill(stringResource(R.string.phone_player_next_episode), light = true, icon = NextIcon) { replaceWith(next) }
        }
    }) {
        vm.episodesScreen?.let { episodes -> PhonePill(stringResource(R.string.phone_player_episodes)) { replaceWith(episodes) } }
    }
}

/**
 * A live channel: its name and the programme on now, channel buttons in place of skipping, and the programme's
 * progress (a bar to drag back in time on a channel with catch-up, as on a TV with touch).
 */
@Composable
internal fun PhoneLiveControls(vm: PlayerViewModel, nn: NowNext?, now: Long) {
    val ch = vm.channel ?: return
    val nav = LocalNavigator.current
    val seek = nn?.now?.let { p ->
        val span = p.startMs..p.stopMs
        fun fraction(t: Long) = ((t - span.first).toFloat() / (span.last - span.first).coerceAtLeast(1)).coerceIn(0f, 1f)
        PhoneSeek(
            nn.progress(now).coerceIn(0f, 1f),
            vm.scrubMs?.let(::fraction),
            formatTime(p.startMs),
            formatTime(p.stopMs),
            if (ch.archiveDays > 0) ({ f -> vm.scrubTo(span, f) }) else null
        )
    }
    val name = listOf(ch.numberLabel, ch.name).filter { it.isNotEmpty() }.joinToString(" · ")
    PhoneControls(vm, name, nn?.now?.let { programmeTitle(it.title) }, seek, live = true) {
        PhonePill(stringResource(R.string.player_channel_guide), onClick = vm::openGuide)
        if (ch.archiveDays > 0) {
            PhonePill(stringResource(R.string.common_start_over)) {
                nav.replace(Screen.CatchupPlayer(ch.playlistId, ch.streamId, filter = vm.filter, kinds = vm.kinds))
            }
        }
    }
}

/** Catch-up: the channel, the programme and how far behind live it is, the skip buttons and the programme's play bar. */
@Composable
internal fun PhoneCatchupControls(vm: CatchupPlayerViewModel) {
    val ch = vm.channel ?: return
    // While skipping: the programme and time playback will go to.
    val scrub = vm.scrubMs
    val at = scrub ?: vm.at
    val p = scrub?.let(vm::programmeAt) ?: vm.programme
    val behind = ((System.currentTimeMillis() - at) / 1000).toInt()
    val seek = p?.let {
        fun fraction(t: Long) = ((t - p.startMs).toFloat() / (p.stopMs - p.startMs).coerceAtLeast(1)).coerceIn(0f, 1f)
        PhoneSeek(fraction(vm.at), scrub?.let(::fraction), formatTime(p.startMs), formatTime(p.stopMs)) { f ->
            vm.scrubTo(p.startMs..p.stopMs, f)
        }
    }
    val line = listOfNotNull(
        p?.let { programmeTitle(it.title) },
        stringResource(R.string.player_catchup_behind_live, formatRuntime(behind))
    ).joinToString(" · ")
    val name = listOf(ch.numberLabel, ch.name).filter { it.isNotEmpty() }.joinToString(" · ")
    PhoneControls(vm, name, line, seek, live = false) {
        PhonePill(stringResource(R.string.player_channel_guide), onClick = vm::openGuide)
        PhonePill(stringResource(R.string.common_from_start), onClick = vm::fromStart)
        PhonePill(stringResource(R.string.player_watch_live), onClick = vm::watchLive)
    }
}

/**
 * The phone overlay over the video, as the approved mock-up has it: Back, the [title] and [subtitle], the VPN and proxy
 * statuses and the clock along the top; the skip buttons ([live]: the channel buttons) around play/pause in the
 * middle; the play bar ([seek]) and the pill buttons at the bottom: Audio, Subtitles, [pills], More, and [trailing] at
 * the right-hand end.
 */
@Composable
private fun PhoneControls(
    vm: PlaybackViewModel,
    title: String,
    subtitle: String?,
    seek: PhoneSeek?,
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
                seek?.let { SeekRow(it) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PhonePill(stringResource(R.string.player_audio)) { vm.sheet = PlaybackViewModel.Sheet.AUDIO }
                    PhonePill(stringResource(R.string.player_subtitles)) { vm.sheet = PlaybackViewModel.Sheet.SUBTITLES }
                    pills()
                    PhonePill(stringResource(R.string.player_more)) { vm.sheet = PlaybackViewModel.Sheet.MORE }
                    Spacer(Modifier.weight(1f))
                    trailing()
                }
            }
        }
    }
}

/** Back (it stops playback, as Back does on a TV), the title, the statuses the TV's clock panel shows, and the clock. */
@Composable
private fun TopRow(vm: PlaybackViewModel, title: String, subtitle: String?) {
    val container = LocalContext.current.container
    val settings by remember { container.settings.settings }.collectAsState(initial = null)
    Row(
        Modifier.fillMaxWidth().onPlainTap {}.padding(start = 12.dp, end = 24.dp, top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            Modifier.size(TOUCH).clip(CircleShape).clickable(role = Role.Button, onClick = vm::stop),
            contentAlignment = Alignment.Center
        ) {
            Image(AppIcons.Back, stringResource(R.string.player_back), Modifier.size(24.dp), colorFilter = ColorFilter.tint(Palette.text))
        }
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
        rememberVpn(settings)?.let { VpnStatus(it, StatusLook.Player) }
        rememberProxy(settings)?.let { ProxyStatus(it, StatusLook.Player) }
        val now by rememberMinuteClock()
        Text(formatTime(now), color = Palette.text, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
    }
}

/** Play/pause, larger and in the accent colour, between skip back and forward (or, on a live channel, the channel buttons). */
@Composable
private fun MiddleButtons(vm: PlaybackViewModel, live: Boolean, modifier: Modifier) {
    val container = LocalContext.current.container
    val settings by remember { container.settings.settings }.collectAsState(initial = null)
    val accent = LocalAccent.current.color
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(56.dp)) {
        if (live) {
            RoundButton(ChannelUpIcon, stringResource(R.string.phone_player_previous_channel)) { vm.onKey(RemoteKey.CHANNEL_UP) }
        } else {
            RoundButton(SkipBackIcon, stringResource(R.string.common_skip_back), settings?.skipBackSec?.toString()) {
                vm.onKey(RemoteKey.LEFT)
            }
        }
        RoundButton(
            if (vm.isPlaying) AppIcons.Pause else AppIcons.Play,
            stringResource(if (vm.isPlaying) R.string.player_pause else R.string.common_play),
            size = 76.dp,
            color = accent,
            tint = Palette.background
        ) { vm.togglePlayPause() }
        if (live) {
            RoundButton(ChannelDownIcon, stringResource(R.string.phone_player_next_channel)) { vm.onKey(RemoteKey.CHANNEL_DOWN) }
        } else {
            RoundButton(SkipForwardIcon, stringResource(R.string.common_skip_forward), settings?.skipForwardSec?.toString()) {
                vm.onKey(RemoteKey.RIGHT)
            }
        }
    }
}

/** A round button with [icon], read out as [label], and a small [number] under the icon (the skip's seconds). */
@Composable
private fun RoundButton(
    icon: ImageVector,
    label: String,
    number: String? = null,
    size: Dp = 60.dp,
    color: Color = SideButton,
    tint: Color = Palette.text,
    onClick: () -> Unit
) {
    Column(
        Modifier.size(size).clip(CircleShape).background(color).clickable(onClickLabel = label, role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(icon, label, Modifier.size(if (number == null) 30.dp else 22.dp), colorFilter = ColorFilter.tint(tint))
        number?.let { Text(it, color = tint, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold) }
    }
}

/** A pill button, at least 44 dp high: dark, or [light] for the button that stands out (Next episode). */
@Composable
private fun PhonePill(label: String, light: Boolean = false, icon: ImageVector? = null, onClick: () -> Unit) {
    val text = if (light) Palette.background else Palette.text
    Row(
        Modifier.heightIn(min = TOUCH).clip(RoundedCornerShape(50)).background(if (light) Palette.text else PillColour)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = if (light) 16.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(label, color = text, fontSize = 13.sp, fontWeight = if (light) FontWeight.Black else FontWeight.ExtraBold, maxLines = 1)
        icon?.let { Image(it, null, Modifier.size(16.dp), colorFilter = ColorFilter.tint(text)) }
    }
}

/** The times either side of the play bar. */
@Composable
private fun SeekRow(seek: PhoneSeek) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TimeText(seek.start)
        PhoneSeekBar(seek, Modifier.weight(1f))
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
 * shows progress has none). It takes touches across a strip 44 dp high: a tap or a drag picks where to play from.
 */
@Composable
private fun PhoneSeekBar(seek: PhoneSeek, modifier: Modifier) {
    val accent = LocalAccent.current.color
    val onSeek by rememberUpdatedState(seek.onSeek)
    val seekable = seek.onSeek != null
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
    val at = (seek.scrub ?: seek.fraction).coerceIn(0f, 1f)
    Canvas(modifier.height(TOUCH).then(input)) {
        val line = 4.dp.toPx()
        val y = size.height / 2
        val radius = CornerRadius(line / 2)
        drawRoundRect(Color.White.copy(alpha = 0.25f), Offset(0f, y - line / 2), Size(size.width, line), radius)
        drawRoundRect(accent, Offset(0f, y - line / 2), Size(size.width * seek.fraction.coerceIn(0f, 1f), line), radius)
        if (seekable) drawCircle(Palette.text, radius = 9.dp.toPx(), center = Offset(size.width * at, y))
    }
}

/** The smallest touch target. */
private val TOUCH = 44.dp

private val SideButton = Color(0x59000000)
private val PillColour = Color(0xD922262D)

/** Dark at the top and the bottom behind the text and buttons, clear in the middle over the picture. */
private val Shade = Brush.verticalGradient(
    0f to Color.Black.copy(alpha = 0.65f),
    0.3f to Color.Transparent,
    0.62f to Color.Transparent,
    1f to Color.Black.copy(alpha = 0.75f)
)

private fun icon(name: String, fill: Boolean, block: PathBuilder.() -> Unit) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
    if (fill) {
        path(fill = SolidColor(Color.White), pathBuilder = block)
    } else {
        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2.2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block
        )
    }
}.build()

/** An arrow turning back round a circle. */
private val SkipBackIcon = icon("skip_back", false) {
    moveTo(3f, 12f)
    arcToRelative(9f, 9f, 0f, true, false, 3f, -6.7f)
    lineTo(3f, 8f)
    moveTo(3f, 3f)
    verticalLineToRelative(5f)
    horizontalLineToRelative(5f)
}

/** An arrow turning forward round a circle. */
private val SkipForwardIcon = icon("skip_forward", false) {
    moveTo(21f, 12f)
    arcToRelative(9f, 9f, 0f, true, true, -3f, -6.7f)
    lineTo(21f, 8f)
    moveTo(21f, 3f)
    verticalLineToRelative(5f)
    horizontalLineToRelative(-5f)
}

private val ChannelUpIcon = icon("channel_up", false) {
    moveTo(6f, 15f)
    lineTo(12f, 9f)
    lineTo(18f, 15f)
}

private val ChannelDownIcon = icon("channel_down", false) {
    moveTo(6f, 9f)
    lineTo(12f, 15f)
    lineTo(18f, 9f)
}

/** Play, then a bar: the next episode. */
private val NextIcon = icon("next", true) {
    moveTo(6f, 5f)
    verticalLineToRelative(14f)
    lineToRelative(9f, -7f)
    close()
    moveTo(16f, 5f)
    horizontalLineToRelative(3f)
    verticalLineToRelative(14f)
    horizontalLineToRelative(-3f)
    close()
}
