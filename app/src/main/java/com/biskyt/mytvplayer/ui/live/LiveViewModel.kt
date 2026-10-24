package com.biskyt.mytvplayer.ui.live

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.biskyt.mytvplayer.AppContainer
import com.biskyt.mytvplayer.core.epg.ProgrammeKind
import com.biskyt.mytvplayer.data.ChannelFilter
import com.biskyt.mytvplayer.data.db.CAT_LIVE
import com.biskyt.mytvplayer.data.db.CAT_MOVIE
import com.biskyt.mytvplayer.data.db.CAT_SERIES
import com.biskyt.mytvplayer.data.db.CategoryCount
import com.biskyt.mytvplayer.data.db.CategoryEntity
import com.biskyt.mytvplayer.data.db.ChannelEntity
import com.biskyt.mytvplayer.data.db.ChannelProgramme
import com.biskyt.mytvplayer.ui.PickedLists
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.common.CategoryFilter
import com.biskyt.mytvplayer.ui.common.FavouriteMover
import com.biskyt.mytvplayer.ui.common.NumberEntry
import com.biskyt.mytvplayer.ui.common.guideNowNext
import com.biskyt.mytvplayer.ui.common.openOnShown
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class LiveViewModel(
    private val c: AppContainer,
    initialFilter: ChannelFilter?,
    initialGuide: Boolean,
    private val picked: PickedLists
) : ViewModel() {
    private val activePlaylist = c.settings.activePlaylistId
    val playlistId: StateFlow<Long> = activePlaylist.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    /** Opened from the Catch-up tab: the categories are those with catch-up channels, and OK opens past programmes. */
    val catchupMode = initialFilter?.isCatchup == true

    // Opens on the list asked for (e.g. Catch-up), else the one picked earlier while the app has been open, else
    // Favourites. A different list: the remembered channel may not be in it, so focus starts from the top.
    val filters =
        CategoryFilter(viewModelScope, initialFilter ?: picked.live ?: ChannelFilter.Favourites, settled = initialFilter != null) { f ->
            // Not Catch-up: the Live TV tab opens on live lists (Catch-up has its own tab).
            if (!f.isCatchup) picked.live = f
            focusStreamId = null
        }
    val filter = filters.value

    init {
        if (initialFilter == null) {
            viewModelScope.launch {
                val pid = activePlaylist.first()
                filters.openOnShown(ChannelFilter.Favourites, ChannelFilter.All, gone = { f ->
                    f is ChannelFilter.Category && c.db.categories().observeIncluded(pid, CAT_LIVE).first().none { it.categoryId == f.id }
                }) { c.favourites.order(pid, CAT_LIVE).isEmpty() }
            }
        }
    }

    val query = MutableStateFlow("")
    var guideMode by mutableStateOf(initialGuide)
        private set

    /** Shared by the list and the guide grid, so switching between them keeps the same channels on screen. */
    val listState = LazyListState()

    val categories: StateFlow<List<CategoryEntity>> = activePlaylist.flatMapLatest { c.db.categories().observeIncluded(it, CAT_LIVE) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The categories with catch-up channels (the Catch-up tab's column). */
    val catchupCategories: StateFlow<List<CategoryCount>> = activePlaylist.flatMapLatest { c.channels.observeCatchupCategories(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Hides a category (from its options in the column); if it's the one shown, All channels shows instead. */
    fun hideCategory(cat: CategoryEntity) = viewModelScope.launch {
        if (filter.value == ChannelFilter.Category(cat.categoryId)) filters.select(ChannelFilter.All)
        c.playlists.hideCategory(cat)
    }

    val totalCount: StateFlow<Int> = activePlaylist.flatMapLatest { c.channels.observeCount(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Null until read. */
    val favouriteIds: StateFlow<Set<Long>?> = c.favourites.channels

    val channels: Flow<PagingData<ChannelEntity>> =
        combine(activePlaylist, filter, query.debounce(250)) { pid, f, q -> Triple(pid, f, q) }
            .distinctUntilChanged()
            .flatMapLatest { (pid, f, q) -> c.channels.page(pid, f, q) }
            .cachedIn(viewModelScope)

    val nowNext =
        guideNowNext(viewModelScope, c) { playlistId.value }

    /** Channel whose options sheet is open. */
    var optionsFor by mutableStateOf<ChannelEntity?>(null)
        private set

    /** The TV guide programme the options were opened on (null from the channel list). */
    var optionsProgramme: ChannelProgramme? = null
        private set

    fun openOptions(ch: ChannelEntity, programme: ChannelProgramme? = null) {
        optionsProgramme = programme
        optionsFor = ch
    }

    fun closeOptions() {
        optionsFor = null
    }

    /**
     * What "find" can offer for a guide programme (a ProgrammeKind): a series, a film, or (unknown, or a playlist
     * without movies or series) a search for its title. Null for news, sport and the like, and with no title to look for.
     */
    suspend fun findKind(ch: ChannelEntity, p: ChannelProgramme): Int? = when {
        p.kind == ProgrammeKind.OTHER || p.title.isBlank() -> null
        c.playlists.get(ch.playlistId)?.hasVod != true -> ProgrammeKind.UNKNOWN
        else -> p.kind
    }

    private var findJob: Job? = null

    /** Opens the programme's series (at its episode) or film, or a search for its title when the playlist doesn't have it. */
    fun find(ch: ChannelEntity, p: ChannelProgramme, kind: Int, open: (Screen) -> Unit) {
        if (findJob?.isActive == true) return // a second press while the first is still looking
        findJob = viewModelScope.launch {
            val pid = ch.playlistId
            open(
                when (kind) {
                    ProgrammeKind.SERIES -> c.vod.findTitle(pid, p.title, CAT_SERIES)?.let {
                        Screen.SeriesDetails(pid, it, p.season, p.episode)
                    }
                    ProgrammeKind.MOVIE -> c.vod.findTitle(pid, p.title, CAT_MOVIE)?.let { Screen.MovieDetails(pid, it) }
                    else -> null
                } ?: Screen.Search(p.title)
            )
        }
    }

    /**
     * Channel to give focus to when the list or guide is shown again (after the player, or after switching view).
     * Updated as focus moves; a plain field, so moving focus never recomposes anything.
     */
    var focusStreamId: Long? = null
        set(value) {
            if (value != field) focusIndex = null
            field = value
        }

    // Position of [focusStreamId] in the list, when known (it isn't for a channel picked by number).
    var focusIndex: Int? = null
        private set

    /** Called as focus moves in the list or guide. */
    fun focusMoved(streamId: Long, index: Int) {
        focusStreamId = streamId
        focusIndex = index
    }

    /**
     * Before restoring focus: scrolls the remembered channel fully into view, as row heights differ between the list
     * and the guide. Call from a composition effect: it first waits a frame so the view now shown has been laid out.
     */
    suspend fun revealFocused() {
        withFrameNanos { }
        val id = focusStreamId ?: return listState.scrollToItem(0) // nothing remembered: focus goes to the first row
        // Unknown position (e.g. a channel picked by number): look it up, unless a search makes the list different.
        val index = focusIndex ?: run {
            if (query.value.isNotBlank()) return
            c.channels.ids(playlistId.value, filter.value).indexOf(id).takeIf { it >= 0 } ?: return
        }
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == index }
        if (item == null || item.offset + item.size > info.viewportEndOffset) listState.scrollToItem(index)
    }
    var restoreFocusPending by mutableStateOf(true)

    /** Set by the screen: what to do with the channel picked by number. */
    var playNumber: (ChannelEntity) -> Unit = {}

    /** Digits typed on the remote jump straight to that channel number. */
    val numberEntry = NumberEntry(viewModelScope) { n -> c.channels.byNumber(playlistId.value, n)?.let { playNumber(it) } }

    fun toggleFavourite(ch: ChannelEntity) = viewModelScope.launch {
        c.channels.setFavourite(ch.playlistId, ch.streamId, ch.streamId !in favouriteIds.value.orEmpty())
    }

    fun hide(ch: ChannelEntity) = viewModelScope.launch {
        c.channels.setHidden(ch.playlistId, ch.streamId, true)
        closeOptions()
    }

    /** Switches between the channel list and the TV guide grid, keeping focus on the same channel. */
    fun showGuide(guide: Boolean) {
        if (guide == guideMode) return
        guideMode = guide
        restoreFocusPending = true
    }

    /** Moving a favourite within Favourites (the list, not the guide). */
    val mover = FavouriteMover(viewModelScope, c.favourites) { playlistId.value }
    val canMoveFavourite: Boolean get() = filter.value == ChannelFilter.Favourites && !guideMode && query.value.isEmpty()
}
