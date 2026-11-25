package com.biskyt.mytvplayer.ui.phone

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.container
import com.biskyt.mytvplayer.data.ChannelFilter
import com.biskyt.mytvplayer.data.ChannelKinds
import com.biskyt.mytvplayer.data.RadioMode
import com.biskyt.mytvplayer.ui.LocalNavigator
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.common.AppIcons
import com.biskyt.mytvplayer.ui.common.Tab
import com.biskyt.mytvplayer.ui.theme.FormFactor
import com.biskyt.mytvplayer.ui.theme.LocalAccent
import com.biskyt.mytvplayer.ui.theme.LocalFormFactor
import com.biskyt.mytvplayer.ui.theme.Palette
import kotlinx.coroutines.flow.flowOf

// The phone's frame (phone spike, 2026-11-25): a tab bar along the bottom on a phone, a rail down the side when a
// foldable is unfolded, in place of the TV's top bar. Plain Compose foundation: no Material library is added.

/**
 * A browsing screen on a phone: [header] (a title and its buttons) over [content], with the tab bar ([tab] the
 * current one) at the bottom, or the rail at the side when unfolded.
 */
@Composable
fun PhoneScaffold(tab: Tab?, title: String, actions: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    val unfolded = LocalFormFactor.current == FormFactor.UNFOLDED
    val page: @Composable () -> Unit = {
        Column(Modifier.fillMaxSize()) {
            PhoneHeader(title, actions)
            Column(Modifier.weight(1f).fillMaxWidth(), content = content)
        }
    }
    if (unfolded) {
        Row(Modifier.fillMaxSize()) {
            PhoneTabs(tab, rail = true)
            Box(Modifier.weight(1f).fillMaxHeight()) { page() }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) { page() }
            PhoneTabs(tab, rail = false)
        }
    }
}

/** A screen's title, large, with its buttons on the right. */
@Composable
fun PhoneHeader(title: String, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            title,
            color = Palette.text,
            fontSize = 26.sp,
            fontWeight = FontWeight.Black,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        actions()
    }
}

/** A round 44 dp button with [icon], read out as [label]. */
@Composable
fun PhoneIconButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).background(Palette.surfaceHigh)
            .clickable(onClickLabel = label, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Image(icon, label, Modifier.size(20.dp), colorFilter = ColorFilter.tint(Palette.text))
    }
}

/** The tabs, as the TV's top bar has them, plus Search (and Settings on the rail). */
@Composable
private fun PhoneTabs(selected: Tab?, rail: Boolean) {
    val nav = LocalNavigator.current
    val container = LocalContext.current.container
    val settings by remember { container.settings.settings }.collectAsState(initial = null)
    val active = remember { container.playlists.observeActive() }.collectAsState(initial = null).value
    val kinds = ChannelKinds.shown(settings?.radio)
    val hasCatchup by remember(active?.id, kinds) { active?.let { container.channels.observeHasCatchup(it.id, kinds) } ?: flowOf(false) }
        .collectAsState(initial = false)
    val items = buildList {
        add(PhoneTab(Tab.HOME, AppIcons.Home, stringResource(R.string.common_tab_home)) { nav.reset(Screen.Home) })
        add(PhoneTab(Tab.LIVE, AppIcons.Tv, stringResource(R.string.common_tab_live)) { nav.reset(Screen.Live()) })
        if (settings?.radio == RadioMode.SECTION) {
            add(PhoneTab(Tab.RADIO, AppIcons.Tv, stringResource(R.string.common_tab_radio)) { nav.reset(Screen.Live(radio = true)) })
        }
        if (rail && hasCatchup) {
            add(
                PhoneTab(Tab.CATCHUP, AppIcons.Catchup, stringResource(R.string.common_catchup)) {
                    nav.reset(Screen.Live(ChannelFilter.Catchup))
                }
            )
        }
        add(PhoneTab(Tab.MOVIES, AppIcons.Film, stringResource(R.string.common_tab_movies)) { nav.reset(Screen.Movies()) })
        add(PhoneTab(Tab.SERIES, AppIcons.Stack, stringResource(R.string.common_tab_series)) { nav.reset(Screen.Series()) })
        add(PhoneTab(null, AppIcons.Search, stringResource(R.string.common_search)) { nav.open(Screen.Search()) })
    }
    if (rail) {
        Column(
            Modifier.width(88.dp).fillMaxHeight().background(Palette.surfaceLow).padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items.forEach { TabItem(it, it.tab != null && it.tab == selected, Modifier.fillMaxWidth()) }
            Spacer(Modifier.weight(1f))
            TabItem(
                PhoneTab(null, AppIcons.Settings, stringResource(R.string.common_settings)) { nav.open(Screen.Settings) },
                false,
                Modifier.fillMaxWidth()
            )
        }
    } else {
        Row(Modifier.fillMaxWidth().background(Palette.surfaceLow).padding(horizontal = 4.dp, vertical = 4.dp)) {
            items.forEach { TabItem(it, it.tab != null && it.tab == selected, Modifier.weight(1f)) }
        }
    }
}

private class PhoneTab(val tab: Tab?, val icon: ImageVector, val label: String, val open: () -> Unit)

@Composable
private fun TabItem(item: PhoneTab, current: Boolean, modifier: Modifier) {
    val colour = if (current) LocalAccent.current.color else Palette.textDim
    Column(
        modifier.heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Tab, onClick = item.open).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically)
    ) {
        Image(item.icon, null, Modifier.size(24.dp), colorFilter = ColorFilter.tint(colour))
        Text(
            item.label,
            color = colour,
            fontSize = 11.sp,
            fontWeight = if (current) FontWeight.ExtraBold else FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Colours the phone screens share. */
internal val PhoneCard = Color(0xFF181B20)
internal val PhoneChip = Color(0xFF22262D)
internal val PhoneSpacer = 8.dp
internal val PhoneRowHeight = Modifier.height(72.dp)
