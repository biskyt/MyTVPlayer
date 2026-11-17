package com.biskyt.mytvplayer.ui.vod

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.ui.common.OneLine
import com.biskyt.mytvplayer.ui.common.Poster
import com.biskyt.mytvplayer.ui.common.PosterTile
import com.biskyt.mytvplayer.ui.common.glowRoom
import com.biskyt.mytvplayer.ui.common.glowRoomPadding
import com.biskyt.mytvplayer.ui.common.raiseOnFocus
import com.biskyt.mytvplayer.ui.common.rememberFirstFocus
import com.biskyt.mytvplayer.ui.theme.BoardStyle
import com.biskyt.mytvplayer.ui.theme.MEDIUM
import com.biskyt.mytvplayer.ui.theme.Palette
import com.biskyt.mytvplayer.ui.theme.SEMI_BOLD

// The rows of posters on a person's page and on "More like this" (MyPlexPlayer's PersonScreen.kt). Their sizes are dp,
// not board pixels (bd): see PersonScreen.kt.

/** A heading and a row of [items]' cards ([card], 18 apart). The first card has the remote when [focus]. */
@Composable
internal fun <T> PosterRow(heading: String, items: List<T>, focus: Boolean, top: Dp, card: @Composable (T, Modifier) -> Unit) {
    Column(Modifier.padding(top = top), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Heading(heading, Modifier.padding(start = SIDE))
        // As on Home, the poster with the remote grows and glows (see focusGlow), drawn past the row's edges and over
        // the cards beside it.
        LazyRow(
            Modifier.glowRoom(),
            contentPadding = glowRoomPadding(PaddingValues(horizontal = SIDE)),
            horizontalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            itemsIndexed(items) { i, item ->
                Box(Modifier.raiseOnFocus()) { card(item, if (focus && i == 0) rememberFirstFocus() else Modifier) }
            }
        }
    }
}

/** A poster 140 by 210, the title and a dim line under it. */
@Composable
internal fun TitleCard(title: String, imageUrl: String?, line: String?, modifier: Modifier, onClick: () -> Unit) {
    PosterTile(
        onClick,
        null,
        modifier,
        listOfNotNull(title, line),
        width = POSTER,
        // (Its initials, shown until the picture loads, are not read: the card's texts are.)
        poster = { Poster(imageUrl, title, POSTER, Modifier.clearAndSetSemantics {}) }
    ) { focused ->
        OneLine(title, CardTitle.current, focused = focused)
        OneLine(line.orEmpty(), CardLine.current, color = Palette.textDim, focused = focused)
    }
}

/** A heading over a part of the page. */
@Composable
internal fun Heading(text: String, modifier: Modifier = Modifier) = Text(text, style = HeadingText.current, modifier = modifier)

/** The page's left and right margin, as the board's. */
internal val SIDE = 56.dp

/** Between the parts: the board's 40. */
internal val GAP = 40.dp

private val POSTER = 140.dp

private val HeadingText = BoardStyle(20, SEMI_BOLD)
private val CardTitle = BoardStyle(14, MEDIUM)
private val CardLine = BoardStyle(12, MEDIUM)
