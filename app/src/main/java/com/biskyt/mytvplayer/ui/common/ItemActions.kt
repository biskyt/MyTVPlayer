package com.biskyt.mytvplayer.ui.common

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.appPlural
import com.biskyt.mytvplayer.appString
import com.biskyt.mytvplayer.core.vod.TitleLink
import com.biskyt.mytvplayer.data.db.CAT_MOVIE
import com.biskyt.mytvplayer.data.db.ChannelEntity
import com.biskyt.mytvplayer.ui.LocalNavigator
import com.biskyt.mytvplayer.ui.Navigator
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.theme.bd

/** Something an options menu offers for an item: open a screen, or do something there and then. */
sealed interface ItemAction {
    val label: String
    val help: String?

    /** Opens [screen] on top, or in place of this one when [replace] (e.g. leaving a movie for its details). */
    data class Open(
        override val label: String,
        val screen: Screen,
        override val help: String? = null,
        val replace: Boolean = false
    ) : ItemAction

    /** Does [run]; [icon] shows after the label (the favourite's heart). */
    class Run(
        override val label: String,
        override val help: String? = null,
        val icon: ImageVector? = null,
        val run: () -> Unit
    ) : ItemAction

    /** A heading over the options after it ("This episode", "The show"); not an option itself. */
    class Heading(override val label: String) : ItemAction {
        override val help: String? = null
    }

    /** Pages about the title elsewhere, side by side in one row: left and right move between them, OK opens one. */
    class Links(override val label: String, val links: List<TitleLink>) : ItemAction {
        override val help: String? = null
    }
}

/** Does [this] from a menu: an Open closes the menu ([close]) first, and calls [beforeReplace] before replacing. */
fun ItemAction.perform(nav: Navigator, close: () -> Unit, beforeReplace: () -> Unit = {}) {
    when (this) {
        is ItemAction.Open -> {
            close()
            if (replace) {
                beforeReplace()
                nav.replace(screen)
            } else {
                nav.push(screen)
            }
        }
        is ItemAction.Run -> run()
        is ItemAction.Heading, is ItemAction.Links -> error("Only ItemActionsPanel offers $label")
    }
}

fun favouriteLabel(favourite: Boolean) = appString(if (favourite) R.string.common_favourite_remove else R.string.common_favourite_add)

/**
 * "Add to favourites", or for a [favourite] "Remove from favourites", with the heart (filled for a favourite) as every
 * menu shows it (the owner, 2026-11-22). [setFavourite] gets what it is to be.
 */
fun favouriteAction(favourite: Boolean, setFavourite: (Boolean) -> Unit) =
    ItemAction.Run(favouriteLabel(favourite), icon = favouriteIcon(favourite)) { setFavourite(!favourite) }

fun favouriteIcon(favourite: Boolean) = if (favourite) AppIcons.Heart else AppIcons.HeartOutline

/** Marks something [watched] as unwatched, or the other way round. */
fun markWatched(watched: Boolean, set: (Boolean) -> Unit): ItemAction =
    ItemAction.Run(appString(if (watched) R.string.common_mark_unwatched else R.string.common_mark_watched)) { set(!watched) }

fun favouriteFlash(added: Boolean) = appString(if (added) R.string.common_favourite_added else R.string.common_favourite_removed)

/** What every channel menu offers (search, the player): favourites, catch-up where the provider has it, its guide. */
fun channelActions(ch: ChannelEntity, favourite: Boolean, setFavourite: (Boolean) -> Unit): List<ItemAction> = buildList {
    add(favouriteAction(favourite, setFavourite))
    if (ch.archiveDays > 0) {
        val help = appPlural(R.plurals.common_catchup_days, ch.archiveDays, ch.archiveDays)
        add(ItemAction.Open(appString(R.string.common_catchup), Screen.Catchup(ch.playlistId, ch.streamId), help))
    }
    add(
        ItemAction.Open(
            appString(R.string.common_change_guide_source),
            Screen.GuideMatching(ch.playlistId, ch.streamId),
            appString(R.string.common_change_guide_source_help)
        )
    )
}

/**
 * What a movie or series menu offers (search, Home): open it, and add or remove it from favourites once that's known
 * ([favourite] null until read: "Add" for a favourite would undo it).
 */
fun vodActions(playlistId: Long, type: String, id: Long, favourite: Boolean?, setFavourite: (Boolean) -> Unit): List<ItemAction> =
    listOfNotNull(
        ItemAction.Open(
            appString(if (type == CAT_MOVIE) R.string.search_open_movie else R.string.search_open_series),
            vodDetails(playlistId, type, id)
        ),
        favourite?.let { f -> favouriteAction(f, setFavourite) }
    )

/** A movie's or series' page ([type] is CAT_MOVIE or CAT_SERIES). */
fun vodDetails(playlistId: Long, type: String, id: Long): Screen =
    if (type == CAT_MOVIE) Screen.MovieDetails(playlistId, id) else Screen.SeriesDetails(playlistId, id)

/**
 * A side panel of what can be done with something: [title], [subtitle] under it if given ("2021 · Drama, Mystery"), then
 * [actions] in order, the first that is not a heading with focus.
 */
@Composable
fun ItemActionsPanel(title: String, actions: List<ItemAction>, onDismiss: () -> Unit, subtitle: String? = null) {
    val nav = LocalNavigator.current
    BoardPanel(onDismiss) {
        BoardPanelTitle(title, Modifier.padding(bottom = if (subtitle == null) 6.bd else 2.bd))
        subtitle?.let { BoardPanelSubtitle(it, Modifier.padding(bottom = 8.bd)) }
        val first = actions.indexOfFirst { it !is ItemAction.Heading }
        actions.forEachIndexed { i, a ->
            ItemActionRow(a, if (i == first) rememberFirstFocus() else Modifier) { a.perform(nav, onDismiss) }
        }
    }
}

/**
 * One row of an options panel, drawn the same in every one ([ItemActionsPanel], the player's More): a heading, a row of
 * links, or an option with its help line and icon, which [perform] does.
 */
@Composable
fun ItemActionRow(a: ItemAction, modifier: Modifier = Modifier, perform: () -> Unit) = when (a) {
    is ItemAction.Heading -> BoardSection(a.label)
    is ItemAction.Links -> BoardLinksRow(a.label, a.links, modifier)
    else -> BoardOption(a.label, a.help, modifier, icon = (a as? ItemAction.Run)?.icon, onClick = perform)
}
