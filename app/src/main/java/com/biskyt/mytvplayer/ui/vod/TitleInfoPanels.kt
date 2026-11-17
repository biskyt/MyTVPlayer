package com.biskyt.mytvplayer.ui.vod

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.appString
import com.biskyt.mytvplayer.core.tmdb.TmdbTitle
import com.biskyt.mytvplayer.ui.common.BoardFact
import com.biskyt.mytvplayer.ui.common.BoardPanel
import com.biskyt.mytvplayer.ui.common.BoardPanelTitle
import com.biskyt.mytvplayer.ui.common.formatDate
import com.biskyt.mytvplayer.ui.common.rememberFirstFocus
import com.biskyt.mytvplayer.ui.theme.bd

// The film's More… › About this film, and a show's About this show (MyPlexPlayer's board "PlexFilmAbout"), from what
// TMDB knows of it. Each fact takes the remote in turn, so that a long list scrolls.

/**
 * About this film (or this show, when not [film]): its original title, when it was released, its studios, countries and
 * genres. A line with no data is left out.
 */
@Composable
fun AboutPanel(film: Boolean, tmdb: TmdbTitle, onDismiss: () -> Unit) {
    BoardPanel(onDismiss) {
        BoardPanelTitle(
            stringResource(if (film) R.string.vod_about_this_film else R.string.vod_about_this_show),
            Modifier.padding(bottom = 3.bd)
        )
        remember(tmdb) { aboutFacts(tmdb) }.forEachIndexed { i, (label, value) ->
            BoardFact(label, value, ABOUT_LABEL, if (i == 0) rememberFirstFocus() else Modifier)
        }
    }
}

/** About's lines, in the board's order: those with a value only. */
fun aboutFacts(t: TmdbTitle): List<Pair<String, String>> = listOf(
    appString(R.string.vod_original_title) to t.originalTitle,
    appString(R.string.vod_released) to t.released?.let(::formatDate),
    appString(R.string.vod_studio) to t.studios.list(),
    appString(R.string.vod_countries) to t.countries.list(),
    appString(R.string.vod_genres) to t.genres.list(),
).mapNotNull { (label, value) -> value?.takeIf { it.isNotBlank() }?.let { label to it } }

private fun List<String>.list() = takeIf { it.isNotEmpty() }?.joinToString(", ")

/** The board's label column. */
private val ABOUT_LABEL: Dp = 104.bd
