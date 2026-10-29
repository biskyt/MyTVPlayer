package com.biskyt.mytvplayer.ui.home

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import com.biskyt.mytvplayer.AppContainer
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.data.ChannelFilter
import com.biskyt.mytvplayer.data.db.CAT_LIVE
import com.biskyt.mytvplayer.data.db.CAT_MOVIE
import com.biskyt.mytvplayer.data.db.ChannelEntity
import com.biskyt.mytvplayer.data.db.VOD_TYPES
import com.biskyt.mytvplayer.data.db.VodCard
import com.biskyt.mytvplayer.data.db.WatchProgressEntity
import com.biskyt.mytvplayer.ui.LocalNavigator
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.appViewModel
import com.biskyt.mytvplayer.ui.common.ChannelCard
import com.biskyt.mytvplayer.ui.common.FavouriteMover
import com.biskyt.mytvplayer.ui.common.ItemAction
import com.biskyt.mytvplayer.ui.common.ItemActionsPanel
import com.biskyt.mytvplayer.ui.common.KeepInView
import com.biskyt.mytvplayer.ui.common.MovableRow
import com.biskyt.mytvplayer.ui.common.MovableTile
import com.biskyt.mytvplayer.ui.common.MoveBackHandler
import com.biskyt.mytvplayer.ui.common.MoveHint
import com.biskyt.mytvplayer.ui.common.MoveLayout
import com.biskyt.mytvplayer.ui.common.Muted
import com.biskyt.mytvplayer.ui.common.NowNextLoader
import com.biskyt.mytvplayer.ui.common.OneLine
import com.biskyt.mytvplayer.ui.common.POSTER_WIDTH
import com.biskyt.mytvplayer.ui.common.PillButton
import com.biskyt.mytvplayer.ui.common.Poster
import com.biskyt.mytvplayer.ui.common.PosterCard
import com.biskyt.mytvplayer.ui.common.ProgressLine
import com.biskyt.mytvplayer.ui.common.SectionTitle
import com.biskyt.mytvplayer.ui.common.Tab
import com.biskyt.mytvplayer.ui.common.TabScaffold
import com.biskyt.mytvplayer.ui.common.TvCard
import com.biskyt.mytvplayer.ui.common.channelActions
import com.biskyt.mytvplayer.ui.common.collectIds
import com.biskyt.mytvplayer.ui.common.guideNowNext
import com.biskyt.mytvplayer.ui.common.moveAction
import com.biskyt.mytvplayer.ui.common.rememberFirstFocus
import com.biskyt.mytvplayer.ui.common.rememberMinuteClock
import com.biskyt.mytvplayer.ui.common.titledRow
import com.biskyt.mytvplayer.ui.common.vodActions
import com.biskyt.mytvplayer.ui.common.vodDetails
import com.biskyt.mytvplayer.ui.theme.Palette
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** How many favourite movies, and series, Home shows. */
private const val VOD_ROW_LIMIT = 20

/** How many favourite channels, and recently watched ones, Home shows. */
private const val CHANNEL_ROW_LIMIT = 12

class HomeViewModel(private val c: AppContainer) : ViewModel() {
    private val activePlaylist = c.settings.activePlaylistId
    val playlistId = activePlaylist.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    @OptIn(ExperimentalCoroutinesApi::class)
    val favourites = activePlaylist.flatMapLatest {
        c.channels.observeFavourites(it, CHANNEL_ROW_LIMIT)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val recent = activePlaylist.flatMapLatest {
        c.channels.observeRecent(it, CHANNEL_ROW_LIMIT)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The first favourite series and movies, by type ([VOD_TYPES]). */
    @OptIn(ExperimentalCoroutinesApi::class)
    val favouriteVod: Map<String, StateFlow<List<VodCard>>> = VOD_TYPES.associateWith { type ->
        activePlaylist.flatMapLatest { c.vod.observeFavouriteCards(it, type, VOD_ROW_LIMIT) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    }

    /** Moving a favourite within its row. */
    val mover = FavouriteMover(viewModelScope, c.favourites) { playlistId.value }

    fun setVodFavourite(type: String, id: Long, favourite: Boolean) = viewModelScope.launch {
        c.vod.setFavourite(playlistId.value, type, id, favourite)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val carryOn = activePlaylist.flatMapLatest { c.vod.observeCarryOn(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Which channels, movies and series are favourites, for their options. */
    val favouriteSets = c.favourites

    fun setFavourite(ch: ChannelEntity, favourite: Boolean) = viewModelScope.launch {
        c.channels.setFavourite(ch.playlistId, ch.streamId, favourite)
    }

    fun removeRecent(ch: ChannelEntity) = viewModelScope.launch { c.channels.removeRecent(ch.playlistId, ch.streamId) }

    fun forget(p: WatchProgressEntity) = viewModelScope.launch { c.vod.forgetProgress(p) }

    val nowNext = guideNowNext(viewModelScope, c) { playlistId.value }
}

@Composable
fun HomeScreen() {
    val vm = appViewModel { HomeViewModel(it) }
    vm.nowNext.PauseWhenHidden()
    val nav = LocalNavigator.current
    val favourites by vm.favourites.collectAsState()
    val recent by vm.recent.collectAsState()
    val carryOn by vm.carryOn.collectAsState()
    // States, read where used: shown() below must see a row change after its card is taken off.
    val favouriteVod = VOD_TYPES.associateWith { vm.favouriteVod.getValue(it).collectAsState() }
    val pid = vm.playlistId.collectAsState().value
    // Again after a card is taken off its row (focus would otherwise go with it): back to the first card.
    var refocus by remember { mutableIntStateOf(0) }
    val first = rememberFirstFocus((favourites != null) to refocus)
    var options by remember { mutableStateOf<HomeTile?>(null) }
    val scope = rememberCoroutineScope()
    fun shown(tile: HomeTile) = when (tile) {
        is HomeTile.Channel -> (
            if (tile.list ==
                ChannelFilter.Recent
            ) {
                recent
            } else {
                favourites.orEmpty()
            }
            ).any { it.streamId == tile.ch.streamId }
        is HomeTile.CarryOn -> carryOn.any { it.kind == tile.p.kind && it.itemId == tile.p.itemId }
        is HomeTile.Vod -> favouriteVod.getValue(tile.type).value.any { it.id == tile.card.id }
    }

    MoveBackHandler(vm.mover)
    TabScaffold(Tab.HOME) {
        LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 56.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp)
        ) {
            if (carryOn.isNotEmpty()) {
                item {
                    Column {
                        SectionTitle(stringResource(R.string.home_carry_on))
                        CarryOnRow(carryOn, { options = HomeTile.CarryOn(it) }) {
                            nav.push(Screen.VodPlayer(pid, it.itemId, it.seriesId, continueSeries = true))
                        }
                    }
                }
            }
            item {
                Column {
                    SectionTitle(stringResource(R.string.home_on_now))
                    MoveHint(vm.mover, CAT_LIVE, MoveLayout.ROW)
                    val favs = favourites
                    when {
                        favs == null -> Unit
                        favs.isEmpty() -> EmptyFavourites(first) { nav.reset(Screen.Live()) }
                        else -> ChannelCardRow(favs, vm.nowNext, {
                            options = HomeTile.Channel(it, ChannelFilter.Favourites)
                        }, first, vm.mover) {
                            nav.push(Screen.Player(pid, it.streamId, ChannelFilter.Favourites))
                        }
                    }
                }
            }
            if (recent.isNotEmpty()) {
                item {
                    Column {
                        SectionTitle(stringResource(R.string.common_recent))
                        ChannelCardRow(recent, vm.nowNext, { options = HomeTile.Channel(it, ChannelFilter.Recent) }) {
                            nav.push(Screen.Player(pid, it.streamId, ChannelFilter.Recent))
                        }
                    }
                }
            }
            // Favourite series, then movies.
            favouriteVod.forEach { (type, cards) ->
                titledRow(favouriteRowTitle(type), cards.value, {
                    it.id
                }, move = MovableRow(vm.mover, type)) { card ->
                    MovableTile(vm.mover, type, card.id, MoveLayout.ROW) {
                        PosterCard(card, onLongClick = { options = HomeTile.Vod(type, card) }) { nav.push(vodDetails(pid, type, card.id)) }
                    }
                }
            }
        }
    }
    options?.let { tile ->
        TileOptions(tile, vm, pid) { removed ->
            options = null
            if (removed) scope.launch { snapshotFlow { shown(tile) }.first { !it }.also { refocus++ } }
        }
    }
}

/**
 * A Home card whose options are open: a channel (from [list]: favourites or recently watched), a title carried on, or a
 * favourite movie or series.
 */
private sealed interface HomeTile {
    data class Channel(val ch: ChannelEntity, val list: ChannelFilter) : HomeTile

    data class CarryOn(val p: WatchProgressEntity) : HomeTile

    data class Vod(val type: String, val card: VodCard) : HomeTile
}

/** Holding OK on a Home card: watch it, and favourites, catch-up and the guide source, or take it off its row. */
@Composable
private fun TileOptions(tile: HomeTile, vm: HomeViewModel, pid: Long, onDismiss: (removed: Boolean) -> Unit) {
    val close = { onDismiss(false) }
    when (tile) {
        is HomeTile.Channel -> {
            val ch = tile.ch
            val favourites = vm.favouriteSets.channels.collectIds()
            val watch = stringResource(R.string.common_watch)
            val removeRecent = stringResource(R.string.home_remove_recent)
            val actions = buildList {
                add(ItemAction.Open(watch, Screen.Player(ch.playlistId, ch.streamId, tile.list)))
                addAll(
                    channelActions(ch, ch.streamId in favourites) { favourite ->
                        vm.setFavourite(ch, favourite)
                        if (!favourite && tile.list == ChannelFilter.Favourites) onDismiss(true)
                    }
                )
                if (tile.list == ChannelFilter.Favourites) add(moveAction(vm.mover, CAT_LIVE, ch.streamId, close, CHANNEL_ROW_LIMIT))
                if (tile.list == ChannelFilter.Recent) {
                    add(
                        ItemAction.Run(removeRecent) {
                            vm.removeRecent(ch)
                            onDismiss(true)
                        }
                    )
                }
            }
            ItemActionsPanel(ch.name, actions, close)
        }
        is HomeTile.CarryOn -> {
            val p = tile.p
            val series = p.seriesId
            // An episode watched, or the next one up after it.
            val next = p.finished || series != null && !p.started
            val actions = listOf(
                ItemAction.Open(
                    stringResource(if (next) R.string.home_play_next_episode else R.string.home_resume),
                    Screen.VodPlayer(p.playlistId, p.itemId, series, continueSeries = true)
                ),
                if (series == null) {
                    ItemAction.Open(stringResource(R.string.common_about_movie), Screen.MovieDetails(p.playlistId, p.itemId))
                } else {
                    ItemAction.Open(
                        stringResource(R.string.common_all_episodes),
                        Screen.SeriesDetails(p.playlistId, series, p.season, p.episode)
                    )
                },
                ItemAction.Run(
                    stringResource(R.string.home_remove_carry_on),
                    stringResource(if (series == null) R.string.home_forget_movie else R.string.home_forget_series)
                ) {
                    vm.forget(p)
                    onDismiss(true)
                }
            )
            ItemActionsPanel(p.title, actions, close)
        }
        is HomeTile.Vod -> {
            // A card on these rows is a favourite.
            val actions = vodActions(pid, tile.type, tile.card.id, favourite = true) { favourite ->
                vm.setVodFavourite(tile.type, tile.card.id, favourite)
                if (!favourite) onDismiss(true)
            } + moveAction(vm.mover, tile.type, tile.card.id, close, VOD_ROW_LIMIT)
            ItemActionsPanel(tile.card.title, actions, close)
        }
    }
}

@Composable
private fun EmptyFavourites(modifier: Modifier, onBrowse: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Muted(stringResource(R.string.home_no_favourites), maxLines = 2)
        PillButton(stringResource(R.string.home_browse_channels), onBrowse, modifier, primary = true)
    }
}

@Composable
private fun ChannelCardRow(
    channels: List<ChannelEntity>,
    loader: NowNextLoader,
    onOptions: (ChannelEntity) -> Unit,
    modifier: Modifier = Modifier,
    mover: FavouriteMover? = null,
    onPlay: (ChannelEntity) -> Unit
) {
    val now by rememberMinuteClock()
    val state = rememberLazyListState()
    KeepInView(state) { mover?.movingId(CAT_LIVE)?.let { p -> channels.indexOfFirst { it.streamId == p } } }
    LazyRow(state = state, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        items(channels, key = { it.streamId }) { c ->
            MovableTile(mover, CAT_LIVE, c.streamId, MoveLayout.ROW) {
                ChannelCard(c, loader, now, { onOptions(c) }, if (c == channels.first()) modifier else Modifier) { onPlay(c) }
            }
        }
    }
}

/** Movies and episodes part-watched (or the next episode up): a poster card each, with how much is left. */
@Composable
private fun CarryOnRow(items: List<WatchProgressEntity>, onOptions: (WatchProgressEntity) -> Unit, onPlay: (WatchProgressEntity) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        items(items, key = { "${it.kind}${it.itemId}" }) { p -> CarryOnCard(p, Modifier, { onOptions(p) }) { onPlay(p) } }
    }
}

/** A movie or episode carried on: its poster with how far it got, its title, and what's left or up next. */
@Composable
private fun CarryOnCard(p: WatchProgressEntity, modifier: Modifier, onLongClick: () -> Unit, onClick: () -> Unit) {
    TvCard(onClick = onClick, onLongClick = onLongClick, color = Palette.background, modifier = modifier) {
        Column(Modifier.padding(6.dp).width(POSTER_WIDTH), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box {
                Poster(p.imageUrl, p.title, POSTER_WIDTH)
                // (Not before it has been started: an episode up next.)
                if (!p.finished && p.started) ProgressLine(p.fraction, Modifier.align(Alignment.BottomCenter).padding(6.dp))
            }
            OneLine(p.title, MaterialTheme.typography.labelLarge)
            Muted(
                when {
                    // A finished episode with another after it that has no card of its own (saved before 1.8.2, or
                    // watched already): the card leads on to that one.
                    p.finished -> stringResource(R.string.home_up_next_after, episodeCode(p))
                    // (The next one up, its running time not known: no time.)
                    p.season != null && p.episode != null ->
                        if (p.durationMs > 0) "${episodeCode(p)} ${shortLeft(p.remainingMs)}" else episodeCode(p)
                    p.durationMs > 0 -> shortLeft(p.remainingMs)
                    else -> ""
                }
            )
        }
    }
}

/** An episode, short, as a card has room for: "S1E2". */
@Composable
private fun episodeCode(p: WatchProgressEntity) = stringResource(R.string.home_episode_code, p.season ?: 0, p.episode ?: 0)

/** Time left, short, as a card has room for: "22m", "1h", "1h 5m" (whole minutes, rounded up). */
@Composable
private fun shortLeft(ms: Long): String {
    val minutes = ((ms + MS_PER_MINUTE - 1) / MS_PER_MINUTE).toInt()
    return when {
        minutes < MINUTES_PER_HOUR -> stringResource(R.string.home_left_m, minutes)
        minutes % MINUTES_PER_HOUR == 0 -> stringResource(R.string.home_left_h, minutes / MINUTES_PER_HOUR)
        else -> stringResource(R.string.home_left_h_m, minutes / MINUTES_PER_HOUR, minutes % MINUTES_PER_HOUR)
    }
}

private const val MS_PER_MINUTE = 60_000L
private const val MINUTES_PER_HOUR = 60

/** The title of Home's row of favourite movies or series ([type]). */
@StringRes
private fun favouriteRowTitle(type: String) = if (type == CAT_MOVIE) R.string.home_favourite_movies else R.string.home_favourite_series
