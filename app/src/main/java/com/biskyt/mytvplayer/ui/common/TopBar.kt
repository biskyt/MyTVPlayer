package com.biskyt.mytvplayer.ui.common

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.container
import com.biskyt.mytvplayer.core.net.PublicIp
import com.biskyt.mytvplayer.data.ChannelFilter
import com.biskyt.mytvplayer.data.Settings
import com.biskyt.mytvplayer.ui.LocalNavigator
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.theme.BoardStyle
import com.biskyt.mytvplayer.ui.theme.LocalAccent
import com.biskyt.mytvplayer.ui.theme.Palette
import com.biskyt.mytvplayer.ui.theme.bd
import kotlinx.coroutines.flow.flowOf

enum class Tab(@param:StringRes val label: Int) {
    HOME(R.string.common_tab_home),
    LIVE(R.string.common_tab_live),
    SERIES(R.string.common_tab_series),
    MOVIES(R.string.common_tab_movies),
    CATCHUP(R.string.common_catchup)
}

/**
 * The top bar, as the design boards draw it (2026-11-05): the tabs as text, with the one shown in the accent colour
 * and underlined; then the VPN status, the clock, Search, Settings and the playlist switcher. The item with focus is
 * filled with the accent colour, with no ring, as the film and series pages' buttons are.
 */
@Composable
fun TopBar(selected: Tab?) {
    val nav = LocalNavigator.current
    val accent = LocalAccent.current
    val container = LocalContext.current.container
    // remember: a new Room flow on each recomposition would re-run the query and briefly show nothing.
    val settings by remember { container.settings.settings }.collectAsState(initial = null)
    val active = remember { container.playlists.observeActive() }.collectAsState(initial = null).value

    // Catch-up only for playlists that have some.
    val hasCatchup by remember(active?.id) { active?.let { container.channels.observeHasCatchup(it.id) } ?: flowOf(false) }
        .collectAsState(initial = false)
    val screen = LocalScreenFocus.current
    Row(
        // The board's 40 and 22, less the room each item keeps around its text for its focus fill.
        Modifier.fillMaxWidth().height(48.bd).padding(horizontal = 40.bd - BAR_PAD_H)
            // So that a screen given focus with none (see KeepsFocus) moves it on into its content.
            .onFocusChanged { screen?.inTopBar = it.hasFocus },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BAR_GAP)
    ) {
        Tab.entries.filter { it != Tab.CATCHUP || hasCatchup }.forEach { tab ->
            BarItem({
                nav.reset(
                    when (tab) {
                        Tab.HOME -> Screen.Home
                        Tab.LIVE -> Screen.Live()
                        Tab.MOVIES -> Screen.Movies()
                        Tab.SERIES -> Screen.Series()
                        Tab.CATCHUP -> Screen.Live(ChannelFilter.Catchup)
                    }
                )
            }) { TabLabel(stringResource(tab.label), tab == selected) }
        }
        Box(Modifier.weight(1f))
        rememberVpn(settings)?.let { VpnStatus(it, VpnLook.Bar, Modifier.padding(horizontal = BAR_PAD_H, vertical = BAR_PAD_V)) }
        Clock(Modifier.padding(horizontal = BAR_PAD_H, vertical = BAR_PAD_V), BarText.current, Palette.textDim)
        BarItem({ nav.open(Screen.Search()) }) { BarIcon(AppIcons.Search, stringResource(R.string.common_search)) }
        BarItem({ nav.open(Screen.Settings) }) { BarIcon(AppIcons.Settings, stringResource(R.string.common_settings)) }
        if (active != null) {
            BarItem({ nav.open(Screen.Playlists) }) {
                // On the accent fill, the initial's colours swap, so its circle still shows.
                val focused = LocalCardFilled.current
                val circle = if (focused) accent.onAccent else accent.color
                val initial = if (focused) accent.color else accent.onAccent
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.bd)) {
                    Box(Modifier.size(20.bd).clip(CircleShape).background(circle), contentAlignment = Alignment.Center) {
                        Text(active.name.take(1).uppercase(), style = InitialText.current, color = initial)
                    }
                    Text(active.name, style = BarText.current, color = cardColor(Palette.textMuted), maxLines = 1)
                }
            }
        }
    }
}

/** The tabs' and the clock's text: 14, bold, muted. */
private val BarText = BoardStyle(14, 700)
private val InitialText = BoardStyle(11, 800)
private val VpnText = BoardStyle(13, 700)

/** Room around a top bar item's text for its focus fill, taken back from the gap between items. */
private val BAR_PAD_H = 10.bd
private val BAR_PAD_V = 4.bd
private val BAR_GAP = 22.bd - BAR_PAD_H * 2

/** A tab's name: the one shown in the accent colour, with a line under it. */
@Composable
private fun TabLabel(name: String, current: Boolean) {
    val colour = cardColor(if (current) LocalAccent.current.color else Palette.textDim)
    Text(
        name,
        style = BarText.current,
        color = colour,
        maxLines = 1,
        // Every tab has the line's room, so they all line up; only the current one draws it.
        modifier = Modifier.drawBehind {
            if (current) drawRect(colour, Offset(0f, size.height - 2.bd.toPx()), Size(size.width, 2.bd.toPx()))
        }.padding(bottom = 3.bd)
    )
}

@Composable
private fun BarIcon(icon: ImageVector, label: String) =
    Icon(icon, contentDescription = label, tint = cardColor(Palette.textDim), modifier = Modifier.size(18.bd))

/**
 * A top bar item: drawn as it is, on nothing. With focus, it is a pill filled with the accent colour, with no ring, as
 * the film and series pages' buttons are; its content takes the accent's text colour then (see [cardColor]).
 */
@Composable
private fun BarItem(onClick: () -> Unit, content: @Composable () -> Unit) {
    TvCard(onClick = onClick, shape = RoundedCornerShape(50), color = Color.Transparent, fill = true) {
        Box(Modifier.padding(horizontal = BAR_PAD_H, vertical = BAR_PAD_V)) { content() }
    }
}

/** What [VpnStatus] shows: whether a VPN is connected, and the public address once known (with its setting). */
data class Vpn(val connected: Boolean, val ip: PublicIp?)

/**
 * With the setting "Warn if VPN is not connected" on, whether a VPN is connected, and with "Show your external IP
 * address" also the address; null with it off, and then nothing is watched or asked.
 */
@Composable
fun rememberVpn(settings: Settings?): Vpn? {
    if (settings?.warnVpn != true) return null
    val container = LocalContext.current.container
    val connected by container.vpn.connected.collectAsState()
    val ip = if (settings.showExternalIp) container.externalIp.current.collectAsState().value else null
    return Vpn(connected, ip)
}

/**
 * How [VpnStatus] is drawn: in the top bar, or larger over the video next to the player's clock. Not connected, it has
 * its own label and icon sizes ([offLabel], [offIcon]), on a warning pill ([offPill]) or in the warning colour.
 */
class VpnLook(
    val label: @Composable () -> TextStyle,
    val offLabel: @Composable () -> TextStyle,
    val ipText: @Composable () -> TextStyle,
    val connectedText: Color,
    val icon: Dp,
    val offIcon: Dp,
    val gap: Dp,
    val ipWidth: Dp,
    val offPill: Boolean
) {
    companion object {
        val Bar = VpnLook(
            label = { VpnText.current },
            offLabel = { MaterialTheme.typography.labelMedium },
            ipText = { MaterialTheme.typography.labelSmall },
            connectedText = Palette.textDim,
            icon = 14.bd,
            offIcon = 20.dp,
            gap = 4.bd,
            // A long IPv6 address is cut, not let to widen the bar.
            ipWidth = 150.dp,
            offPill = true
        )

        /** Next to the player's clock (the owner, 2026-11-12). */
        val Player = VpnLook(
            label = { MaterialTheme.typography.titleMedium },
            offLabel = { MaterialTheme.typography.titleMedium },
            ipText = { MaterialTheme.typography.labelMedium },
            connectedText = Palette.text,
            icon = 22.dp,
            offIcon = 22.dp,
            gap = 6.dp,
            ipWidth = 240.dp,
            // Already on the clock's dark panel: a pill in it would be a frame in a frame.
            offPill = false
        )
    }
}

/**
 * Whether a VPN is connected: a shield and "VPN on" when it is, the warning pill when not. With the address, also it
 * and its country on a second line.
 */
@Composable
fun VpnStatus(vpn: Vpn, look: VpnLook, modifier: Modifier = Modifier) {
    val connected = vpn.connected
    val ip = vpn.ip
    val accent = LocalAccent.current.color
    val pill = !connected && look.offPill
    val text = when {
        connected -> look.connectedText
        pill -> Palette.background
        else -> Palette.warning
    }
    // The address goes on a second, smaller line, so the status is no wider.
    val vertical = if (ip == null) 8.dp else 3.dp
    Row(
        if (pill) {
            modifier.clip(RoundedCornerShape(50)).background(Palette.warning)
                .padding(start = 10.dp, end = 14.dp, top = vertical, bottom = vertical)
        } else {
            modifier
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(look.gap)
    ) {
        // Connected: as the boards draw it. Not connected: the warning pill as before 1.12.2.
        Icon(
            AppIcons.Shield,
            contentDescription = null,
            tint = if (connected) accent else text,
            modifier = Modifier.size(if (connected) look.icon else look.offIcon)
        )
        Column {
            Text(
                stringResource(if (connected) R.string.common_vpn_on else R.string.common_vpn_off),
                style = if (connected) look.label() else look.offLabel(),
                color = text
            )
            ip?.let {
                Text(
                    it.country?.let { country -> stringResource(R.string.common_ip_country, it.ip, country) } ?: it.ip,
                    style = look.ipText().copy(color = text),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = look.ipWidth)
                )
            }
        }
    }
}

/** The time of day, updated each minute: in the top bar, and over the video while the player's controls show. */
@Composable
fun Clock(
    modifier: Modifier = Modifier.padding(horizontal = 8.dp),
    style: TextStyle = MaterialTheme.typography.titleSmall,
    color: Color = Palette.textMuted
) {
    val now by rememberMinuteClock()
    Text(formatTime(now), style = style, color = color, modifier = modifier)
}

/**
 * Between the top bar and the screen below it: page content starts 70 board pixels down (see TopBar). The boards say
 * 76; the owner asked for half way between that and the gap before them (2026-11-05).
 */
val TabScaffoldGap = 22.bd

/** On a page drawn behind the top bar (its art showing through), how far down its content must start: 0 without. */
val LocalTopBarInset = compositionLocalOf { 0.dp }

/** Screen with the top bar above [content]. */
@Composable
fun TabScaffold(tab: Tab?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    // (On the app's background, painted once below every screen.)
    Column(modifier.fillMaxSize()) {
        TopBar(tab)
        Column(Modifier.padding(top = TabScaffoldGap).fillMaxSize(), content = content)
    }
}

/**
 * A text box that only opens the keyboard when selected, so D-pad focus can pass over it.
 * [editing] can be hoisted so a form can open its next field straight from the keyboard's Next key ([onNext]).
 * [autoCorrect] is off for addresses, user names and passwords, where "corrections" break what was typed.
 * Everything is shown as typed, passwords included: they are entered with a remote and need checking.
 */
@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    autoCorrect: Boolean = keyboardType == KeyboardType.Text,
    leading: (@Composable () -> Unit)? = null,
    editing: MutableState<Boolean> = rememberSaveable { mutableStateOf(false) },
    onNext: (() -> Unit)? = null,
    onDone: () -> Unit = {}
) {
    val focus = remember { FocusRequester() }
    val cardFocus = remember { FocusRequester() }
    var refocusCard by remember { mutableStateOf(false) }
    TvCard(onClick = { editing.value = true }, modifier = modifier.focusRequester(cardFocus), color = Palette.surface) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) {
                leading()
                Box(Modifier.size(10.dp))
            }
            if (editing.value) {
                var hadFocus by remember { mutableStateOf(false) }
                val keyboard = LocalSoftwareKeyboardController.current
                val focusManager = LocalFocusManager.current
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge,
                    cursorBrush = SolidColor(LocalAccent.current.color),
                    keyboardOptions = KeyboardOptions(
                        autoCorrectEnabled = autoCorrect,
                        keyboardType = keyboardType,
                        imeAction = if (onNext != null) ImeAction.Next else ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = {
                            editing.value = false
                            onNext?.invoke()
                        },
                        onDone = {
                            editing.value = false
                            refocusCard = true
                            onDone()
                        },
                    ),
                    modifier = Modifier.weight(1f).focusRequester(focus).onPreviewKeyEvent { e ->
                        // One line has no use for Up and Down. Some TVs (a Chromecast with Google TV) close the
                        // keyboard and leave the box focused: these keys then leave the box, as on other TVs.
                        val direction = when (e.key) {
                            Key.DirectionUp -> FocusDirection.Up
                            Key.DirectionDown -> FocusDirection.Down
                            else -> null
                        }
                        if (direction != null && e.type == KeyEventType.KeyDown && !focusManager.moveFocus(direction)) {
                            editing.value = false
                            refocusCard = true
                        }
                        direction != null
                    }.onFocusChanged {
                        // Leaving the field (e.g. pressing Back or moving away) ends editing.
                        if (it.isFocused) {
                            hadFocus = true
                        } else if (hadFocus) {
                            editing.value = false
                        }
                    }
                )
                LaunchedEffect(Unit) {
                    focus.requestFocus()
                    keyboard?.show()
                }
            } else {
                Text(
                    value.ifEmpty { placeholder },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (value.isEmpty()) Palette.textDim else Palette.text,
                    maxLines = 1
                )
            }
        }
    }
    if (refocusCard) {
        // After Done, focus stays on this box rather than jumping elsewhere.
        LaunchedEffect(Unit) {
            runCatching { cardFocus.requestFocus() }
            refocusCard = false
        }
    }
}
