package com.biskyt.mytvplayer.ui.vod

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.AppContainer
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.appString
import com.biskyt.mytvplayer.data.SimilarPage
import com.biskyt.mytvplayer.data.SimilarTitle
import com.biskyt.mytvplayer.ui.LocalNavigator
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.appViewModel
import com.biskyt.mytvplayer.ui.common.ErrorRetry
import com.biskyt.mytvplayer.ui.common.Muted
import com.biskyt.mytvplayer.ui.theme.BoardStyle
import com.biskyt.mytvplayer.ui.theme.SEMI_BOLD
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

// "More like this" (the owner, 2026-11-17): the films or series that TMDB suggests for a title, made from the parts of a
// person's page.

/** The page, read once for the screen (and kept while it is on the back stack). */
class MoreLikeViewModel(private val c: AppContainer, private val screen: Screen.MoreLike) : ViewModel() {
    var page by mutableStateOf<SimilarPage?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    init {
        load()
    }

    fun load() = viewModelScope.launch {
        error = null
        try {
            page = c.similar.page(screen.playlistId, screen.series, screen.tmdbId)
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // Any failure (the database too) shows Retry: the page must never crash the app. Only TMDB's own message is shown.
            error = (e as? IOException)?.message ?: appString(R.string.vod_similar_failed)
        }
    }
}

/**
 * "More like Dune", then the suggestions in TMDB's order. OK on one that is in the playlist opens its page there; on
 * another, it opens its TMDB page (with "Find in your playlist"). With no suggestions at all, a line says so.
 */
@Composable
fun MoreLikeScreen(screen: Screen.MoreLike) {
    val vm = appViewModel { MoreLikeViewModel(it, screen) }
    val nav = LocalNavigator.current
    val page = vm.page
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
        item {
            Text(
                stringResource(R.string.vod_similar_title, screen.title),
                style = TitleText.current,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = SIDE)
            )
        }
        val error = vm.error
        val below = Modifier.padding(start = SIDE, top = FIRST_GAP)
        when {
            error != null -> item { Box(below) { ErrorRetry(error) { vm.load() } } }
            page == null -> item { Muted(stringResource(R.string.common_loading), below) }
            page.titles.isEmpty() -> item { Muted(stringResource(R.string.vod_similar_none), below) }
            else -> item { Suggestions(page.titles) { nav.push(open(screen, it)) } }
        }
    }
}

/** The suggestions in one row, which has the remote. One in the playlist says so under its card. */
@Composable
private fun Suggestions(titles: List<SimilarTitle>, open: (SimilarTitle) -> Unit) {
    PosterRow(stringResource(R.string.vod_similar_suggested), titles, focus = true, top = FIRST_GAP) { t, modifier ->
        val s = t.suggestion
        val card = t.card
        TitleCard(card?.title ?: s.title, card?.imageUrl ?: s.posterUrl, cardLine(card?.year ?: s.year, s.score, card != null), modifier) {
            open(t)
        }
    }
}

/** A suggestion's page: the playlist's, where it has the title; else TMDB's. */
private fun open(screen: Screen.MoreLike, t: SimilarTitle): Screen {
    val id = screen.playlistId
    val card = t.card ?: return Screen.TmdbTitle(id, t.suggestion.id, screen.series, t.suggestion.title, t.suggestion.posterUrl, screen.tab)
    return if (screen.series) Screen.SeriesDetails(id, card.id) else Screen.MovieDetails(id, card.id)
}

/** Under a card: "2019 · TMDB 78%", or "2019 · In your playlist" ([inPlaylist]). A part not known is left out. */
@Composable
private fun cardLine(year: Int?, score: Double?, inPlaylist: Boolean): String? = listOfNotNull(
    year?.toString(),
    if (inPlaylist) {
        stringResource(R.string.vod_similar_in_playlist)
    } else {
        score?.let {
            stringResource(R.string.vod_score_tmdb_value, percentOf(it))
        }
    },
).joinToString(" · ").ifEmpty { null }

/** Between the title and the first row. */
private val FIRST_GAP = 32.dp

private val TitleText = BoardStyle(32, SEMI_BOLD, 1.1f)
