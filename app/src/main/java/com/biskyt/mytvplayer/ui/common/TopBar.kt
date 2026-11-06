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
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.container
import com.biskyt.mytvplayer.data.ChannelFilter
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
    val playlists by remember { container.playlists.observePlaylists() }.collectAsState(initial = emptyList())
    val active = playlists.firstOrNull { it.id == settings?.activePlaylistId } ?: playlists.firstOrNull()

    // Catch-up only for playlists that have some.
    val hasCatchup by remember(active?.id) { active?.let { container.channels.observeHasCatchup(it.id) } ?: flowOf(false) }
        .collectAsState(initial = false)
    Row(
        // The board's 40 and 22, less the room each item keeps around its text for its focus fill.
        Modifier.fillMaxWidth().height(48.bd).padding(horizontal = 40.bd - BAR_PAD_H),
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
        settings?.takeIf { it.warnVpn }?.let { VpnStatus(showIp = it.showExternalIp) }
        Clock(Modifier.padding(horizontal = BAR_PAD_H, vertical = BAR_PAD_V), BarText.current, Palette.textDim)
        BarItem({ nav.open(Screen.Search()) }) { BarIcon(AppIcons.Search, stringResource(R.string.common_search)) }
        BarItem({ nav.open(Screen.Settings) }) { BarIcon(AppIcons.Settings, stringResource(R.string.common_settings)) }
        if (active != null) {
            BarItem({ nav.open(Screen.Playlists) }) {
                // On the accent fill, the initial's colours swap, so its circle still shows.
                val focused = LocalCardFocused.current
                val circle = if (focused) accent.onAccent else accent.color
                val initial = if (focused) accent.color else accent.onAccent
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.bd)) {
                    Box(Modifier.size(20.bd).clip(CircleShape).background(circle), contentAlignment = Alignment.Center) {
                        Text(active.name.take(1).uppercase(), style = InitialText.current, color = initial)
                    }
                    Text(active.name, style = BarText.current, color = barContent(Palette.textMuted), maxLines = 1)
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

/** A top bar item's text or icon colour: [normal], or the accent's text colour on its focus fill. */
@Composable
private fun barContent(normal: Color) = if (LocalCardFocused.current) LocalAccent.current.onAccent else normal

/** A tab's name: the one shown in the accent colour, with a line under it. */
@Composable
private fun TabLabel(name: String, current: Boolean) {
    val colour = barContent(if (current) LocalAccent.current.color else Palette.textDim)
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
    Icon(icon, contentDescription = label, tint = barContent(Palette.textDim), modifier = Modifier.size(18.bd))

/**
 * A top bar item: drawn as it is, on nothing. With focus, it is a pill filled with the accent colour, with no ring, as
 * the film and series pages' buttons are; its content reads that from [LocalCardFocused] (see [barContent]).
 */
@Composable
private fun BarItem(onClick: () -> Unit, content: @Composable () -> Unit) {
    TvCard(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = Color.Transparent,
        focusedColor = LocalAccent.current.color,
        ring = false
    ) {
        Box(Modifier.padding(horizontal = BAR_PAD_H, vertical = BAR_PAD_V)) { content() }
    }
}

/**
 * Whether a VPN is connected (setting "Warn if VPN is not connected"): a shield and "VPN on" when it is, today's
 * warning pill when not. With [showIp], also the public IP address and its country, once known, on a second line.
 */
@Composable
private fun VpnStatus(showIp: Boolean) {
    val container = LocalContext.current.container
    val connected by container.vpn.connected.collectAsState()
    val ip = if (showIp) container.externalIp.current.collectAsState().value else null
    val accent = LocalAccent.current.color
    val text = if (connected) Palette.textDim else Palette.background
    // The address goes on a second, smaller line, so the status is no wider.
    val vertical = if (ip == null) 8.dp else 3.dp
    Row(
        if (connected) {
            Modifier.padding(horizontal = BAR_PAD_H, vertical = BAR_PAD_V)
        } else {
            // The same room beside it as the other items have, so the bar is spaced alike either way.
            Modifier.padding(horizontal = BAR_PAD_H).clip(RoundedCornerShape(50)).background(Palette.warning)
                .padding(start = 10.dp, end = 14.dp, top = vertical, bottom = vertical)
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.bd)
    ) {
        // Connected: as the boards draw it. Not connected: the warning pill as before 1.12.2.
        Icon(
            AppIcons.Shield,
            contentDescription = null,
            tint = if (connected) accent else text,
            modifier = Modifier.size(if (connected) 14.bd else 20.dp)
        )
        Column {
            Text(
                stringResource(if (connected) R.string.common_vpn_on else R.string.common_vpn_off),
                style = if (connected) VpnText.current else MaterialTheme.typography.labelMedium,
                color = text
            )
            ip?.let {
                Text(
                    it.country?.let { country -> stringResource(R.string.common_ip_country, it.ip, country) } ?: it.ip,
                    style = MaterialTheme.typography.labelSmall.copy(color = text),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // A long IPv6 address is cut, not let to widen the bar.
                    modifier = Modifier.widthIn(max = IP_WIDTH)
                )
            }
        }
    }
}

private val IP_WIDTH = 150.dp

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
