package com.biskyt.mytvplayer.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.biskyt.mytvplayer.AppContainer
import com.biskyt.mytvplayer.container
import com.biskyt.mytvplayer.data.ChannelFilter
import com.biskyt.mytvplayer.data.Settings
import com.biskyt.mytvplayer.data.db.CAT_MOVIE
import com.biskyt.mytvplayer.data.db.CAT_SERIES
import com.biskyt.mytvplayer.data.db.PlaylistEntity
import com.biskyt.mytvplayer.ui.common.LocalTopBarInset
import com.biskyt.mytvplayer.ui.common.SyncToast
import com.biskyt.mytvplayer.ui.common.Tab
import com.biskyt.mytvplayer.ui.common.TabScaffoldGap
import com.biskyt.mytvplayer.ui.common.TopBar
import com.biskyt.mytvplayer.ui.common.onGuideKey
import com.biskyt.mytvplayer.ui.guide.GuideMatchingScreen
import com.biskyt.mytvplayer.ui.guide.GuideSourcesScreen
import com.biskyt.mytvplayer.ui.home.HomeScreen
import com.biskyt.mytvplayer.ui.live.CatchupScreen
import com.biskyt.mytvplayer.ui.live.LiveScreen
import com.biskyt.mytvplayer.ui.player.CatchupPlayerScreen
import com.biskyt.mytvplayer.ui.player.PlayerScreen
import com.biskyt.mytvplayer.ui.player.VodPlayerScreen
import com.biskyt.mytvplayer.ui.search.SearchScreen
import com.biskyt.mytvplayer.ui.settings.AboutScreen
import com.biskyt.mytvplayer.ui.settings.AppearanceScreen
import com.biskyt.mytvplayer.ui.settings.BackupScreen
import com.biskyt.mytvplayer.ui.settings.CategoriesScreen
import com.biskyt.mytvplayer.ui.settings.LicencesScreen
import com.biskyt.mytvplayer.ui.settings.PlaybackSettingsScreen
import com.biskyt.mytvplayer.ui.settings.PlaylistEditScreen
import com.biskyt.mytvplayer.ui.settings.PlaylistsScreen
import com.biskyt.mytvplayer.ui.settings.SettingsScreen
import com.biskyt.mytvplayer.ui.settings.WelcomeScreen
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
import com.biskyt.mytvplayer.ui.theme.Palette
import com.biskyt.mytvplayer.ui.vod.MovieDetailsScreen
import com.biskyt.mytvplayer.ui.vod.SeriesDetailsScreen
import com.biskyt.mytvplayer.ui.vod.VodBrowseScreen
import kotlinx.coroutines.flow.filterNotNull

@Composable
fun AppRoot() {
    val container = LocalContext.current.container
    // A crash last time: shown first, before anything that might crash again runs (see CrashLog).
    val crash = remember { container.crashLog.read() }
    val root = viewModel<RootViewModel>()
    if (crash != null && !root.crashSeen) {
        MyTvTheme {
            CrashScreen(crash) { updating ->
                container.crashLog.clear()
                root.safeStart = !updating
                root.crashSeen = true
            }
        }
        return
    }
    val settings by container.settings.settings.collectAsState(initial = null)
    val s = settings
    if (s == null) {
        Box(Modifier.fillMaxSize().background(Palette.background))
        return
    }
    // Only the first settings value decides the start screen; later changes (e.g. last channel) mustn't recompose the app.
    val initial = remember { s }
    MyTvTheme(accentArgb = s.accent, largeText = s.largeText) {
        LaunchedEffect(Unit) {
            if (root.navigator == null) root.navigator = firstScreen(container, initial, root.safeStart)
        }
        val nav = root.navigator ?: run {
            Box(Modifier.fillMaxSize().background(Palette.background))
            return@MyTvTheme
        }
        // A backup file opened with MyTVPlayer from another app: Backup & restore offers to restore it.
        LaunchedEffect(nav) {
            container.openedFile.filterNotNull().collect { if (nav.current.screen != Screen.Backup) nav.push(Screen.Backup) }
        }
        val activity = LocalActivity.current
        BackHandler {
            when {
                nav.pop() -> Unit
                nav.current.screen != Screen.Home && nav.current.screen != Screen.Welcome && nav.current.screen !is Screen.PlaylistEdit ->
                    nav.reset(Screen.Home)
                // Closes the app, and takes it off the recent apps too: Back from Home means "close".
                else -> activity?.finishAndRemoveTask()
            }
        }
        val hasPlaylist = s.activePlaylistId != 0L
        Box(
            Modifier.fillMaxSize().background(Palette.background)
                // Clear of a phone's camera cutout (TVs have none).
                .windowInsetsPadding(WindowInsets.displayCutout)
                .guideButton(nav, hasPlaylist)
        ) {
            NavHost(nav) { screen -> ScreenContent(screen, hasPlaylist) }
            SyncToast(nav, Modifier.align(Alignment.BottomEnd).padding(24.dp))
        }
    }
}

/**
 * The first screen, decided once: set-up if there is no playlist yet, else Home (or the last channel), updating the
 * playlist if it's due. [safeStart] (after a crash): neither the update nor the last channel, in case that's what crashed.
 */
private suspend fun firstScreen(container: AppContainer, s: Settings, safeStart: Boolean): Navigator {
    val playlist = container.playlists.get(s.activePlaylistId)
        ?: container.playlists.allIds().firstOrNull()?.let { container.playlists.get(it) }
        ?: return Navigator(Screen.Welcome)
    val nav = Navigator(Screen.Home)
    if (playlist.id != s.activePlaylistId) container.settings.update { it.copy(activePlaylistId = playlist.id) }
    if (safeStart) return nav
    when {
        playlist.isStale() -> container.sync.refresh(playlist.id)
        needsVod(container, playlist) -> container.sync.refreshVod(playlist.id)
    }
    // Not when opened with a backup file: that's what to show (see MainActivity).
    if (s.startOnLastChannel &&
        container.openedFile.value == null &&
        s.lastChannelId != 0L &&
        container.channels.get(playlist.id, s.lastChannelId) != null
    ) {
        nav.push(Screen.Player(playlist.id, s.lastChannelId, ChannelFilter.All))
    }
    return nav
}

/**
 * The remote's Guide button opens the TV guide from anywhere a screen hasn't used it already (the channel player shows
 * the channel's schedule). Opened on top, so Back returns to whatever was there, unchanged.
 */
private fun Modifier.guideButton(nav: Navigator, hasPlaylist: Boolean) =
    onGuideKey({ hasPlaylist && guideButtonOpensGuide(nav.current.screen) }) { nav.push(Screen.Live(guide = true)) }

/**
 * Movies and series not loaded yet (e.g. just updated from a version without them). M3U ones only if never loaded:
 * with every category unticked there are rightly none.
 */
private suspend fun needsVod(c: AppContainer, p: PlaylistEntity) = p.hasVod && (!p.isM3u || p.vodFeedHash == null) && !c.vod.hasTitles(p.id)

/**
 * Not while watching something (leaving would stop it), during set-up, or on the live screen (which switches between
 * list and guide itself, wherever focus is on it).
 */
internal fun guideButtonOpensGuide(screen: Screen) = !screen.isPlayback && !screen.isSetup && screen !is Screen.Live

/**
 * [screen], with the top bar: on every screen but playback and set-up (first launch, or anything before there is a
 * playlist, such as restoring a backup from the welcome screen).
 */
@Composable
internal fun ScreenContent(screen: Screen, hasPlaylist: Boolean = true) {
    if (screen.drawsOwnTopBar) return ScreenBody(screen)
    // The screen stays in one place with or without the bar, so it keeps its state as the bar comes (a backup restored
    // during set-up adds the first playlist) or goes.
    val bar = !screen.isPlayback && !screen.isSetup && hasPlaylist
    if (bar && screen.drawsBehindTopBar) return BehindTopBar(screen.tab) { ScreenBody(screen) }
    Column(Modifier.fillMaxSize()) {
        if (bar) TopBar(screen.tab)
        Box(Modifier.weight(1f).padding(top = if (bar) TabScaffoldGap else 0.dp)) { ScreenBody(screen) }
    }
}

/**
 * The top bar over [content], which fills the screen behind it (a title's art showing through) and starts its own
 * content [LocalTopBarInset] down: the bar is measured first, for that.
 */
@Composable
private fun BehindTopBar(tab: Tab?, content: @Composable () -> Unit) = SubcomposeLayout(Modifier.fillMaxSize()) { constraints ->
    val loose = constraints.copy(minWidth = 0, minHeight = 0)
    val bar = subcompose(Slot.BAR) { TopBar(tab) }.map { it.measure(loose) }
    val inset = (bar.maxOfOrNull { it.height } ?: 0) + TabScaffoldGap.roundToPx()
    val body = subcompose(Slot.BODY) { CompositionLocalProvider(LocalTopBarInset provides inset.toDp(), content = content) }
        .map { it.measure(constraints) }
    layout(constraints.maxWidth, constraints.maxHeight) {
        body.forEach { it.place(0, 0) }
        bar.forEach { it.place(0, 0) }
    }
}

private enum class Slot { BAR, BODY }

/** The pages with a title's art behind them, which shows behind the top bar too. */
private val Screen.drawsBehindTopBar: Boolean
    get() = this is Screen.MovieDetails || this is Screen.SeriesDetails

/** The tab screens draw the top bar themselves (Live TV's number keys cover its tabs too). Every screen is listed. */
private val Screen.drawsOwnTopBar: Boolean
    get() = when (this) {
        Screen.Home, is Screen.Live, is Screen.Movies, is Screen.Series, is Screen.Search, is Screen.Catchup -> true
        Screen.Welcome, is Screen.Player, is Screen.VodPlayer, is Screen.CatchupPlayer, is Screen.MovieDetails,
        is Screen.SeriesDetails, Screen.Settings, Screen.Playlists, is Screen.PlaylistEdit, is Screen.Categories,
        Screen.PlaybackSettings, Screen.Appearance, Screen.GuideSources, is Screen.GuideMatching, Screen.Backup,
        Screen.About, Screen.Licences -> false
    }

/** The tab a screen comes under in the top bar, if any. */
private val Screen.tab: Tab?
    get() = when (this) {
        is Screen.MovieDetails -> Tab.MOVIES
        is Screen.SeriesDetails -> Tab.SERIES
        else -> null
    }

@Composable
private fun ScreenBody(screen: Screen) {
    when (screen) {
        Screen.Welcome -> WelcomeScreen()
        Screen.Home -> HomeScreen()
        is Screen.Live -> LiveScreen(screen.filter, screen.guide)
        is Screen.Movies -> VodBrowseScreen(CAT_MOVIE, screen.filter)
        is Screen.Series -> VodBrowseScreen(CAT_SERIES, screen.filter)
        is Screen.MovieDetails -> MovieDetailsScreen(screen)
        is Screen.SeriesDetails -> SeriesDetailsScreen(screen)
        is Screen.VodPlayer -> VodPlayerScreen(screen)
        is Screen.Search -> SearchScreen(screen.query)
        is Screen.Player -> PlayerScreen(screen)
        is Screen.Catchup -> CatchupScreen(screen)
        is Screen.CatchupPlayer -> CatchupPlayerScreen(screen)
        Screen.Settings -> SettingsScreen()
        Screen.Playlists -> PlaylistsScreen()
        is Screen.PlaylistEdit -> PlaylistEditScreen(screen.playlistId, screen.firstRun)
        is Screen.Categories -> CategoriesScreen(screen.playlistId, screen.firstRun)
        Screen.PlaybackSettings -> PlaybackSettingsScreen()
        Screen.Appearance -> AppearanceScreen()
        Screen.GuideSources -> GuideSourcesScreen()
        is Screen.GuideMatching -> GuideMatchingScreen(screen.playlistId, screen.streamId)
        Screen.Backup -> BackupScreen()
        Screen.About -> AboutScreen()
        Screen.Licences -> LicencesScreen()
    }
}

/** Owns the back stack for the activity's lifetime and releases every screen (and the player) with it. */
class RootViewModel : ViewModel() {
    var navigator by mutableStateOf<Navigator?>(null)

    /** The last crash's report has been seen (and dealt with) this time. */
    var crashSeen by mutableStateOf(false)

    /** Chosen after a crash: start without updating the playlist or opening the last channel, in case that crashed. */
    var safeStart = false

    override fun onCleared() {
        navigator?.clear()
    }
}
