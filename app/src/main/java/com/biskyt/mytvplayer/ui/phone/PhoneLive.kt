package com.biskyt.mytvplayer.ui.phone

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.data.ChannelFilter
import com.biskyt.mytvplayer.data.db.ChannelEntity
import com.biskyt.mytvplayer.data.label
import com.biskyt.mytvplayer.data.labelGroup
import com.biskyt.mytvplayer.data.programmeTitle
import com.biskyt.mytvplayer.ui.LocalNavigator
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.common.AmbientSource
import com.biskyt.mytvplayer.ui.common.AppIcons
import com.biskyt.mytvplayer.ui.common.CatchupMark
import com.biskyt.mytvplayer.ui.common.CategoryEntry
import com.biskyt.mytvplayer.ui.common.ChannelLogo
import com.biskyt.mytvplayer.ui.common.LocalAmbience
import com.biskyt.mytvplayer.ui.common.LockedCategory
import com.biskyt.mytvplayer.ui.common.Muted
import com.biskyt.mytvplayer.ui.common.NowNext
import com.biskyt.mytvplayer.ui.common.ProgressLine
import com.biskyt.mytvplayer.ui.common.Tab
import com.biskyt.mytvplayer.ui.common.ambientSource
import com.biskyt.mytvplayer.ui.common.categoryEntries
import com.biskyt.mytvplayer.ui.common.collectIds
import com.biskyt.mytvplayer.ui.common.distinctLabels
import com.biskyt.mytvplayer.ui.common.formatTime
import com.biskyt.mytvplayer.ui.common.isLockedCategory
import com.biskyt.mytvplayer.ui.common.numberLabel
import com.biskyt.mytvplayer.ui.common.rememberMinuteClock
import com.biskyt.mytvplayer.ui.live.ChannelOptionsSheet
import com.biskyt.mytvplayer.ui.live.LiveViewModel
import com.biskyt.mytvplayer.ui.theme.FormFactor
import com.biskyt.mytvplayer.ui.theme.LocalAccent
import com.biskyt.mytvplayer.ui.theme.LocalFormFactor
import com.biskyt.mytvplayer.ui.theme.Palette

// Live TV, Radio and the Catch-up tab on a phone or an unfolded foldable (phone spike, 2026-11-25). The same
// ViewModel, lists and options as the TV screen (LiveScreen), drawn for touch. The guide stays the TV's screen.

/**
 * The channel list on a touch screen. A tap calls [open] (it plays the channel, or opens its catch-up in the Catch-up
 * tab), and a long press opens the channel's options, as holding OK does on a TV. [play] is for the options' Watch.
 */
@Composable
fun PhoneLive(vm: LiveViewModel, play: (ChannelEntity) -> Unit, open: (ChannelEntity) -> Unit) {
    val nav = LocalNavigator.current
    val filter by vm.filter.collectAsState()
    val tab = when {
        vm.catchupMode -> Tab.CATCHUP
        vm.radio -> Tab.RADIO
        else -> Tab.LIVE
    }
    val title = stringResource(
        when (tab) {
            Tab.CATCHUP -> R.string.common_catchup
            Tab.RADIO -> R.string.common_tab_radio
            else -> R.string.common_tab_live
        }
    )
    val unfolded = LocalFormFactor.current == FormFactor.UNFOLDED
    val entries = liveEntries(vm)
    val openGuide = { nav.push(Screen.Live(filter, guide = true, radio = vm.radio)) }
    // The options' "Switch to grid view" asks for the guide: here it opens the TV guide's screen.
    LaunchedEffect(vm.guideMode) {
        if (vm.guideMode) {
            vm.showGuide(false, moveFocus = false)
            openGuide()
        }
    }
    PhoneScaffold(tab, title, actions = { GuideButton(openGuide) }) {
        if (unfolded) {
            Row(Modifier.fillMaxSize()) {
                CategoryList(entries, filter, vm.filters::select, Modifier.width(240.dp).fillMaxHeight())
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    FilterField(vm, Modifier.padding(start = 8.dp, end = 20.dp, top = 6.dp, bottom = 10.dp))
                    Channels(vm, filter, open, PaddingValues(start = 8.dp, end = 20.dp, bottom = 24.dp))
                }
            }
        } else {
            FilterField(vm, Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            CategoryChips(entries, filter, vm.filters::select)
            Channels(vm, filter, open, PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 16.dp))
        }
    }
    vm.optionsFor?.let { ch -> ChannelOptionsSheet(ch, vm, play) }
}

/** The lists to pick from, as the TV's category column has them: the fixed ones first, then the categories A to Z. */
@Composable
private fun liveEntries(vm: LiveViewModel): List<CategoryEntry<ChannelFilter>> {
    if (vm.catchupMode) {
        val categories by vm.catchupCategories.collectAsState()
        val all = stringResource(R.string.live_all_catchup)
        return remember(categories, all) {
            listOf<CategoryEntry<ChannelFilter>>(CategoryEntry(ChannelFilter.Catchup, all, categories.sumOf { it.count })) +
                distinctLabels(categories, { it.label }, { it.labelGroup }) { c, label ->
                    CategoryEntry<ChannelFilter>(ChannelFilter.CatchupCategory(c.categoryId), label, c.count)
                }
        }
    }
    val categories by vm.categories.collectAsState()
    val total by vm.totalCount.collectAsState()
    val favourites = stringResource(R.string.common_favourites)
    val recent = stringResource(R.string.common_recent)
    val all = stringResource(R.string.live_all_channels)
    return remember(categories, favourites, recent, all, total) {
        listOf<CategoryEntry<ChannelFilter>>(
            CategoryEntry(ChannelFilter.Favourites, favourites),
            CategoryEntry(ChannelFilter.Recent, recent),
            CategoryEntry(ChannelFilter.All, all, total)
        ) + categoryEntries<ChannelFilter>(categories) { ChannelFilter.Category(it.categoryId) }
    }
}

/** The header's button that opens the TV guide (drawn as on a TV). */
@Composable
private fun GuideButton(onClick: () -> Unit) {
    Row(
        Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(22.dp)).background(PhoneChip)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Image(AppIcons.Guide, null, Modifier.size(18.dp), colorFilter = ColorFilter.tint(Palette.text))
        Text(stringResource(R.string.live_view_guide), color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
    }
}

/** The box that filters the channels by name or number, as the TV's filter box does. */
@Composable
private fun FilterField(vm: LiveViewModel, modifier: Modifier) {
    val query by vm.query.collectAsState()
    val focus = LocalFocusManager.current
    val style = TextStyle(color = Palette.text, fontSize = 15.sp)
    BasicTextField(
        value = query,
        onValueChange = { vm.query.value = it },
        singleLine = true,
        textStyle = style,
        cursorBrush = SolidColor(LocalAccent.current.color),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        modifier = modifier.fillMaxWidth(),
        decorationBox = { field ->
            Row(
                Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp)).background(PhoneCard).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Image(AppIcons.Search, null, Modifier.size(18.dp), colorFilter = ColorFilter.tint(Palette.textDim))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text(stringResource(R.string.live_filter_hint), style = style.copy(color = Palette.textDim))
                    field()
                }
            }
        }
    )
}

/** The lists as chips in a row that scrolls sideways (on a phone). */
@Composable
private fun CategoryChips(entries: List<CategoryEntry<ChannelFilter>>, selected: ChannelFilter, select: (ChannelFilter) -> Unit) {
    val state = rememberLazyListState()
    val index = entries.indexOfFirst { it.filter == selected }
    // The list picked before (remembered, or opened from elsewhere) is in view.
    LaunchedEffect(index) {
        if (index >= 0 && state.layoutInfo.visibleItemsInfo.none { it.index == index }) state.scrollToItem(index)
    }
    LazyRow(
        state = state,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(entries, key = { _, e -> e.label + e.filter.hashCode() }) { _, entry ->
            val on = entry.filter == selected
            val accent = LocalAccent.current
            Row(
                Modifier.heightIn(min = 36.dp).clip(RoundedCornerShape(18.dp)).background(if (on) accent.color else PhoneChip)
                    .clickable(role = Role.Tab) { select(entry.filter) }.padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val colour = if (on) accent.onAccent else Palette.text
                Text(
                    entry.label,
                    color = colour,
                    fontSize = 14.sp,
                    fontWeight = if (on) FontWeight.ExtraBold else FontWeight.Bold,
                    maxLines = 1
                )
                if (entry.category?.restricted == true) RestrictedIcon(colour)
            }
        }
    }
}

/** The lists as a column at the side (an unfolded foldable), with their sizes, as on a TV but at touch size. */
@Composable
private fun CategoryList(
    entries: List<CategoryEntry<ChannelFilter>>,
    selected: ChannelFilter,
    select: (ChannelFilter) -> Unit,
    modifier: Modifier
) {
    val state = rememberLazyListState()
    val index = entries.indexOfFirst { it.filter == selected }
    LaunchedEffect(index) {
        if (index >= 0 && state.layoutInfo.visibleItemsInfo.none { it.index == index }) state.scrollToItem(index)
    }
    LazyColumn(
        modifier,
        state = state,
        contentPadding = PaddingValues(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        itemsIndexed(entries, key = { _, e -> e.label + e.filter.hashCode() }) { _, entry ->
            val on = entry.filter == selected
            val accent = LocalAccent.current.color
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (on) PhoneChip else Palette.background)
                    .clickable(role = Role.Tab) { select(entry.filter) }.padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    entry.label,
                    color = if (on) accent else Palette.text,
                    fontSize = 15.sp,
                    fontWeight = if (on) FontWeight.ExtraBold else FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (entry.category?.restricted == true) RestrictedIcon(Palette.textDim)
                entry.count?.let { Text(it.toString(), color = Palette.textDim, fontSize = 13.sp) }
            }
        }
    }
}

@Composable
private fun RestrictedIcon(colour: androidx.compose.ui.graphics.Color) =
    Image(AppIcons.Lock, stringResource(R.string.parental_restricted), Modifier.size(14.dp), colorFilter = ColorFilter.tint(colour))

/** The channels of the list picked, or the restricted category's notice, as on a TV. */
@Composable
private fun Channels(vm: LiveViewModel, filter: ChannelFilter, open: (ChannelEntity) -> Unit, padding: PaddingValues) {
    val items = vm.channels.collectAsLazyPagingItems()
    val categories by vm.categories.collectAsState()
    when {
        !vm.filters.settled -> Unit
        isLockedCategory(categories, (filter as? ChannelFilter.Category)?.id) ->
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) { LockedCategory() }
        else -> ChannelList(vm, items, filter, open, padding)
    }
}

@Composable
private fun ChannelList(
    vm: LiveViewModel,
    items: LazyPagingItems<ChannelEntity>,
    filter: ChannelFilter,
    open: (ChannelEntity) -> Unit,
    padding: PaddingValues
) {
    val favs = vm.favouriteIds.collectIds()
    val query by vm.query.collectAsState()
    val now by rememberMinuteClock()
    val ambience = LocalAmbience.current
    // The TV's list state, so the list is where it was back from the player; a new list starts at the top.
    val state = vm.listState
    var shown by remember { mutableStateOf(filter to query) }
    LaunchedEffect(filter, query) {
        if (shown != filter to query) {
            shown = filter to query
            state.scrollToItem(0)
        }
    }
    if (items.itemCount == 0) {
        Muted(stringResource(R.string.live_no_channels), Modifier.padding(horizontal = 20.dp, vertical = 24.dp))
        return
    }
    LazyColumn(state = state, contentPadding = padding, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(count = items.itemCount, key = items.itemKey { it.streamId }) { index ->
            val ch = items[index]
            if (ch == null) {
                Box(Modifier.fillMaxWidth().height(ROW_HEIGHT).clip(RoundedCornerShape(14.dp)).background(Palette.surfaceLow))
            } else {
                LaunchedEffect(ch.streamId) { vm.nowNext.request(ch.streamId) }
                val source = AmbientSource.logo(ch.logoUrl)
                PhoneChannelRow(
                    ch,
                    vm.nowNext.entries[ch.streamId],
                    favourite = ch.streamId in favs,
                    nowMs = now,
                    onClick = {
                        ambience?.source = source
                        vm.focusMoved(ch.streamId, index)
                        open(ch)
                    },
                    onLongClick = {
                        ambience?.source = source
                        vm.focusMoved(ch.streamId, index)
                        vm.openOptions(ch)
                    },
                    modifier = Modifier.ambientSource(ambience, source)
                )
            }
        }
    }
}

/** A channel at touch size: its number, logo and name, and what is on now, until when, with how far through it is. */
@Composable
private fun PhoneChannelRow(
    ch: ChannelEntity,
    nn: NowNext?,
    favourite: Boolean,
    nowMs: Long,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = ROW_HEIGHT).clip(RoundedCornerShape(14.dp)).background(Palette.surfaceLow)
            .combinedClickable(onLongClick = onLongClick, onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            ch.numberLabel,
            color = Palette.textDim,
            fontSize = 13.sp,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(32.dp)
        )
        ChannelLogo(ch.logoUrl, ch.name, width = 64.dp, height = 40.dp, favourite = favourite)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    ch.name,
                    color = Palette.text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                CatchupMark(ch, Modifier.padding(start = 6.dp))
            }
            val now = nn?.now
            when {
                nn == null -> Box(Modifier.fillMaxWidth(0.6f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(Palette.surfaceHigh))
                now == null -> Text(stringResource(R.string.common_no_guide), color = Palette.textDim, fontSize = 13.sp, maxLines = 1)
                else -> {
                    Text(
                        programmeTitle(now.title) + " · " + stringResource(R.string.live_until, formatTime(now.stopMs)),
                        color = Palette.textDim,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    ProgressLine(nn.progress(nowMs), Modifier.padding(top = 2.dp), height = 3.dp)
                }
            }
        }
    }
}

private val ROW_HEIGHT = 72.dp
