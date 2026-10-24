package com.biskyt.mytvplayer.ui.search

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import com.biskyt.mytvplayer.AppContainer
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.data.ChannelFilter
import com.biskyt.mytvplayer.data.SearchResults
import com.biskyt.mytvplayer.data.db.CAT_MOVIE
import com.biskyt.mytvplayer.data.db.CAT_SERIES
import com.biskyt.mytvplayer.data.db.ChannelEntity
import com.biskyt.mytvplayer.data.db.ProgrammeHit
import com.biskyt.mytvplayer.data.db.VodCard
import com.biskyt.mytvplayer.data.programmeTitle
import com.biskyt.mytvplayer.ui.LocalNavigator
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.appViewModel
import com.biskyt.mytvplayer.ui.common.AppIcons
import com.biskyt.mytvplayer.ui.common.ChannelCard
import com.biskyt.mytvplayer.ui.common.ChannelLogo
import com.biskyt.mytvplayer.ui.common.ItemAction
import com.biskyt.mytvplayer.ui.common.ItemActionsPanel
import com.biskyt.mytvplayer.ui.common.Muted
import com.biskyt.mytvplayer.ui.common.OnNowLabel
import com.biskyt.mytvplayer.ui.common.OneLine
import com.biskyt.mytvplayer.ui.common.PosterCard
import com.biskyt.mytvplayer.ui.common.SectionTitle
import com.biskyt.mytvplayer.ui.common.TabScaffold
import com.biskyt.mytvplayer.ui.common.TvCard
import com.biskyt.mytvplayer.ui.common.TvTextField
import com.biskyt.mytvplayer.ui.common.channelActions
import com.biskyt.mytvplayer.ui.common.collectIds
import com.biskyt.mytvplayer.ui.common.formatDayTime
import com.biskyt.mytvplayer.ui.common.guideNowNext
import com.biskyt.mytvplayer.ui.common.isLive
import com.biskyt.mytvplayer.ui.common.rememberMinuteClock
import com.biskyt.mytvplayer.ui.common.titledRow
import com.biskyt.mytvplayer.ui.common.vodActions
import com.biskyt.mytvplayer.ui.common.vodDetails
import com.biskyt.mytvplayer.ui.theme.Palette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class SearchViewModel(private val c: AppContainer, initialQuery: String = "") : ViewModel() {
    val query = MutableStateFlow(initialQuery)

    /** What was searched, in which playlist, and what was found (null: too short to search; see [failed]). */
    data class Found(val playlistId: Long = 0, val query: String = "", val results: SearchResults? = null, val failed: Boolean = false)

    /** Results for the query once typing pauses; the previous results stay up meanwhile. */
    val found: StateFlow<Found> =
        combine(c.settings.activePlaylistId, query.debounce(300).map { it.trim() }.distinctUntilChanged()) { pid, q -> pid to q }
            .mapLatest { (pid, q) ->
                // A failed search (e.g. the database busy rebuilding) is shown, never allowed to end searching.
                try {
                    Found(pid, q, c.search.search(pid, q))
                } catch (e: CancellationException) {
                    throw e
                } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                    Log.w("Search", "Search failed", e)
                    Found(pid, q, failed = true)
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Found())

    private val playlistId = c.settings.activePlaylistId.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    /** What's on the channels found; read again for another playlist (switched to while Search waited behind). */
    val nowNext = guideNowNext(viewModelScope, c) { playlistId.value }

    init {
        viewModelScope.launch { playlistId.drop(1).collect { nowNext.refreshAll() } }
    }

    /** The active playlist's favourites: marked on the results, and for the options panel (null until read). */
    val favourites = c.favourites

    fun setChannelFavourite(pid: Long, streamId: Long, favourite: Boolean) =
        viewModelScope.launch { c.channels.setFavourite(pid, streamId, favourite) }

    fun setVodFavourite(pid: Long, type: String, id: Long, favourite: Boolean) =
        viewModelScope.launch { c.vod.setFavourite(pid, type, id, favourite) }

    /** The channel a programme is on (for catch-up in its options). */
    suspend fun channel(pid: Long, streamId: Long) = c.channels.get(pid, streamId)
}

/** A search result whose options panel is open. */
private sealed interface Picked {
    data class Channel(val streamId: Long, val name: String) : Picked
    data class Vod(val type: String, val card: VodCard) : Picked
}

/** One search box for channels, series, movies and TV programmes (shown in that order). */
@Composable
fun SearchScreen(initialQuery: String = "") {
    val vm = appViewModel { SearchViewModel(it, initialQuery) }
    vm.nowNext.PauseWhenHidden()
    val nav = LocalNavigator.current
    val query by vm.query.collectAsState()
    val found = vm.found.collectAsState().value
    val pid = found.playlistId
    val searched = found.query
    val results = found.results
    val now by rememberMinuteClock()
    val favouriteChannels = vm.favourites.channels.collectIds()
    val favouriteSeries = vm.favourites.series.collectIds()
    val favouriteMovies = vm.favourites.movies.collectIds()
    // The keyboard is up on arrival, ready to type.
    val editing = rememberSaveable { mutableStateOf(true) }
    var picked by remember { mutableStateOf<Picked?>(null) }
    TabScaffold(null) {
        LazyColumn(
            contentPadding = PaddingValues(start = 56.dp, end = 56.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item {
                TvTextField(
                    value = query,
                    onValueChange = { vm.query.value = it },
                    placeholder = stringResource(R.string.search_placeholder),
                    modifier = Modifier.fillMaxWidth(),
                    leading = { Icon(AppIcons.Search, null, tint = Palette.textDim, modifier = Modifier.size(20.dp)) },
                    editing = editing
                )
            }
            when {
                searched.isEmpty() -> Unit
                found.failed -> item { Muted(stringResource(R.string.search_failed)) }
                results == null -> item { Muted(stringResource(R.string.search_keep_typing)) }
                results.isEmpty -> item { Muted(stringResource(R.string.search_nothing_found, searched)) }
                else -> {
                    // Channels, series, films, then TV programmes. Holding OK (or the remote's Menu button) on a result
                    // opens its options.
                    titledRow(R.string.common_channels, results.channels, { it.streamId }, SECTION_GAP) {
                        ChannelCard(
                            it,
                            vm.nowNext,
                            now,
                            { picked = Picked.Channel(it.streamId, it.name) },
                            favourite =
                            it.streamId in favouriteChannels
                        ) {
                            nav.push(Screen.Player(pid, it.streamId, ChannelFilter.All))
                        }
                    }
                    titledRow(R.string.common_tab_series, results.series, { it.id }, SECTION_GAP) {
                        PosterCard(it, favourite = it.id in favouriteSeries, onLongClick = { picked = Picked.Vod(CAT_SERIES, it) }) {
                            nav.push(vodDetails(pid, CAT_SERIES, it.id))
                        }
                    }
                    titledRow(R.string.common_tab_movies, results.movies, { it.id }, SECTION_GAP) {
                        PosterCard(it, favourite = it.id in favouriteMovies, onLongClick = { picked = Picked.Vod(CAT_MOVIE, it) }) {
                            nav.push(vodDetails(pid, CAT_MOVIE, it.id))
                        }
                    }
                    // TV programmes last: often a long list, it would push films and series out of sight.
                    if (results.programmes.isNotEmpty()) {
                        item { SectionTitle(stringResource(R.string.search_on_tv), Modifier.padding(top = SECTION_GAP)) }
                        items(results.programmes, key = { "p${it.streamId}:${it.startMs}" }) { p ->
                            ProgrammeRow(p, now, { picked = Picked.Channel(p.streamId, p.channelName) }) {
                                nav.push(Screen.Player(pid, p.streamId, ChannelFilter.All))
                            }
                        }
                    }
                }
            }
        }
    }
    picked?.let { ResultOptions(it, pid, vm) { picked = null } }
}

/** What can be done with a result: watch or open it, favourites, and for channels catch-up and the guide source. */
@Composable
private fun ResultOptions(item: Picked, pid: Long, vm: SearchViewModel, onDismiss: () -> Unit) {
    val (title, actions) = when (item) {
        is Picked.Channel -> {
            val favourites by vm.favourites.channels.collectAsState()
            val channel by produceState<ChannelEntity?>(null, item) { value = vm.channel(pid, item.streamId) }
            item.name to
                listOf(ItemAction.Open(stringResource(R.string.common_watch), Screen.Player(pid, item.streamId, ChannelFilter.All))) +
                // Once both are read: a label saying "Add" for a favourite would undo it.
                channel?.let { ch ->
                    favourites?.let { f -> channelActions(ch, ch.streamId in f) { vm.setChannelFavourite(pid, ch.streamId, it) } }
                }.orEmpty()
        }
        is Picked.Vod -> {
            val favourites by vm.favourites.of(item.type).collectAsState()
            item.card.title to vodActions(pid, item.type, item.card.id, favourites?.let { item.card.id in it }) {
                vm.setVodFavourite(pid, item.type, item.card.id, it)
            }
        }
    }
    ItemActionsPanel(title, actions, onDismiss)
}

@Composable
private fun ProgrammeRow(p: ProgrammeHit, now: Long, onLongClick: () -> Unit, onClick: () -> Unit) {
    TvCard(
        onClick = onClick,
        onLongClick = onLongClick,
        color = Palette.surfaceLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            ChannelLogo(p.logoUrl, p.channelName, 56.dp, 34.dp)
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                OneLine(programmeTitle(p.title), MaterialTheme.typography.titleSmall)
                OneLine(
                    listOfNotNull(p.channelName, p.subtitle).joinToString(" · "),
                    MaterialTheme.typography.bodyMedium,
                    color = Palette.textMuted
                )
            }
            if (isLive(p.startMs, p.stopMs, now)) OnNowLabel() else Muted(formatDayTime(p.startMs, now))
        }
    }
}

private val SECTION_GAP = 18.dp
