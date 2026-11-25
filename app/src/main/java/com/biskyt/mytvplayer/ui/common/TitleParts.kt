package com.biskyt.mytvplayer.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.core.tmdb.TmdbVideo
import com.biskyt.mytvplayer.core.vod.CastMember
import com.biskyt.mytvplayer.core.vod.TitleLink
import com.biskyt.mytvplayer.ui.theme.BoardStyle
import com.biskyt.mytvplayer.ui.theme.EXTRA_BOLD
import com.biskyt.mytvplayer.ui.theme.Palette
import com.biskyt.mytvplayer.ui.theme.SEMI_BOLD
import com.biskyt.mytvplayer.ui.theme.bd

// The parts of the film and series pages that show a title's people and its pages elsewhere (MyPlexPlayer's
// TitleParts.kt, drawn as this app's side panels are).

/**
 * Shows a title's link as a QR code to open on a phone (not on a phone itself), with "Open on this device": what every
 * row of links does. Unlike
 * MyPlexPlayer, the panel comes first, as MyTVPlayer's links always have: some TVs have a stand-in "picker" that takes
 * the browser's intent without failing and only says "no application can handle this request" (CLAUDE.md).
 */
@Composable
fun rememberLinkOpener(): (TitleLink) -> Unit {
    var shown by remember { mutableStateOf<TitleLink?>(null) }
    shown?.let { LinkPanel(it.name, it.url) { shown = null } }
    return remember { { link -> shown = link } }
}

/**
 * Plays a trailer by its page ([url], on YouTube or Vimeo), which the YouTube or Vimeo app (or another, such as
 * SmartTube) opens and plays; where nothing can open it, shows that address as a QR code (with "Open on this device"; see LinkPanel). Not a
 * "vnd.youtube:" link: on Android TV the YouTube app then opens without the video, and SmartTube calls it a bad intent.
 */
@Composable
fun rememberTrailerOpener(): (String) -> Unit {
    val context = LocalContext.current
    var shown by remember { mutableStateOf<String?>(null) }
    shown?.let { LinkPanel(stringResource(R.string.vod_trailer), it) { shown = null } }
    return remember(context) { { url -> if (openLink(context, url) != null) shown = url } }
}

/**
 * "Other trailers": all of a title's trailers and teasers from TMDB, best first, each with the country it was made for.
 * A studio's upload is often blocked outside its own country: one of the others may play. OK plays one; the panel stays.
 */
@Composable
fun TrailersPanel(title: String, trailers: List<TmdbVideo>, onDismiss: () -> Unit) {
    ItemActionsPanel(title, trailerActions(trailers), onDismiss, stringResource(R.string.vod_other_trailers))
}

/**
 * Each of [trailers] as an entry that plays it: "Official Trailer · GB", or "Teaser · US · Vimeo" (its name, else
 * whether it is a trailer or a teaser; the country it was made for; the site, if not YouTube).
 */
@Composable
fun trailerActions(trailers: List<TmdbVideo>): List<ItemAction> {
    val open = rememberTrailerOpener()
    val trailer = stringResource(R.string.vod_trailer)
    val teaser = stringResource(R.string.vod_teaser)
    return trailers.map { v ->
        val name = v.name ?: if (v.teaser) teaser else trailer
        val label = listOfNotNull(name, v.country, v.site.takeIf { it != TmdbVideo.YOUTUBE }).joinToString(" · ")
        ItemAction.Run(label) { open(v.url) }
    }
}

/**
 * One of the cast: photo (loaded only when shown), name and part. Larger than a panel's options (the owner, 2026-11-24):
 * the name 18, the part 14.
 */
@Composable
fun CastPerson(person: CastMember, photo: Dp, modifier: Modifier = Modifier) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.bd), verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        CirclePhoto(person.photoUrl, person.name, photo)
        Column {
            Text(
                person.name,
                style = CastNameText.current,
                color = LocalContentColor.current,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            person.role?.let {
                Text(it, style = CastRoleText.current, color = cardColor(Palette.textDim), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * The cast in a side panel, from a page's options. [heading] is "Cast", or "Cast and crew" where [cast] also lists who
 * directed and wrote it. Each person takes the remote in turn, so that a long list scrolls; the one at [focus] has it
 * first (back from their page). OK on one with a TMDB id opens their page ([open], with their place in the list).
 */
@Composable
fun CastPanel(
    title: String,
    cast: List<CastMember>,
    heading: String = stringResource(R.string.vod_cast_title),
    focus: Int = 0,
    open: (Int) -> Unit = {},
    onDismiss: () -> Unit
) {
    // The cast is a list of its own, which does not go in a scrolling column.
    BoardPanel(onDismiss, dim = false, scroll = false) {
        BoardPanelSubtitle(title)
        BoardPanelTitle(heading, Modifier.padding(bottom = 6.bd))
        val first = focus.coerceIn(0, (cast.size - 1).coerceAtLeast(0))
        LazyColumn(state = rememberLazyListState(first), verticalArrangement = Arrangement.spacedBy(BoardPanelGap)) {
            itemsIndexed(cast) { i, person ->
                val modifier = if (i == first) rememberFirstFocus() else Modifier
                BoardRow({ if (person.id != null) open(i) }, modifier) {
                    CastPerson(person, CAST_PHOTO, Modifier.padding(horizontal = 14.bd, vertical = 6.bd))
                }
            }
        }
    }
}

/** A cast panel's photos: half as large again as the 48 they were (the owner, 2026-11-24). */
private val CAST_PHOTO = 72.bd
private val CastNameText = BoardStyle(18, EXTRA_BOLD)
private val CastRoleText = BoardStyle(14, SEMI_BOLD)
