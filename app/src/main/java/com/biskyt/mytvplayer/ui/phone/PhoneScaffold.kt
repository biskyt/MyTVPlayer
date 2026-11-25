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
import com.biskyt.mytvplayer.ui.LocalNavigator
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.common.AppIcons
import com.biskyt.mytvplayer.ui.common.ProxyStatus
import com.biskyt.mytvplayer.ui.common.StatusLook
import com.biskyt.mytvplayer.ui.common.Tab
import com.biskyt.mytvplayer.ui.common.VpnStatus
import com.biskyt.mytvplayer.ui.common.rememberProxy
import com.biskyt.mytvplayer.ui.common.rememberShownTabs
import com.biskyt.mytvplayer.ui.common.rememberVpn
import com.biskyt.mytvplayer.ui.common.screen
import com.biskyt.mytvplayer.ui.theme.FormFactor
import com.biskyt.mytvplayer.ui.theme.LocalAccent
import com.biskyt.mytvplayer.ui.theme.LocalFormFactor
import com.biskyt.mytvplayer.ui.theme.Palette

// The phone's frame (the owner, 2026-11-25): a tab bar along the bottom on a phone, a rail down the side when a
// foldable is unfolded, in place of the TV's top bar. Plain Compose foundation: no Material library is added.

/**
 * A browsing screen on a phone: [header] (a title and its buttons) over [content], with the tab bar ([tab] the
 * current one) at the bottom, or the rail at the side when unfolded.
 */
@Composable
fun PhoneScaffold(tab: Tab?, title: String, actions: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    val container = LocalContext.current.container
    // remember: a new Room flow on each recomposition would re-run the query and briefly show nothing.
    val settings by remember { container.settings.settings }.collectAsState(initial = null)
    PhoneTabFrame(tab) {
        Column(Modifier.fillMaxSize()) {
            PhoneHeader(title) {
                // The statuses the TV's top bar shows, small.
                rememberVpn(settings)?.let { VpnStatus(it, StatusLook.Phone) }
                rememberProxy(settings)?.let { ProxyStatus(it, StatusLook.Phone) }
                actions()
            }
            Column(Modifier.weight(1f).fillMaxWidth(), content = content)
        }
    }
}

/**
 * [page] with the tabs, [tab] the current one: the tab bar at the bottom on a phone, or the rail at the side when
 * unfolded. The browsing screens have it (see [PhoneScaffold]), and a film's or series' page (the owner, 2026-11-25).
 */
@Composable
fun PhoneTabFrame(tab: Tab?, page: @Composable () -> Unit) {
    val unfolded = LocalFormFactor.current == FormFactor.UNFOLDED
    val container = LocalContext.current.container
    val settings by remember { container.settings.settings }.collectAsState(initial = null)
    val active = remember { container.playlists.observeActive() }.collectAsState(initial = null).value
    val tabs = rememberShownTabs(settings, active?.id)
    if (unfolded) {
        Row(Modifier.fillMaxSize()) {
            PhoneTabs(tabs, tab, rail = true)
            Box(Modifier.weight(1f).fillMaxHeight()) { page() }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) { page() }
            PhoneTabs(tabs, tab, rail = false)
        }
    }
}

/** A screen's title, large, with its buttons on the right. */
@Composable
fun PhoneHeader(title: String, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = PhoneGutter, end = 12.dp, top = 14.dp, bottom = 6.dp),
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

/** The tabs, as the TV's top bar has them ([tabs]: see rememberShownTabs), plus Search (and Settings on the rail). */
@Composable
private fun PhoneTabs(tabs: List<Tab>, selected: Tab?, rail: Boolean) {
    val nav = LocalNavigator.current
    val items = tabs.map { tab -> PhoneTab(tab, tab.icon, stringResource(tab.label)) { nav.reset(tab.screen) } } +
        PhoneTab(null, AppIcons.Search, stringResource(R.string.common_search)) { nav.open(Screen.Search()) }
    if (rail) {
        Column(
            Modifier.width(PhoneRailWidth).fillMaxHeight().background(Palette.surfaceLow).padding(vertical = 12.dp),
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
        Row(
            Modifier.fillMaxWidth().heightIn(
                min = PhoneTabBarHeight
            ).background(Palette.surfaceLow).padding(horizontal = 4.dp, vertical = 4.dp)
        ) {
            items.forEach { TabItem(it, it.tab != null && it.tab == selected, Modifier.weight(1f)) }
        }
    }
}

/** The tab bar's height on a phone: a tab's 56 dp and the bar's padding. */
internal val PhoneTabBarHeight = 64.dp

/** The rail's width on an unfolded screen. */
internal val PhoneRailWidth = 88.dp

/** A tab's icon in the tab bar. */
private val Tab.icon: ImageVector
    get() = when (this) {
        Tab.HOME -> AppIcons.Home
        Tab.LIVE, Tab.RADIO -> AppIcons.Tv
        Tab.SERIES -> AppIcons.Stack
        Tab.MOVIES -> AppIcons.Film
        Tab.CATCHUP -> AppIcons.Catchup
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
