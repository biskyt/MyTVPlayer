package com.biskyt.mytvplayer.ui.vod

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.AppContainer
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.appString
import com.biskyt.mytvplayer.core.xtream.EpisodeInfo
import com.biskyt.mytvplayer.core.xtream.SeriesDetails
import com.biskyt.mytvplayer.data.db.CAT_SERIES
import com.biskyt.mytvplayer.data.db.SeriesEntity
import com.biskyt.mytvplayer.data.db.WatchProgressEntity
import com.biskyt.mytvplayer.ui.LocalNavigator
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.appViewModel
import com.biskyt.mytvplayer.ui.common.Muted
import com.biskyt.mytvplayer.ui.common.PillButton
import com.biskyt.mytvplayer.ui.common.ProgressLine
import com.biskyt.mytvplayer.ui.common.TvCard
import com.biskyt.mytvplayer.ui.common.favouriteLabel
import com.biskyt.mytvplayer.ui.common.formatRuntime
import com.biskyt.mytvplayer.ui.common.rememberFirstFocus
import com.biskyt.mytvplayer.ui.common.restoreFocusIf
import com.biskyt.mytvplayer.ui.common.shortEpisodeLabel
import com.biskyt.mytvplayer.ui.player.episodeName
import com.biskyt.mytvplayer.ui.theme.LocalAccent
import com.biskyt.mytvplayer.ui.theme.Palette
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SeriesDetailsViewModel(
    private val c: AppContainer,
    private val playlistId: Long,
    private val seriesId: Long,
    season: Int?
) : ViewModel() {
    var series by mutableStateOf<SeriesEntity?>(null)
        private set
    var details by mutableStateOf<SeriesDetails?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** The season asked for or picked (until then, the one with the episode to carry on with). */
    var season by mutableStateOf(season)

    /** An episode to focus when the page opens (from the TV guide), until it has had focus. */
    var focusEpisodePending = true

    /** Progress per episode id. */
    val progress = c.vod.observeSeriesProgress(playlistId, seriesId).map { list -> list.associateBy { it.itemId } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    val favourite = c.vod.observeFavourite(playlistId, CAT_SERIES, seriesId).stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        load()
    }

    fun load() = viewModelScope.launch {
        error = null
        series = c.vod.series(playlistId, seriesId)
        val p = c.playlists.get(playlistId) ?: return@launch
        try {
            val d = c.vod.seriesDetails(p, seriesId)
            details = d
        } catch (e: java.io.IOException) {
            error = e.message ?: appString(R.string.vod_episodes_failed)
        }
    }

    fun toggleFavourite() = viewModelScope.launch { c.vod.setFavourite(playlistId, CAT_SERIES, seriesId, !favourite.value) }
}

/**
 * Where to carry on: the last episode watched if it isn't finished, otherwise the one after it; the first episode
 * when nothing has been watched yet.
 */
fun continueEpisode(episodes: List<EpisodeInfo>, progress: Map<Long, WatchProgressEntity>): EpisodeInfo? {
    val last = progress.values.maxByOrNull { it.updatedAt } ?: return episodes.firstOrNull()
    val index = episodes.indexOfFirst { it.id == last.itemId }
    if (index < 0) return episodes.firstOrNull()
    return if (last.finished) episodes.getOrNull(index + 1) ?: episodes[index] else episodes[index]
}

@Composable
fun SeriesDetailsScreen(screen: Screen.SeriesDetails) {
    val vm = appViewModel { SeriesDetailsViewModel(it, screen.playlistId, screen.seriesId, screen.season) }
    val nav = LocalNavigator.current
    val show = vm.series ?: return
    val d = vm.details
    val progress by vm.progress.collectAsState()
    val favourite by vm.favourite.collectAsState()
    val play = { e: EpisodeInfo -> nav.push(Screen.VodPlayer(screen.playlistId, e.id, screen.seriesId)) }

    DetailsLayout(backdropUrl = d?.info?.backdropUrl, posterUrl = show.coverUrl ?: d?.info?.posterUrl, title = show.title) {
        Text(show.title, style = MaterialTheme.typography.displaySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Muted(metaLine(show.year ?: d?.info?.year, null, d?.info?.genre, show.rating ?: d?.info?.rating))
        d?.info?.plot?.let { Text(it, style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis) }
        val next = d?.let { continueEpisode(it.episodes, progress) }
        // Opened for one episode (from the TV guide): focus goes to it rather than to Play.
        val target = d?.episodes?.firstOrNull { it.season == screen.season && it.number == screen.episode }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (next != null) {
                val started = progress.isNotEmpty()
                PillButton(
                    stringResource(
                        if (started) R.string.vod_continue_episode else R.string.vod_play_episode,
                        shortEpisodeLabel(next.season, next.number)
                    ),
                    { play(next) },
                    if (target == null || !vm.focusEpisodePending) rememberFirstFocus(next.id) else Modifier,
                    primary = true
                )
            }
            PillButton(favouriteLabel(favourite), { vm.toggleFavourite() })
        }
        when {
            vm.error != null -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Muted(vm.error.orEmpty())
                PillButton(stringResource(R.string.common_retry), { vm.load() }, if (next == null) rememberFirstFocus() else Modifier)
            }
            d == null -> Muted(stringResource(R.string.vod_loading_episodes))
            d.episodes.isEmpty() -> Muted(stringResource(R.string.vod_no_episodes))
            else -> {
                // A season the provider doesn't have (the guide may number them differently) shows the usual one.
                val asked = vm.season?.takeIf { s -> d.episodes.any { it.season == s } }
                Episodes(vm, d, asked ?: next?.season ?: d.seasons.firstOrNull()?.number, progress, target, play)
            }
        }
    }
}

@Composable
private fun Episodes(
    vm: SeriesDetailsViewModel,
    d: SeriesDetails,
    season: Int?,
    progress: Map<Long, WatchProgressEntity>,
    target: EpisodeInfo?,
    play: (EpisodeInfo) -> Unit
) {
    if (d.seasons.size > 1) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(d.seasons, key = { it.number }) { s ->
                PillButton(s.name ?: stringResource(R.string.vod_season, s.number), { vm.season = s.number }, selected = s.number == season)
            }
        }
    }
    val shown = remember(d, season) { d.episodes.filter { season == null || it.season == season } }
    val list = rememberLazyListState()
    // Scrolled into view first, so the row exists to take focus.
    LaunchedEffect(target, shown) {
        val i = shown.indexOf(target)
        if (vm.focusEpisodePending && i >= 0) list.scrollToItem(i)
    }
    LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        items(shown, key = { it.id }) { e ->
            val focus = Modifier.restoreFocusIf(vm.focusEpisodePending && e == target) { vm.focusEpisodePending = false }
            EpisodeRow(e, progress[e.id], focus) { play(e) }
        }
    }
}

@Composable
private fun EpisodeRow(e: EpisodeInfo, progress: WatchProgressEntity?, modifier: Modifier, onClick: () -> Unit) {
    TvCard(onClick = onClick, color = Palette.surfaceLow, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${e.number}", style = MaterialTheme.typography.titleSmall, color = Palette.textDim, modifier = Modifier.width(36.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(episodeName(e), style = MaterialTheme.typography.titleSmall, maxLines = 1)
                e.plot?.let { Muted(it) }
                if (progress != null && !progress.finished && progress.durationMs > 0) {
                    ProgressLine(progress.fraction, Modifier.width(220.dp).padding(top = 4.dp))
                }
            }
            val duration = e.durationSec
            when {
                progress?.finished == true -> Text(
                    stringResource(R.string.vod_watched),
                    style = MaterialTheme.typography.labelMedium,
                    color = LocalAccent.current.color
                )
                duration != null -> Muted(formatRuntime(duration))
            }
        }
    }
}
