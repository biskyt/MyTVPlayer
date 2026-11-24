package com.biskyt.mytvplayer.ui.vod

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.AppContainer
import com.biskyt.mytvplayer.AppText
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.core.tmdb.TmdbTitle
import com.biskyt.mytvplayer.core.vod.CastMember
import com.biskyt.mytvplayer.core.vod.DownloadNames
import com.biskyt.mytvplayer.core.xtream.VodDetails
import com.biskyt.mytvplayer.data.db.CAT_MOVIE
import com.biskyt.mytvplayer.data.db.CategoryEntity
import com.biskyt.mytvplayer.data.db.MovieEntity
import com.biskyt.mytvplayer.data.db.WatchProgressEntity
import com.biskyt.mytvplayer.data.label
import com.biskyt.mytvplayer.ui.LocalNavigator
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.appViewModel
import com.biskyt.mytvplayer.ui.common.AccentFillSurface
import com.biskyt.mytvplayer.ui.common.CastPanel
import com.biskyt.mytvplayer.ui.common.CirclePhoto
import com.biskyt.mytvplayer.ui.common.ItemActionsPanel
import com.biskyt.mytvplayer.ui.common.LocalTopBarInset
import com.biskyt.mytvplayer.ui.common.Poster
import com.biskyt.mytvplayer.ui.common.Tab
import com.biskyt.mytvplayer.ui.common.TrailersPanel
import com.biskyt.mytvplayer.ui.common.cardColor
import com.biskyt.mytvplayer.ui.common.favouriteAction
import com.biskyt.mytvplayer.ui.common.formatDate
import com.biskyt.mytvplayer.ui.common.formatRuntime
import com.biskyt.mytvplayer.ui.common.onOptionsKey
import com.biskyt.mytvplayer.ui.common.rememberFirstFocus
import com.biskyt.mytvplayer.ui.common.rememberTrailerOpener
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

class MovieDetailsViewModel(private val c: AppContainer, val playlistId: Long, private val streamId: Long) : ViewModel() {
    var movie by mutableStateOf<MovieEntity?>(null)
        private set
    var details by mutableStateOf<VodDetails?>(null)
        private set

    /** Starts downloading the film (see Downloads): where it goes, or null if it can't be. */
    suspend fun download(): DownloadNames.Place? {
        val m = movie ?: return null
        val p = c.playlists.get(playlistId) ?: return null
        val place = DownloadNames.movie(m.title, m.year, m.extension)
        c.startDownload(c.vod.movieUrl(p, m), place)
        return place
    }

    /** What TMDB knows of it: null without a TMDB key, until it is read, or when TMDB has nothing. */
    var tmdb by mutableStateOf<TmdbTitle?>(null)
        private set

    /** What the page shows: the provider's details first, then TMDB's. */
    val info by derivedStateOf { merge(details, tmdb) }

    /** The film's category, shown when the provider gives no genre (an M3U film). Named when shown. */
    var category by mutableStateOf<CategoryEntity?>(null)
        private set

    val progress = c.vod.observeMovieProgress(playlistId, streamId).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val favourite = c.vod.observeFavourite(playlistId, CAT_MOVIE, streamId).stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        c.progressSync.syncSoon()
        viewModelScope.launch {
            val m = c.vod.movie(playlistId, streamId) ?: return@launch
            movie = m
            category = c.db.categories().get(playlistId, CAT_MOVIE, m.categoryId)
            // The page works without details (title, poster and play come from the list).
            details = c.playlists.get(playlistId)?.let { p -> runCatching { c.vod.movieDetails(p, streamId) }.getOrNull() }
            // After the details: they may give its TMDB id.
            tmdb = c.tmdb.movie(m.title, details)
        }
    }

    fun toggleFavourite() = viewModelScope.launch { c.vod.setFavourite(playlistId, CAT_MOVIE, streamId, !favourite.value) }
}

/** The panels over the film page. */
private enum class FilmPanel { NONE, MORE, CAST, TRAILERS }

@Composable
fun MovieDetailsScreen(screen: Screen.MovieDetails) {
    val vm = appViewModel { MovieDetailsViewModel(it, screen.playlistId, screen.streamId) }
    val nav = LocalNavigator.current
    val movie = vm.movie ?: return
    val progress by vm.progress.collectAsState()
    // What is open over the page: More… (its button, holding OK, or Menu), or a panel opened from it. Kept with the
    // page, as is who had the remote in Cast and crew: back from a person's page, the panel shows them again.
    var open by rememberSaveable { mutableStateOf(FilmPanel.NONE) }
    var castFocus by rememberSaveable { mutableIntStateOf(0) }
    val trailer = rememberTrailerOpener()
    val info = vm.info
    val playTrailer = info.trailer?.let { url -> { trailer(url) } }
    FilmPage(
        movie,
        info,
        vm.category?.label,
        progress?.takeUnless { it.finished },
        play = { fromStart -> nav.push(Screen.VodPlayer(screen.playlistId, movie.streamId, fromStart = fromStart)) },
        more = { open = FilmPanel.MORE },
        openPerson = { person -> personScreen(screen.playlistId, person, movie.title, Tab.MOVIES, acting = true)?.let(nav::push) },
        playTrailer = playTrailer
    )
    val openCrew: (Int, CastMember) -> Unit = { i, person ->
        castFocus = i
        val acting = i < info.cast.size
        personScreen(screen.playlistId, person, movie.title, Tab.MOVIES, acting)?.let(nav::push)
    }
    // Cast and crew opens on its first person. Back from a person's page, it opens on that person.
    val show = { panel: FilmPanel ->
        if (panel == FilmPanel.CAST) castFocus = 0
        open = panel
    }
    val moreLike = { tmdbId: Int -> nav.push(Screen.MoreLike(screen.playlistId, tmdbId, series = false, movie.title, Tab.MOVIES)) }
    FilmPanels(vm, movie, open, castFocus, openCrew, playTrailer, moreLike, show)
}

/**
 * The panel [open] over the film page ([show] changes it): its own composable, so the page does not recompose with it.
 * Cast and crew has the remote on person [castFocus]; OK on one opens their page ([openPerson]). More like this opens
 * [moreLike] for the film's TMDB id.
 */
@Composable
@Suppress("LongParameterList") // the page's state, passed down rather than read again
private fun FilmPanels(
    vm: MovieDetailsViewModel,
    movie: MovieEntity,
    open: FilmPanel,
    castFocus: Int,
    openPerson: (Int, CastMember) -> Unit,
    playTrailer: (() -> Unit)?,
    moreLike: (Int) -> Unit,
    show: (FilmPanel) -> Unit
) {
    val info = vm.info
    val tmdb = info.tmdb
    val close = { show(FilmPanel.NONE) }
    when (open) {
        FilmPanel.NONE -> Unit
        FilmPanel.MORE -> {
            val favourite by vm.favourite.collectAsState()
            val download = downloadAction(vm.playlistId) { vm.download() }
            val actions = listOfNotNull(favouriteAction(favourite) { vm.toggleFavourite() }, download) + titleActions(
                tmdb,
                film = true,
                playTrailer,
                otherTrailers = { show(FilmPanel.TRAILERS) },
                showCast = { show(FilmPanel.CAST) },
                showSimilar = moreLike
            )
            ItemActionsPanel(movie.title, actions, close, panelLine(movie.year ?: info.year, info.genre))
        }
        FilmPanel.CAST -> {
            val crew = remember(tmdb) { castAndCrew(tmdb) }
            CastPanel(movie.title, crew, stringResource(R.string.vod_cast_crew), castFocus, { openPerson(it, crew[it]) }, close)
        }
        FilmPanel.TRAILERS -> TrailersPanel(movie.title, info.trailers, close)
    }
}

/**
 * The film page (MyPlexPlayer's board "PlexFilm"): the backdrop, the poster, then the title, the facts (with TMDB's
 * release date in place of the year), the original title (when TMDB's differs), TMDB's score, the plot, who directed and wrote it, and the buttons; the cast along the bottom (OK on one opens their page,
 * [openPerson]). A line with no data is left out
 * ("TvFilmMinimal": an M3U film shows its category in place of the facts). [info] has the provider's data and TMDB's
 * (see [merge]). The remote's Menu button opens [more] from anywhere on it. [playTrailer] is null when there is no trailer.
 */
@Composable
@Suppress("LongParameterList") // the page's state, passed down rather than read again
fun FilmPage(
    movie: MovieEntity,
    info: TitleInfo,
    category: String?,
    resumable: WatchProgressEntity?,
    play: (fromStart: Boolean) -> Unit,
    more: () -> Unit,
    openPerson: (CastMember) -> Unit,
    playTrailer: (() -> Unit)?
) {
    val cast = info.cast
    Box(Modifier.fillMaxSize().onOptionsKey(more)) {
        Backdrop(info.backdropUrl)
        Row(
            Modifier.padding(start = 56.bd, end = 56.bd, top = LocalTopBarInset.current),
            horizontalArrangement = Arrangement.spacedBy(32.bd)
        ) {
            Poster(movie.posterUrl ?: info.posterUrl, movie.title, 180.bd, radius = 10.bd)
            Column(Modifier.widthIn(max = 640.bd), verticalArrangement = Arrangement.spacedBy(8.bd)) {
                Text(movie.title, style = FilmTitle.current, maxLines = 2, overflow = TextOverflow.Ellipsis)
                // With no genre (an M3U film), its category says what it is: "1958 · Movies: Classics". TMDB's release
                // date, when known, in place of the year (the owner, 2026-11-24).
                val released = info.tmdb?.released?.let(::formatDate) ?: (movie.year ?: info.year)?.toString()
                val facts = metaLine(released, info.durationSec, info.genre ?: category, movie.rating ?: info.rating)
                if (facts.isNotEmpty()) {
                    Text(
                        facts,
                        style = FilmFacts.current,
                        color = Palette.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                val original = originalTitle(movie.title, info.tmdb)
                original?.let {
                    Text(
                        stringResource(R.string.vod_original_title_is, it),
                        style = SmallMuted.current,
                        color = Palette.textDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                info.score?.let { TmdbScore(it) }
                // Fewer lines with the cast along the bottom, so that the buttons stay above it: one fewer again under
                // the original title's line.
                info.plot?.let {
                    val lines = if (cast.isEmpty()) {
                        6
                    } else if (original == null) {
                        3
                    } else {
                        2
                    }
                    PlotWithMore(movie.title, it, FilmPlot.current, maxLines = lines)
                }
                people(info)?.let {
                    Text(it, style = SmallMuted.current, color = Palette.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                // Without TMDB's cast (with their photos, along the bottom), the provider's names.
                info.castNames?.let {
                    Text(
                        stringResource(R.string.vod_cast, it),
                        style = SmallMuted.current,
                        color = Palette.textDim,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(Modifier.padding(top = 10.bd), horizontalArrangement = Arrangement.spacedBy(10.bd)) {
                    FilmButtons(resumable, play, more, playTrailer)
                }
            }
        }
        if (cast.isNotEmpty()) {
            CastStrip(
                cast,
                openPerson,
                Modifier.align(Alignment.BottomStart).padding(start = 56.bd, end = 40.bd, bottom = 14.bd - CARD_PAD)
            )
        }
    }
}

/**
 * With progress: "Resume · 38 min left" (the progress along its bottom), Restart, Trailer, More…. Without: Play, Trailer,
 * More…. Trailer only when there is one. Holding OK on any of them opens [more] too.
 */
@Composable
private fun FilmButtons(resumable: WatchProgressEntity?, play: (Boolean) -> Unit, more: () -> Unit, playTrailer: (() -> Unit)?) {
    val resume = resumable?.takeIf { it.durationMs > 0 }
    // One button either way, so the focus it has stays when the progress loads after the page.
    DetailButton(
        resume?.let { stringResource(R.string.vod_resume_left, timeLeft(it)) }
            ?: stringResource(R.string.common_play),
        { play(false) },
        rememberFirstFocus(),
        strong = true,
        progress = resume?.fraction,
        onLongClick = more
    )
    if (resume != null) DetailButton(stringResource(R.string.vod_restart), { play(true) }, onLongClick = more)
    playTrailer?.let { DetailButton(stringResource(R.string.vod_trailer), it, onLongClick = more) }
    DetailButton(stringResource(R.string.vod_more), more, onLongClick = more)
}

/**
 * The cast along the bottom of the page: "Cast", then each one's photo (92 square), name and part, 20 apart. OK on one
 * opens their page ([open]). Focus fills the card with the accent colour, with no ring.
 */
@Composable
private fun CastStrip(cast: List<CastMember>, open: (CastMember) -> Unit, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.bd)) {
        Text(stringResource(R.string.vod_cast_title), style = CastHeading.current, color = Palette.textMuted)
        // Each card keeps CARD_PAD round it for its focus, taken back from the gap and the row's start.
        LazyRow(
            Modifier.offset(x = -CARD_PAD),
            horizontalArrangement = Arrangement.spacedBy(20.bd - CARD_PAD * 2),
            contentPadding = PaddingValues(end = CARD_PAD)
        ) {
            // Keyed by place and name: a name can be in the cast twice.
            itemsIndexed(cast, key = { i, person -> "$i ${person.name}" }) { _, person -> CastCard(person) { open(person) } }
        }
    }
}

@Composable
private fun CastCard(person: CastMember, onClick: () -> Unit) {
    AccentFillSurface(onClick, CardShape) {
        Column(Modifier.padding(CARD_PAD).width(92.bd), verticalArrangement = Arrangement.spacedBy(4.bd)) {
            CirclePhoto(person.photoUrl, person.name, 92.bd, shape = PhotoShape)
            // The card's content colour: the text's, or the accent's while it has focus; the part is muted without.
            Text(person.name, style = CastName.current, color = LocalContentColor.current, maxLines = 1, overflow = TextOverflow.Ellipsis)
            person.role?.let {
                Text(it, style = CastRole.current, color = cardColor(Palette.textDim), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private val CardShape = RoundedCornerShape(12.bd)
private val PhotoShape = RoundedCornerShape(10.bd)
private val CARD_PAD = 4.bd

/** Under More…'s title: "2021 · Drama, Mystery". */
private fun panelLine(year: Int?, genre: String?): String? = listOfNotNull(year?.toString(), genre).joinToString(" · ").ifEmpty { null }

// The film page's text (the boards' sizes).
private val FilmTitle = BoardStyle(32, EXTRA_BOLD, 1.1f)
private val FilmFacts = BoardStyle(15, BOLD)
private val FilmPlot = BoardStyle(15, MEDIUM, 1.4f)

/** Directed and written by, and the provider's cast: 14, muted. */
private val SmallMuted = BoardStyle(14, MEDIUM)
private val CastHeading = BoardStyle(14, EXTRA_BOLD)
private val CastName = BoardStyle(13, EXTRA_BOLD, 1.2f)
private val CastRole = BoardStyle(12, MEDIUM, 1.2f)

/** A film's original title, from TMDB, when it is not the [title] shown. */
fun originalTitle(title: String, tmdb: TmdbTitle?): String? = tmdb?.originalTitle?.takeUnless { it.equals(title, ignoreCase = true) }

/**
 * "2021 · 2 h 35 min · Drama · ★ 8.1", skipping whatever is unknown; a series' [seasons] ("3 seasons") after the year.
 * [released] is the year, or a film's release date ("15 Sept 2021").
 */
fun metaLine(released: String?, durationSec: Int?, genre: String?, rating: Double?, seasons: String? = null): String = listOfNotNull(
    released,
    seasons,
    durationSec?.takeIf { it > 0 }?.let(::formatRuntime),
    genre,
    rating?.let { "★ %.1f".format(AppText.locale, it) }
).joinToString(" · ")
