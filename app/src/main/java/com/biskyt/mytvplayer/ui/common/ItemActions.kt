package com.biskyt.mytvplayer.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.appPlural
import com.biskyt.mytvplayer.appString
import com.biskyt.mytvplayer.data.db.CAT_MOVIE
import com.biskyt.mytvplayer.data.db.ChannelEntity
import com.biskyt.mytvplayer.ui.LocalNavigator
import com.biskyt.mytvplayer.ui.Navigator
import com.biskyt.mytvplayer.ui.Screen

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

    class Run(override val label: String, override val help: String? = null, val run: () -> Unit) : ItemAction
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
    }
}

fun favouriteLabel(favourite: Boolean) = appString(if (favourite) R.string.common_favourite_remove else R.string.common_favourite_add)

/** Marks something [watched] as unwatched, or the other way round. */
fun markWatched(watched: Boolean, set: (Boolean) -> Unit): ItemAction =
    ItemAction.Run(appString(if (watched) R.string.common_mark_unwatched else R.string.common_mark_watched)) { set(!watched) }

fun favouriteFlash(added: Boolean) = appString(if (added) R.string.common_favourite_added else R.string.common_favourite_removed)

/** What every channel menu offers (search, the player): favourites, catch-up where the provider has it, its guide. */
fun channelActions(ch: ChannelEntity, favourite: Boolean, setFavourite: (Boolean) -> Unit): List<ItemAction> = buildList {
    add(ItemAction.Run(favouriteLabel(favourite)) { setFavourite(!favourite) })
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
        favourite?.let { f -> ItemAction.Run(favouriteLabel(f)) { setFavourite(!f) } }
    )

/** A movie's or series' page ([type] is CAT_MOVIE or CAT_SERIES). */
fun vodDetails(playlistId: Long, type: String, id: Long): Screen =
    if (type == CAT_MOVIE) Screen.MovieDetails(playlistId, id) else Screen.SeriesDetails(playlistId, id)

/** A side panel of what can be done with something: [actions] in order, the first with focus. */
@Composable
fun ItemActionsPanel(title: String, actions: List<ItemAction>, onDismiss: () -> Unit) {
    val nav = LocalNavigator.current
    SidePanel(onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2, modifier = Modifier.padding(bottom = 8.dp))
            actions.forEachIndexed { i, a ->
                MenuOption(a.label, a.help, if (i == 0) rememberFirstFocus() else Modifier) {
                    a.perform(nav, onDismiss)
                }
            }
        }
    }
}
