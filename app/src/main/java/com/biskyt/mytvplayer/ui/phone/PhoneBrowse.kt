package com.biskyt.mytvplayer.ui.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.data.VodFilter
import com.biskyt.mytvplayer.ui.LocalNavigator
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.common.CategoryEntry
import com.biskyt.mytvplayer.ui.common.FilterField
import com.biskyt.mytvplayer.ui.common.LockedCategory
import com.biskyt.mytvplayer.ui.common.Tab
import com.biskyt.mytvplayer.ui.common.categoryEntries
import com.biskyt.mytvplayer.ui.common.collectIds
import com.biskyt.mytvplayer.ui.common.formatCount
import com.biskyt.mytvplayer.ui.common.isLockedCategory
import com.biskyt.mytvplayer.ui.theme.FormFactor
import com.biskyt.mytvplayer.ui.theme.LocalAccent
import com.biskyt.mytvplayer.ui.theme.LocalFormFactor
import com.biskyt.mytvplayer.ui.theme.Palette
import com.biskyt.mytvplayer.ui.vod.PosterOptions
import com.biskyt.mytvplayer.ui.vod.VodBrowseViewModel

// Movies and Series on a phone or an unfolded foldable (phone spike, 2026-11-25): the TV's category column becomes a
// row of chips that scrolls sideways, over a grid of posters (three across on a phone, as many as fit when unfolded).

/** Movies or series, from the TV's [VodBrowseViewModel]: the same lists, filter and paging. */
@Composable
fun PhoneBrowse(vm: VodBrowseViewModel) {
    val filter by vm.filters.value.collectAsState()
    val categories by vm.categories.collectAsState()
    val playlist by vm.playlist.collectAsState()
    val query by vm.query.collectAsState()
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
    val title = stringResource(if (vm.isMovies) R.string.common_tab_movies else R.string.common_tab_series)
    PhoneScaffold(if (vm.isMovies) Tab.MOVIES else Tab.SERIES, title) {
        FilterField(
            query,
            { vm.query.value = it },
            stringResource(R.string.vod_filter_hint),
            Modifier.padding(horizontal = PhoneGutter, vertical = 6.dp).fillMaxWidth()
        )
        CategoryChips(entries, filter) { vm.filters.select(it) }
        val p = playlist
        when {
            p != null && !p.hasVod -> Note(stringResource(R.string.vod_live_only))
            isLockedCategory(categories, (filter as? VodFilter.Category)?.id) ->
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) { LockedCategory() }
            vm.filters.settled -> PhonePosterGrid(vm, p?.id ?: 0, filter)
        }
    }
    vm.optionsFor?.let { card -> PhonePanel { PosterOptions(vm, card, filter, playlist?.id ?: 0) } }
}

/** The lists to choose from (Favourites, the newest, all, then each category), as chips in a row. */
@Composable
private fun CategoryChips(entries: List<CategoryEntry<VodFilter>>, selected: VodFilter, onSelect: (VodFilter) -> Unit) {
    val state = rememberLazyListState()
    val index = entries.indexOfFirst { it.filter == selected }
    // The chip chosen stays in view, with the one before it showing: when the screen opens on a category far along the
    // row, too.
    LaunchedEffect(index) { if (index >= 0) state.animateScrollToItem((index - 1).coerceAtLeast(0)) }
    LazyRow(
        state = state,
        contentPadding = PaddingValues(horizontal = PhoneGutter, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(entries, key = { _, e -> e.filter.toString() }) { _, entry ->
            Chip(entry, entry.filter == selected) { onSelect(entry.filter) }
        }
    }
}

@Composable
private fun Chip(entry: CategoryEntry<VodFilter>, selected: Boolean, onClick: () -> Unit) {
    val accent = LocalAccent.current
    Row(
        Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(20.dp)).background(if (selected) accent.color else PhoneChip)
            .semantics { this.selected = selected }
            .clickable(role = Role.Tab, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        val colour = if (selected) Palette.background else Palette.text
        Text(entry.label, color = colour, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        entry.count?.let { Text(formatCount(it), color = colour.copy(alpha = 0.7f), fontSize = 12.sp, maxLines = 1) }
    }
}

/** The titles of the list chosen, paged as on a TV. */
@Composable
private fun PhonePosterGrid(vm: VodBrowseViewModel, pid: Long, filter: VodFilter) {
    val nav = LocalNavigator.current
    val items = vm.items.collectAsLazyPagingItems()
    val favourites = vm.favouriteIds.collectIds()
    if (items.itemCount == 0) {
        if (filter == VodFilter.Favourites) {
            Note(stringResource(R.string.vod_no_favourites))
        } else {
            Column(Modifier.padding(horizontal = PhoneGutter, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.vod_nothing_here), color = Palette.textDim, fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PhonePill(stringResource(R.string.vod_load_now), primary = true, onClick = vm::loadNow)
                    PhonePill(stringResource(R.string.vod_choose_categories)) { nav.push(Screen.Categories(pid)) }
                }
            }
        }
        return
    }
    val wide = LocalFormFactor.current == FormFactor.UNFOLDED
    // A new list starts at its top.
    val state = remember(filter) { LazyGridState() }
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
private fun gridColumns(width: Dp, wide: Boolean): Pair<Int, Dp> {
    val room = width - PhoneGutter * 2
    val columns = if (wide) ((room + GRID_GAP) / (WIDE_POSTER + GRID_GAP)).toInt().coerceAtLeast(PHONE_COLUMNS) else PHONE_COLUMNS
    return columns to (room - GRID_GAP * (columns - 1)) / columns
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        color = Palette.textDim,
        fontSize = 14.sp,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(PhoneGutter)
    )
}

private const val PHONE_COLUMNS = 3
private val WIDE_POSTER = 130.dp
private val GRID_GAP = 12.dp

/** A poster not loaded yet: about as high as a poster with its two lines of text, for its width. */
private const val POSTER_AND_TEXT = 0.55f
