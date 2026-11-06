package com.biskyt.mytvplayer.ui.vod

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.AppContainer
import com.biskyt.mytvplayer.AppText
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.core.xtream.VodDetails
import com.biskyt.mytvplayer.data.db.CAT_MOVIE
import com.biskyt.mytvplayer.data.db.CategoryEntity
import com.biskyt.mytvplayer.data.db.MovieEntity
import com.biskyt.mytvplayer.data.db.WatchProgressEntity
import com.biskyt.mytvplayer.data.label
import com.biskyt.mytvplayer.ui.LocalNavigator
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.appViewModel
import com.biskyt.mytvplayer.ui.common.LocalTopBarInset
import com.biskyt.mytvplayer.ui.common.Poster
import com.biskyt.mytvplayer.ui.common.formatRuntime
import com.biskyt.mytvplayer.ui.common.rememberFirstFocus
import com.biskyt.mytvplayer.ui.common.timeLeft
import com.biskyt.mytvplayer.ui.theme.BOLD
import com.biskyt.mytvplayer.ui.theme.BoardStyle
import com.biskyt.mytvplayer.ui.theme.EXTRA_BOLD
import com.biskyt.mytvplayer.ui.theme.MEDIUM
import com.biskyt.mytvplayer.ui.theme.Palette
import com.biskyt.mytvplayer.ui.theme.bd
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MovieDetailsViewModel(private val c: AppContainer, private val playlistId: Long, private val streamId: Long) : ViewModel() {
    var movie by mutableStateOf<MovieEntity?>(null)
        private set
    var details by mutableStateOf<VodDetails?>(null)
        private set

    /** The film's category, shown when the provider gives no genre (an M3U film). Named when shown. */
    var category by mutableStateOf<CategoryEntity?>(null)
        private set

    val progress = c.vod.observeMovieProgress(playlistId, streamId).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val favourite = c.vod.observeFavourite(playlistId, CAT_MOVIE, streamId).stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        c.progressSync.syncSoon()
        viewModelScope.launch {
            val m = c.vod.movie(playlistId, streamId)
            movie = m
            category = m?.let { c.db.categories().get(playlistId, CAT_MOVIE, it.categoryId) }
            val p = c.playlists.get(playlistId) ?: return@launch
            // The page works without details (title, poster and play come from the list).
            details = runCatching { c.vod.movieDetails(p, streamId) }.getOrNull()
        }
    }

    fun toggleFavourite() = viewModelScope.launch { c.vod.setFavourite(playlistId, CAT_MOVIE, streamId, !favourite.value) }
}

@Composable
fun MovieDetailsScreen(screen: Screen.MovieDetails) {
    val vm = appViewModel { MovieDetailsViewModel(it, screen.playlistId, screen.streamId) }
    val nav = LocalNavigator.current
    val movie = vm.movie ?: return
    val progress by vm.progress.collectAsState()
    val favourite by vm.favourite.collectAsState()
    FilmPage(
        movie,
        vm.details,
        vm.category?.label,
        progress?.takeUnless { it.finished },
        favourite,
        play = { fromStart -> nav.push(Screen.VodPlayer(screen.playlistId, movie.streamId, fromStart = fromStart)) },
        toggleFavourite = { vm.toggleFavourite() }
    )
}

/**
 * The film page (board "Main"): the backdrop, the poster, then the title, the facts, the plot, the director, the cast
 * and the buttons. A line with no data is left out ("TvFilmMinimal": an M3U film shows its category in place of the
 * facts). [extraFacts] and [extraButtons] are where MyPlexPlayer adds its own.
 */
@Composable
fun FilmPage(
    movie: MovieEntity,
    d: VodDetails?,
    category: String?,
    resumable: WatchProgressEntity?,
    favourite: Boolean,
    play: (fromStart: Boolean) -> Unit,
    toggleFavourite: () -> Unit,
    extraFacts: @Composable ColumnScope.() -> Unit = {},
    extraButtons: @Composable RowScope.() -> Unit = {}
) {
    Box(Modifier.fillMaxSize()) {
        Backdrop(d?.backdropUrl)
        Row(
            Modifier.padding(start = 56.bd, end = 56.bd, top = LocalTopBarInset.current, bottom = 28.bd),
            horizontalArrangement = Arrangement.spacedBy(32.bd)
        ) {
            Poster(movie.posterUrl ?: d?.posterUrl, movie.title, 200.bd, radius = 10.bd)
            Column(Modifier.widthIn(max = 600.bd), verticalArrangement = Arrangement.spacedBy(10.bd)) {
                Text(movie.title, style = FilmTitle.current, maxLines = 2, overflow = TextOverflow.Ellipsis)
                // With no genre (an M3U film), its category says what it is: "1958 · Movies: Classics".
                val facts = metaLine(movie.year ?: d?.year, d?.durationSec, d?.genre ?: category, movie.rating ?: d?.rating)
                if (facts.isNotEmpty()) {
                    Text(
                        facts,
                        style = FilmFacts.current,
                        color = Palette.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                extraFacts()
                d?.plot?.let { PlotWithMore(movie.title, it, FilmPlot.current, maxLines = 6) }
                d?.director?.let {
                    Text(stringResource(R.string.vod_director, it), style = SmallMuted.current, color = Palette.textDim, maxLines = 1)
                }
                d?.cast?.let {
                    Text(
                        stringResource(R.string.vod_cast, it),
                        style = SmallMuted.current,
                        color = Palette.textDim,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(Modifier.padding(top = 8.bd), horizontalArrangement = Arrangement.spacedBy(10.bd)) {
                    FilmButtons(resumable, favourite, play, toggleFavourite)
                    extraButtons()
                }
            }
        }
    }
}

/** With progress: "Resume · 38 min left" (the progress along its bottom), Restart, Favourite. Without: Play, Favourite. */
@Composable
private fun FilmButtons(resumable: WatchProgressEntity?, favourite: Boolean, play: (Boolean) -> Unit, toggleFavourite: () -> Unit) {
    val resume = resumable?.takeIf { it.durationMs > 0 }
    // One button either way, so the focus it has stays when the progress loads after the page.
    DetailButton(
        resume?.let { stringResource(R.string.vod_resume_left, timeLeft(it)) }
            ?: stringResource(R.string.common_play),
        { play(false) },
        rememberFirstFocus(),
        strong = true,
        progress = resume?.fraction
    )
    if (resume != null) DetailButton(stringResource(R.string.vod_restart), { play(true) })
    FavouriteButton(favourite, toggleFavourite, text = stringResource(R.string.common_favourite_mark))
}

// The film page's text (the boards' sizes).
private val FilmTitle = BoardStyle(30, EXTRA_BOLD, 1.15f)
private val FilmFacts = BoardStyle(15, BOLD)
private val FilmPlot = BoardStyle(16, MEDIUM, 1.4f)

/** Director and cast: 14, muted. */
private val SmallMuted = BoardStyle(14, MEDIUM)

/** "2021 · 2 h 35 min · Drama · ★ 8.1", skipping whatever is unknown; a series' [seasons] ("3 seasons") after the year. */
fun metaLine(year: Int?, durationSec: Int?, genre: String?, rating: Double?, seasons: String? = null): String = listOfNotNull(
    year?.toString(),
    seasons,
    durationSec?.takeIf { it > 0 }?.let(::formatRuntime),
    genre,
    rating?.let { "★ %.1f".format(AppText.locale, it) }
).joinToString(" · ")
