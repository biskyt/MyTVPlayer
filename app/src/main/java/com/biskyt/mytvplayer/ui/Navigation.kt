package com.biskyt.mytvplayer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateListOf
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
import com.biskyt.mytvplayer.data.VodFilter

/** Every screen in the app. Tab roots are [Home], [Live], [Movies], [Series]. */
sealed interface Screen {
    /** First launch, or after the last playlist is deleted: add a provider, the free channels, or restore a backup. */
    data object Welcome : Screen
    data object Home : Screen

    /** [filter] null: the list shown last. */
    data class Live(val filter: ChannelFilter? = null, val guide: Boolean = false) : Screen
    data class Movies(val filter: VodFilter? = null) : Screen
    data class Series(val filter: VodFilter? = null) : Screen

    /** [query]: already typed (e.g. a TV programme's title). */
    data class Search(val query: String = "") : Screen
    data class Player(val playlistId: Long, val streamId: Long, val filter: ChannelFilter) : Screen

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
        val filter: ChannelFilter = ChannelFilter.All
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
    data object Settings : Screen
    data object Playlists : Screen
    data class PlaylistEdit(val playlistId: Long?, val firstRun: Boolean = false) : Screen
    data class Categories(val playlistId: Long, val firstRun: Boolean = false) : Screen
    data object PlaybackSettings : Screen
    data object Appearance : Screen
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
    val vod = HashMap<String, VodFilter>()
}

/** A tiny back stack: enough for a TV app and much lighter than a navigation library. */
class Navigator(start: Screen) {
    /** The lists picked on Live TV, Movies and Series while the app is open (as this navigator is). */
    val picked = PickedLists()

    private var nextId = 0
    val stack = mutableStateListOf(NavEntry(start, nextId++))

    /** Ids of entries removed since the host last cleaned up their saved state. */
    internal val dropped = ArrayList<Int>()
    val current: NavEntry get() = stack.last()

    fun push(screen: Screen) {
        stack += NavEntry(screen, nextId++)
    }

    /** Shows [screen]: back to it if it's on the stack already (never a second copy), else over this one. */
    fun open(screen: Screen) {
        val at = stack.indexOfLast { it.screen == screen }
        if (at < 0) return push(screen)
        while (stack.lastIndex > at) drop(stack.removeAt(stack.lastIndex))
    }

    /** Returns false when there is nothing left to go back to. */
    fun pop(): Boolean {
        if (stack.size <= 1) return false
        drop(stack.removeAt(stack.lastIndex))
        return true
    }

    /** Clears the stack and shows [screen], e.g. when switching tabs or finishing set-up. */
    fun reset(screen: Screen) {
        val old = stack.toList()
        stack.clear()
        stack += NavEntry(screen, nextId++)
        old.forEach(::drop)
    }

    /** Replaces the top entry, keeping what is below it. */
    fun replace(screen: Screen) {
        drop(stack.removeAt(stack.lastIndex))
        push(screen)
    }

    /** Releases every screen's ViewModels (and so the player) when the activity is finishing. */
    fun clear() {
        stack.forEach { it.viewModelStore.clear() }
    }

    private fun drop(entry: NavEntry) {
        entry.viewModelStore.clear()
        dropped += entry.id
    }
}

val LocalNavigator = staticCompositionLocalOf<Navigator> { error("No navigator") }

/** Shows the top of the back stack, keeping scroll positions of screens below it. */
@Composable
fun NavHost(navigator: Navigator, content: @Composable (Screen) -> Unit) {
    val holder = rememberSaveableStateHolder()
    val entry = navigator.current
    SideEffect {
        navigator.dropped.forEach(holder::removeState)
        navigator.dropped.clear()
    }
    CompositionLocalProvider(LocalNavigator provides navigator, LocalViewModelStoreOwner provides entry) {
        holder.SaveableStateProvider(entry.id) { content(entry.screen) }
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
