package com.biskyt.mytvplayer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.biskyt.mytvplayer.AppContainer
import com.biskyt.mytvplayer.container
import com.biskyt.mytvplayer.data.ChannelFilter
import com.biskyt.mytvplayer.data.ChannelKinds
import com.biskyt.mytvplayer.data.VodFilter
import com.biskyt.mytvplayer.ui.common.FocusVisit
import com.biskyt.mytvplayer.ui.common.KeepsFocus
import com.biskyt.mytvplayer.ui.common.LocalFocusVisit
import com.biskyt.mytvplayer.ui.common.Tab
import kotlin.random.Random

/** Every screen in the app. Tab roots are [Home], [Live], [Movies], [Series]. */
sealed interface Screen {
    /** First launch, or after the last playlist is deleted: add a provider, the free channels, or restore a backup. */
    data object Welcome : Screen
    data object Home : Screen

    /** [filter] null: the list shown last. [radio]: the Radio section, Live TV's screens with radio stations only. */
    data class Live(val filter: ChannelFilter? = null, val guide: Boolean = false, val radio: Boolean = false) : Screen
    data class Movies(val filter: VodFilter? = null) : Screen
    data class Series(val filter: VodFilter? = null) : Screen

    /** [query]: already typed (e.g. a TV programme's title). */
    data class Search(val query: String = "") : Screen

    /** [kinds]: the list's channels, TV, radio or both (zapping stays in them; see ChannelKinds). */
    data class Player(
        val playlistId: Long,
        val streamId: Long,
        val filter: ChannelFilter,
        val kinds: ChannelKinds = ChannelKinds.ALL
    ) : Screen

    /** A channel's catch-up page: days on the left, the programmes that can be replayed on the right. */
    data class Catchup(val playlistId: Long, val streamId: Long) : Screen

    /**
     * A catch-up channel as it was from [startMs] on, running on into the following programmes; null starts what's on
     * now from its beginning ("Start over"). [filter]: the live list to return to for "Watch live".
     */
    data class CatchupPlayer(
        val playlistId: Long,
        val streamId: Long,
        val startMs: Long? = null,
        val filter: ChannelFilter = ChannelFilter.All,
        /** Opened by ◀ on the live channel: it says so while it loads (see PlayerViewModel.catchupFrom). */
        val switched: Boolean = false,
        val kinds: ChannelKinds = ChannelKinds.ALL
    ) : Screen

    /**
     * A movie ([seriesId] null) or an episode. [fromStart] ignores any saved position; [continueSeries] (from "Carry on
     * watching") moves on to the next episode if this one was finished.
     */
    data class VodPlayer(
        val playlistId: Long,
        val itemId: Long,
        val seriesId: Long? = null,
        val fromStart: Boolean = false,
        val continueSeries: Boolean = false
    ) : Screen
    data class MovieDetails(val playlistId: Long, val streamId: Long) : Screen

    /** [season] and [episode] (from 1): open on that episode (found from the TV guide). */
    data class SeriesDetails(val playlistId: Long, val seriesId: Long, val season: Int? = null, val episode: Int? = null) : Screen

    /**
     * A cast or crew member's page: [personId] is their id on TMDB; [playlistId] the playlist whose titles of theirs it
     * shows. [name] and [photoUrl] show until the page loads. It was opened from [title] (where they play [role], if an
     * actor), under top bar [tab].
     */
    data class Person(
        val playlistId: Long,
        val personId: Long,
        val name: String,
        val photoUrl: String?,
        val role: String?,
        val title: String,
        val tab: Tab,
    ) : Screen

    /**
     * "More like this": the films (or, when [series], the series) that TMDB suggests for its title [tmdbId], called
     * [title] in playlist [playlistId]. It shows which of them are in the playlist, under top bar [tab].
     */
    data class MoreLike(val playlistId: Long, val tmdbId: Int, val series: Boolean, val title: String, val tab: Tab) : Screen

    /**
     * A film's (or, when [series], a series') page on TMDB, for a title that playlist [playlistId] may not have: its
     * overview, and "Find in your playlist", which searches the playlist for [title]. [posterUrl] shows until it loads.
     */
    data class TmdbTitle(
        val playlistId: Long,
        val tmdbId: Int,
        val series: Boolean,
        val title: String,
        val posterUrl: String?,
        val tab: Tab,
    ) : Screen
    data object Settings : Screen
    data object Playlists : Screen
    data class PlaylistEdit(val playlistId: Long?, val firstRun: Boolean = false) : Screen
    data class Categories(val playlistId: Long, val firstRun: Boolean = false) : Screen
    data object PlaybackSettings : Screen
    data object Appearance : Screen
    data object MiscSettings : Screen
    data object Parental : Screen
    data object Sync : Screen
    data object GuideSources : Screen
    data class GuideMatching(val playlistId: Long, val streamId: Long? = null) : Screen
    data object Backup : Screen
    data object About : Screen
    data object Licences : Screen
}

/** A back-stack entry owns the ViewModels of its screen; they are cleared when the entry is popped. */
class NavEntry(val screen: Screen, val id: Int) : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
}

/**
 * The list last picked on Live TV ([live]) and on Movies and Series ([vod], by CAT_MOVIE or CAT_SERIES): each opens there
 * again while the app is open, and on Favourites once it has been closed.
 */
class PickedLists {
    var live: ChannelFilter? = null

    /** The Radio section's list, apart from Live TV's. */
    var radio: ChannelFilter? = null
    val vod = HashMap<String, VodFilter>()
}

/** A tiny back stack: enough for a TV app and much lighter than a navigation library. */
class Navigator(start: Screen) {
    /** The lists picked on Live TV, Movies and Series while the app is open (as this navigator is). */
    val picked = PickedLists()

    // Each run of the app (each navigator) numbers its entries from a start of its own. An entry's saved state is kept
    // by its number, and Android gives it back after it has stopped the app and starts it again: a new run's entries
    // must not take the old run's (Home far down the page; the owner, 2026-11-11). The app opens again, fresh.
    private var nextId = Random.nextInt(0, Int.MAX_VALUE / 2)
    val stack = mutableStateListOf(NavEntry(start, nextId++))

    val current: NavEntry get() = stack.last()

    /**
     * The entry a card on the TV's home screen opened (the owner, 2026-11-15): when it closes, the app goes back to the
     * launcher, not to its own Home (Home is shown again under it, for the next time the app opens). See [onLeave].
     */
    var launcherEntry: Int? = null

    /** Takes the app back to the launcher (AppRoot sets it while its activity is there). */
    var onLeave: (() -> Unit)? = null

    fun push(screen: Screen) {
        stack += NavEntry(screen, nextId++)
    }

    /** Shows [screen]: back to an equal one if it's on the stack already, else over this one (a new search too). */
    fun open(screen: Screen) {
        val at = stack.indexOfLast { it.screen == screen }
        if (at < 0) return push(screen)
        while (stack.lastIndex > at) drop(stack.removeAt(stack.lastIndex))
    }

    /** Returns false when there is nothing left to go back to. */
    fun pop(): Boolean {
        if (stack.size <= 1) return false
        if (current.id == launcherEntry) {
            // (Home is under it: see AppRoot's openCard.)
            launcherEntry = null
            drop(stack.removeAt(stack.lastIndex))
            onLeave?.invoke()
            return true
        }
        drop(stack.removeAt(stack.lastIndex))
        return true
    }

    /**
     * What Back does: goes to the screen below, or else to Home. Returns false on Home, Welcome or a playlist's set-up
     * with nothing below it, where Back closes the app.
     */
    fun back(): Boolean {
        if (pop()) return true
        val screen = current.screen
        if (screen == Screen.Home || screen == Screen.Welcome || screen is Screen.PlaylistEdit) return false
        reset(Screen.Home)
        return true
    }

    /** Clears the stack and shows [screen], e.g. when switching tabs or finishing set-up. */
    fun reset(screen: Screen) {
        val old = stack.toList()
        launcherEntry = null
        stack.clear()
        stack += NavEntry(screen, nextId++)
        old.forEach(::drop)
    }

    /** Replaces the top entry, keeping what is below it. */
    fun replace(screen: Screen) {
        val fromLauncher = current.id == launcherEntry
        drop(stack.removeAt(stack.lastIndex))
        push(screen)
        // (The channel player and its catch-up replace each other: the launcher's entry is still the one shown.)
        if (fromLauncher) launcherEntry = current.id
    }

    /** Releases every screen's ViewModels (and so the player) when the activity is finishing. */
    fun clear() {
        stack.forEach { it.viewModelStore.clear() }
    }

    // (Its saved state goes too: see NavHost.)
    private fun drop(entry: NavEntry) {
        entry.viewModelStore.clear()
    }
}

val LocalNavigator = staticCompositionLocalOf<Navigator> { error("No navigator") }

/** Shows the top of the back stack, keeping scroll positions of screens below it. */
@Composable
fun NavHost(navigator: Navigator, content: @Composable (Screen) -> Unit) {
    val holder = rememberSaveableStateHolder()
    // The entries the holder keeps state for, saved with it. An entry no longer on the stack has its state removed:
    // one taken off it, and after Android starts the app again, the old run's (numbered apart from the new run's: see
    // Navigator).
    val kept = rememberSaveable { ArrayList<Int>() }
    val entry = navigator.current
    SideEffect {
        val ids = navigator.stack.map { it.id }
        kept.filterNot(ids::contains).forEach(holder::removeState)
        kept.clear()
        kept += ids
    }
    // A new visit each time an entry is shown (see rememberFirstFocus's once).
    val visit = remember(entry) { FocusVisit() }
    CompositionLocalProvider(LocalNavigator provides navigator, LocalViewModelStoreOwner provides entry, LocalFocusVisit provides visit) {
        // Inside the entry's saved state: the item last focused is kept with it (see KeepsFocus).
        holder.SaveableStateProvider(entry.id) { KeepsFocus(entry) { content(entry.screen) } }
    }
}

/** Something playing: leaving the screen would stop it. */
val Screen.isPlayback: Boolean get() = this is Screen.Player || this is Screen.VodPlayer || this is Screen.CatchupPlayer

/** First-launch set-up, before there is anything to watch. */
val Screen.isSetup: Boolean
    get() = this == Screen.Welcome || (this is Screen.PlaylistEdit && firstRun) || (this is Screen.Categories && firstRun)

/** Creates a screen-scoped ViewModel with access to the app's dependencies. */
@Composable
inline fun <reified VM : ViewModel> appViewModel(key: String? = null, crossinline create: (AppContainer) -> VM): VM {
    val container = LocalContext.current.container
    return viewModel(key = key) { create(container) }
}
