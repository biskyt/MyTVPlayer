package com.biskyt.mytvplayer.ui.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowRgb565
import com.biskyt.mytvplayer.AppContainer
import com.biskyt.mytvplayer.AppText
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.core.xtream.VodDetails
import com.biskyt.mytvplayer.data.db.CAT_MOVIE
import com.biskyt.mytvplayer.data.db.MovieEntity
import com.biskyt.mytvplayer.data.db.WatchProgressEntity
import com.biskyt.mytvplayer.ui.LocalNavigator
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.appViewModel
import com.biskyt.mytvplayer.ui.common.LocalTopBarInset
import com.biskyt.mytvplayer.ui.common.Muted
import com.biskyt.mytvplayer.ui.common.PillButton
import com.biskyt.mytvplayer.ui.common.Poster
import com.biskyt.mytvplayer.ui.common.ProgressLine
import com.biskyt.mytvplayer.ui.common.favouriteLabel
import com.biskyt.mytvplayer.ui.common.formatDuration
import com.biskyt.mytvplayer.ui.common.formatRuntime
import com.biskyt.mytvplayer.ui.common.rememberFirstFocus
import com.biskyt.mytvplayer.ui.theme.Palette
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MovieDetailsViewModel(private val c: AppContainer, private val playlistId: Long, private val streamId: Long) : ViewModel() {
    var movie by mutableStateOf<MovieEntity?>(null)
        private set
    var details by mutableStateOf<VodDetails?>(null)
        private set

    val progress = c.vod.observeMovieProgress(playlistId, streamId).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val favourite = c.vod.observeFavourite(playlistId, CAT_MOVIE, streamId).stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        viewModelScope.launch {
            movie = c.vod.movie(playlistId, streamId)
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
    val d = vm.details
    val progress by vm.progress.collectAsState()
    val favourite by vm.favourite.collectAsState()
    DetailsLayout(backdropUrl = d?.backdropUrl, posterUrl = movie.posterUrl ?: d?.posterUrl, title = movie.title) {
        Text(movie.title, style = MaterialTheme.typography.displaySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Muted(metaLine(movie.year ?: d?.year, d?.durationSec, d?.genre, movie.rating ?: d?.rating))
        d?.plot?.let { Text(it, style = MaterialTheme.typography.bodyLarge, maxLines = 6, overflow = TextOverflow.Ellipsis) }
        d?.director?.let { Muted(stringResource(R.string.vod_director, it)) }
        d?.cast?.let { Muted(stringResource(R.string.vod_cast, it), maxLines = 2) }
        ResumeBar(progress)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
            val resumable = progress?.takeUnless { it.finished }
            PillButton(
                resumable?.let { stringResource(R.string.vod_resume_from, formatDuration(it.positionMs)) }
                    ?: stringResource(R.string.common_play),
                { nav.push(Screen.VodPlayer(screen.playlistId, movie.streamId)) },
                rememberFirstFocus(),
                primary = true
            )
            if (resumable != null) {
                PillButton(stringResource(R.string.common_from_start), {
                    nav.push(Screen.VodPlayer(screen.playlistId, movie.streamId, fromStart = true))
                })
            }
            PillButton(favouriteLabel(favourite), { vm.toggleFavourite() })
        }
    }
}

/** "2021 · 2 h 35 min · Drama · ★ 8.1", skipping whatever is unknown. */
fun metaLine(year: Int?, durationSec: Int?, genre: String?, rating: Double?): String = listOfNotNull(
    year?.toString(),
    durationSec?.takeIf { it > 0 }?.let(::formatRuntime),
    genre,
    rating?.let { "★ %.1f".format(AppText.locale, it) }
).joinToString(" · ")

@Composable
internal fun ResumeBar(progress: WatchProgressEntity?) {
    if (progress == null || progress.finished || progress.durationMs <= 0) return
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.widthIn(max = 520.dp)) {
        ProgressLine(progress.fraction, Modifier.weight(1f).padding(top = 8.dp))
        Muted(stringResource(R.string.common_time_left, formatDuration(progress.remainingMs)))
    }
}

/** Backdrop faded behind, poster on the left, [content] on the right. Shared by movies and series. */
@Composable
internal fun DetailsLayout(backdropUrl: String?, posterUrl: String?, title: String, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize()) {
        if (backdropUrl != null) {
            // Faded behind a gradient, so a third of the screen's resolution is plenty (and a ninth of the memory).
            val context = LocalContext.current
            val request = remember(backdropUrl) {
                ImageRequest.Builder(context).data(backdropUrl).size(BACKDROP_WIDTH, BACKDROP_WIDTH * 9 / 16).allowRgb565(true).build()
            }
            AsyncImage(
                request,
                null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().testTag("backdrop"),
                alpha = 0.35f
            )
            Box(
                Modifier.fillMaxSize()
                    .background(Brush.horizontalGradient(listOf(Palette.background, Palette.background.copy(alpha = 0.6f))))
            )
        }
        Row(
            Modifier.padding(start = 72.dp, end = 72.dp, top = LocalTopBarInset.current + 8.dp, bottom = 56.dp),
            horizontalArrangement = Arrangement.spacedBy(40.dp)
        ) {
            Poster(posterUrl, title, 240.dp)
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.widthIn(max = 760.dp).fillMaxHeight()) {
                content()
            }
        }
    }
}

private const val BACKDROP_WIDTH = 640
