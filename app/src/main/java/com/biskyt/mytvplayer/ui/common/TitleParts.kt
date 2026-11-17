package com.biskyt.mytvplayer.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import com.biskyt.mytvplayer.core.vod.CastMember
import com.biskyt.mytvplayer.core.vod.TitleLink
import com.biskyt.mytvplayer.ui.theme.Palette
import com.biskyt.mytvplayer.ui.theme.bd

// The parts of the film and series pages that show a title's people and its pages elsewhere (MyPlexPlayer's
// TitleParts.kt, drawn as this app's side panels are).

/**
 * Shows a title's link as a QR code to open on a phone, with "Open on this TV": what every row of links does. Unlike
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
 * Plays a trailer (a YouTube id) in the YouTube app; where it can't start, shows its YouTube page as a QR code (with
 * "Open on this TV"). Not straight to a browser, unlike MyPlexPlayer: see [rememberLinkOpener].
 */
@Composable
fun rememberTrailerOpener(): (String) -> Unit {
    val context = LocalContext.current
    var shown by remember { mutableStateOf<String?>(null) }
    shown?.let { LinkPanel(stringResource(R.string.vod_trailer), youTubePage(it)) { shown = null } }
    return remember(context) { { id -> if (openLink(context, "vnd.youtube:$id") != null) shown = id } }
}

private fun youTubePage(id: String) = "https://www.youtube.com/watch?v=$id"

/** One of the cast: photo (loaded only when shown), name and part. */
@Composable
fun CastPerson(person: CastMember, photo: Dp, modifier: Modifier = Modifier) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.bd), verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        CirclePhoto(person.photoUrl, person.name, photo)
        Column {
            Text(
                person.name,
                style = BoardOptionText.current,
                color = LocalContentColor.current,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            person.role?.let {
                Text(it, style = BoardHelpText.current, color = cardColor(Palette.textDim), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * The cast in a side panel, from a page's options. [heading] is "Cast", or "Cast and crew" where [cast] also lists who
 * directed and wrote it. Each person takes the remote in turn, so that a long list scrolls.
 */
@Composable
fun CastPanel(title: String, cast: List<CastMember>, heading: String = stringResource(R.string.vod_cast_title), onDismiss: () -> Unit) {
    BoardPanel(onDismiss, dim = false, scroll = false) {
        BoardPanelSubtitle(title)
        BoardPanelTitle(heading, Modifier.padding(bottom = 6.bd))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(BoardPanelGap)) {
            itemsIndexed(cast) { i, person ->
                BoardRow({}, if (i == 0) rememberFirstFocus() else Modifier) {
                    CastPerson(person, 48.bd, Modifier.padding(horizontal = 14.bd, vertical = 6.bd))
                }
            }
        }
    }
}
