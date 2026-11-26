package com.biskyt.mytvplayer.ui.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.data.VodFilter
import com.biskyt.mytvplayer.data.db.VodCard
import com.biskyt.mytvplayer.ui.LocalNavigator
import com.biskyt.mytvplayer.ui.common.FilterField
import com.biskyt.mytvplayer.ui.common.LockedCategory
import com.biskyt.mytvplayer.ui.common.Muted
import com.biskyt.mytvplayer.ui.common.collectIds
import com.biskyt.mytvplayer.ui.common.isLockedCategory
import com.biskyt.mytvplayer.ui.theme.FormFactor
import com.biskyt.mytvplayer.ui.theme.LocalFormFactor
import com.biskyt.mytvplayer.ui.vod.PosterOptions
import com.biskyt.mytvplayer.ui.vod.VodBrowseViewModel
import com.biskyt.mytvplayer.ui.vod.emptyTitles
import com.biskyt.mytvplayer.ui.vod.vodEntries

// Movies and Series on a phone or an unfolded foldable (the owner, 2026-11-25): the TV's category column becomes a
// row of chips that scrolls sideways, over a grid of posters (three across on a phone, as many as fit when unfolded).

/** Movies or series, from the TV's [VodBrowseViewModel]: the same lists, filter and paging. */
@Composable
fun PhoneBrowse(vm: VodBrowseViewModel) {
    val filter by vm.filters.value.collectAsState()
    val categories by vm.categories.collectAsState()
    val playlist by vm.playlist.collectAsState()
    val query by vm.query.collectAsState()
    val entries = vodEntries(vm)
    PhoneScaffold(vm.tab, stringResource(vm.tab.label)) {
        FilterField(
            query,
            { vm.query.value = it },
            stringResource(R.string.vod_filter_hint),
            Modifier.padding(horizontal = PhoneGutter, vertical = 6.dp).fillMaxWidth()
        )
        PhoneCategoryChips(entries, filter, vm.filters::select, vm::hideCategory)
        val p = playlist
        when {
            p != null && !p.hasVod -> Muted(stringResource(R.string.vod_live_only), Modifier.padding(PhoneGutter), maxLines = 3)
            isLockedCategory(categories, (filter as? VodFilter.Category)?.id) ->
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) { LockedCategory() }
            vm.filters.settled -> PhonePosterGrid(vm, p?.id ?: 0, filter)
        }
    }
    vm.optionsFor?.let { card -> PosterOptions(vm, card, filter, playlist?.id ?: 0) }
}

/** The titles of the list chosen, paged as on a TV. */
@Composable
private fun PhonePosterGrid(vm: VodBrowseViewModel, pid: Long, filter: VodFilter) {
    val items = vm.items.collectAsLazyPagingItems()
    val favourites = vm.favouriteIds.collectIds()
    // A poster taken off Favourites (from its options): a TV moves focus on from it; a touch screen has no focus to move.
    vm.removedId?.let { removed -> LaunchedEffect(removed) { vm.removedId = null } }
    if (items.itemCount == 0) {
        val empty = emptyTitles(vm, pid, filter == VodFilter.Favourites)
        Column(Modifier.padding(horizontal = PhoneGutter, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Muted(stringResource(empty.message), maxLines = 3)
            if (empty.actions.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    empty.actions.forEachIndexed { i, (label, action) ->
                        PhonePill(stringResource(label), primary = i == 0, onClick = action)
                    }
                }
            }
        }
        return
    }
    // A new list starts at its top; back from a title's page, the list is where it was (as on a TV).
    key(filter) { PhoneGrid(vm, pid, items, favourites) }
}

@Composable
private fun PhoneGrid(vm: VodBrowseViewModel, pid: Long, items: LazyPagingItems<VodCard>, favourites: Set<Long>) {
    val nav = LocalNavigator.current
    val wide = LocalFormFactor.current == FormFactor.UNFOLDED
    val state = rememberLazyGridState()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val (columns, width) = gridColumns(maxWidth, wide)
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = state,
            contentPadding = PaddingValues(start = PhoneGutter, end = PhoneGutter, top = 4.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(GRID_GAP),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(count = items.itemCount, key = items.itemKey { it.id }) { index ->
                val card = items[index]
                if (card == null) {
                    Spacer(Modifier.fillMaxWidth().aspectRatio(POSTER_AND_TEXT))
                } else {
                    PhonePoster(
                        card.imageUrl,
                        card.title,
                        card.year?.toString().orEmpty(),
                        width = width,
                        favourite = card.id in favourites,
                        onLongClick = { vm.optionsFor = card }
                    ) { nav.push(vm.open(card, pid)) }
                }
            }
        }
    }
}

/** How many posters fit across [width], and how wide each is: three on a phone, about [WIDE_POSTER] each unfolded. */
internal fun gridColumns(width: Dp, wide: Boolean): Pair<Int, Dp> {
    val room = width - PhoneGutter * 2
    val columns = if (wide) ((room + GRID_GAP) / (WIDE_POSTER + GRID_GAP)).toInt().coerceAtLeast(PHONE_COLUMNS) else PHONE_COLUMNS
    return columns to (room - GRID_GAP * (columns - 1)) / columns
}

private const val PHONE_COLUMNS = 3
private val WIDE_POSTER = 130.dp
internal val GRID_GAP = 12.dp

/** A poster not loaded yet: about as high as a poster with its two lines of text, for its width. */
private const val POSTER_AND_TEXT = 0.55f
