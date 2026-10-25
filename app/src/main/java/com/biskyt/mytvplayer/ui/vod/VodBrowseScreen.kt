package com.biskyt.mytvplayer.ui.vod

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.paging.map
import com.biskyt.mytvplayer.AppContainer
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.data.VodFilter
import com.biskyt.mytvplayer.data.db.CAT_MOVIE
import com.biskyt.mytvplayer.data.db.CategoryEntity
import com.biskyt.mytvplayer.data.db.PlaylistEntity
import com.biskyt.mytvplayer.data.db.VodCard
import com.biskyt.mytvplayer.ui.LocalNavigator
import com.biskyt.mytvplayer.ui.PickedLists
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.appViewModel
import com.biskyt.mytvplayer.ui.common.CategoryEntry
import com.biskyt.mytvplayer.ui.common.CategoryFilter
import com.biskyt.mytvplayer.ui.common.FavouriteMover
import com.biskyt.mytvplayer.ui.common.FilteredCategoryColumn
import com.biskyt.mytvplayer.ui.common.ItemActionsPanel
import com.biskyt.mytvplayer.ui.common.KeepInView
import com.biskyt.mytvplayer.ui.common.ListHeader
import com.biskyt.mytvplayer.ui.common.MovableTile
import com.biskyt.mytvplayer.ui.common.MoveBackHandler
import com.biskyt.mytvplayer.ui.common.MoveHint
import com.biskyt.mytvplayer.ui.common.MoveLayout
import com.biskyt.mytvplayer.ui.common.Muted
import com.biskyt.mytvplayer.ui.common.POSTER_WIDTH
import com.biskyt.mytvplayer.ui.common.PillButton
import com.biskyt.mytvplayer.ui.common.Poster
import com.biskyt.mytvplayer.ui.common.PosterCard
import com.biskyt.mytvplayer.ui.common.Tab
import com.biskyt.mytvplayer.ui.common.TabScaffold
import com.biskyt.mytvplayer.ui.common.categoryEntries
import com.biskyt.mytvplayer.ui.common.collectIds
import com.biskyt.mytvplayer.ui.common.moveAction
import com.biskyt.mytvplayer.ui.common.openOnShown
import com.biskyt.mytvplayer.ui.common.restoreFocusIf
import com.biskyt.mytvplayer.ui.common.vodActions
import com.biskyt.mytvplayer.ui.common.vodDetails
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Movies or series ([type] is CAT_MOVIE or CAT_SERIES): categories on the left, a grid of posters on the right. */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class VodBrowseViewModel(
    private val c: AppContainer,
    val type: String,
    initialFilter: VodFilter?,
    private val picked: PickedLists
) : ViewModel() {
    private val activePlaylist = c.settings.activePlaylistId
    val isMovies = type == CAT_MOVIE

    val playlist: StateFlow<PlaylistEntity?> = activePlaylist.map { c.playlists.get(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // As Live TV: the list asked for, else the one picked earlier while the app has been open, else Favourites.
    val filters =
        CategoryFilter(viewModelScope, initialFilter ?: picked.vod[type] ?: VodFilter.Favourites, settled = initialFilter != null) {
            picked.vod[type] = it
        }

    init {
        if (initialFilter == null) {
            viewModelScope.launch {
                val pid = activePlaylist.first()
                filters.openOnShown(VodFilter.Favourites, VodFilter.All, gone = { f ->
                    f is VodFilter.Category && c.vod.observeCategories(pid, type).first().none { it.categoryId == f.id }
                }) { c.favourites.order(pid, type).isEmpty() }
            }
        }
    }

    val query = MutableStateFlow("")

    val categories: StateFlow<List<CategoryEntity>> = activePlaylist.flatMapLatest { c.vod.observeCategories(it, type) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val total: StateFlow<Int?> = activePlaylist.flatMapLatest { c.vod.observeCount(type, it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The favourites among the titles, marked on their posters. */
    val favouriteIds = c.favourites.of(type)

    val items: Flow<PagingData<VodCard>> =
        combine(activePlaylist, filters.value, query.debounce(250)) { pid, f, q -> Triple(pid, f, q) }
            .distinctUntilChanged()
            .flatMapLatest { (pid, f, q) -> c.vod.pageCards(type, pid, f, q) }
            .cachedIn(viewModelScope)

    /** A poster whose options are open. */
    var optionsFor by mutableStateOf<VodCard?>(null)

    /** A poster taken off Favourites while they're shown: focus moves on once it has gone. */
    var removedId by mutableStateOf<Long?>(null)

    /** Moving a favourite within Favourites. */
    val mover = FavouriteMover(viewModelScope, c.favourites) { playlist.value?.id ?: 0 }

    fun setFavourite(card: VodCard, favourite: Boolean) = viewModelScope.launch {
        c.vod.setFavourite(playlist.value?.id ?: return@launch, type, card.id, favourite)
    }

    /** The title last opened, to return focus to it when coming back. */
    var focusId: Long? = null
    var restoreFocusPending by mutableStateOf(false)

    /** Downloads the movie and series lists (progress shows in the top bar). */
    fun loadNow() {
        c.sync.refreshVod(playlist.value?.id ?: return)
    }

    /** Hides a category (from its options in the column, which shows the one beside it); if still shown, all titles. */
    fun hideCategory(cat: CategoryEntity) = viewModelScope.launch {
        if (filters.value.value == VodFilter.Category(cat.categoryId)) filters.select(VodFilter.All)
        c.playlists.hideCategory(cat)
    }

    fun open(card: VodCard, pid: Long): Screen {
        focusId = card.id
        restoreFocusPending = true
        return vodDetails(pid, type, card.id)
    }
}

@Composable
fun VodBrowseScreen(type: String, initialFilter: VodFilter?) {
    val nav = LocalNavigator.current
    val vm = appViewModel(key = "vod-$type") { VodBrowseViewModel(it, type, initialFilter, nav.picked) }
    val filter by vm.filters.value.collectAsState()
    val categories by vm.categories.collectAsState()
    val playlist by vm.playlist.collectAsState()
    val favourites = stringResource(R.string.common_favourites)
    val newest = stringResource(if (vm.isMovies) R.string.vod_recently_added else R.string.vod_recently_updated)
    val all = stringResource(if (vm.isMovies) R.string.vod_all_movies else R.string.vod_all_series)
    val entries = remember(categories, favourites, newest, all) {
        listOf<CategoryEntry<VodFilter>>(
            CategoryEntry(VodFilter.Favourites, favourites),
            CategoryEntry(VodFilter.Newest, newest),
            CategoryEntry(VodFilter.All, all)
        ) + categoryEntries<VodFilter>(categories) { VodFilter.Category(it.categoryId) }
    }
    TabScaffold(if (vm.isMovies) Tab.MOVIES else Tab.SERIES) {
        Row(Modifier.fillMaxSize().padding(horizontal = 56.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            FilteredCategoryColumn(entries, fixed = 3, filter, vm.filters::focus, vm.filters::select, vm::hideCategory)
            Column(Modifier.weight(1f)) {
                Header(vm, entries.firstOrNull { it.filter == filter })
                MoveHint(vm.mover, type, MoveLayout.GRID, Modifier.padding(bottom = 8.dp))
                val p = playlist
                when {
                    p != null && !p.hasVod -> Muted(
                        stringResource(R.string.vod_live_only),
                        Modifier.padding(top = 24.dp),
                        maxLines = 2
                    )
                    vm.filters.settled -> PosterGrid(vm, p?.id ?: 0)
                }
            }
        }
    }
    vm.optionsFor?.let { card -> PosterOptions(vm, card, filter, playlist?.id ?: 0) }
}

/** Holding OK on a poster: open it, favourites, and in Favourites, move it. */
@Composable
private fun PosterOptions(vm: VodBrowseViewModel, card: VodCard, filter: VodFilter, pid: Long) {
    val close = { vm.optionsFor = null }
    val favourites by vm.favouriteIds.collectAsState()
    val query by vm.query.collectAsState()
    val favourite = favourites?.let { card.id in it }
    val inFavourites = filter == VodFilter.Favourites
    val actions = vodActions(pid, vm.type, card.id, favourite) {
        vm.setFavourite(card, it)
        if (!it && inFavourites) {
            close()
            vm.removedId = card.id
        }
    } + listOfNotNull(if (inFavourites && favourite == true && query.isEmpty()) moveAction(vm.mover, vm.type, card.id, close) else null)
    ItemActionsPanel(card.title, actions, close)
}

@Composable
private fun Header(vm: VodBrowseViewModel, entry: CategoryEntry<VodFilter>?) {
    val total by vm.total.collectAsState()
    val query by vm.query.collectAsState()
    val count = entry?.count ?: total.takeIf { entry?.filter == VodFilter.All }
    ListHeader(entry?.label.orEmpty(), count, query, { vm.query.value = it }, stringResource(R.string.vod_filter_hint))
}

@Composable
private fun PosterGrid(vm: VodBrowseViewModel, pid: Long) {
    val nav = LocalNavigator.current
    val items = vm.items.collectAsLazyPagingItems()
    val favourites = vm.favouriteIds.collectIds()
    if (items.itemCount == 0) {
        if (vm.filters.value.collectAsState().value == VodFilter.Favourites) {
            Muted(stringResource(R.string.vod_no_favourites), Modifier.padding(top = 24.dp))
        } else {
            Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Muted(stringResource(R.string.vod_nothing_here), maxLines = 2)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PillButton(stringResource(R.string.vod_load_now), vm::loadNow, primary = true)
                    PillButton(stringResource(R.string.vod_choose_categories), { nav.push(Screen.Categories(pid)) })
                }
            }
        }
        return
    }
    val filter by vm.filters.value.collectAsState()
    val query by vm.query.collectAsState()
    MoveBackHandler(vm.mover, filter, query)
    val state = rememberLazyGridState()
    vm.removedId?.let { removed ->
        LaunchedEffect(removed) {
            snapshotFlow { items.itemSnapshotList.none { it?.id == removed } }.first { it }
            vm.focusId = null
            vm.restoreFocusPending = true
            vm.removedId = null
        }
    }
    KeepInView(state) { vm.mover.movingId(vm.type)?.let { p -> items.itemSnapshotList.indexOfFirst { it?.id == p } } }
    LazyVerticalGrid(
        columns = GridCells.Fixed(COLUMNS),
        state = state,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 40.dp)
    ) {
        items(count = items.itemCount, key = items.itemKey { it.id }) { index ->
            val card = items[index]
            if (card == null) {
                Column {
                    Poster(null, "", POSTER_WIDTH)
                    Spacer(Modifier.height(40.dp))
                }
            } else {
                // Back from a title, on it; after one is taken off Favourites, on the first.
                val wantsFocus = vm.restoreFocusPending && (vm.focusId?.let { it == card.id } ?: (index == 0))
                MovableTile(vm.mover, vm.type, card.id, MoveLayout.GRID, COLUMNS) {
                    PosterCard(
                        card,
                        Modifier.restoreFocusIf(wantsFocus) { vm.restoreFocusPending = false },
                        favourite = card.id in favourites,
                        onLongClick = { vm.optionsFor = card }
                    ) { nav.push(vm.open(card, pid)) }
                }
            }
        }
    }
}

private const val COLUMNS = 6
