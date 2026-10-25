package com.biskyt.mytvplayer.ui.live

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.data.ChannelFilter
import com.biskyt.mytvplayer.data.db.CAT_LIVE
import com.biskyt.mytvplayer.data.db.ChannelEntity
import com.biskyt.mytvplayer.data.label
import com.biskyt.mytvplayer.data.programmeTitle
import com.biskyt.mytvplayer.ui.LocalNavigator
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.appViewModel
import com.biskyt.mytvplayer.ui.common.CatchupMark
import com.biskyt.mytvplayer.ui.common.CategoryEntry
import com.biskyt.mytvplayer.ui.common.ChannelLogo
import com.biskyt.mytvplayer.ui.common.FilteredCategoryColumn
import com.biskyt.mytvplayer.ui.common.FlashLabel
import com.biskyt.mytvplayer.ui.common.KeepInView
import com.biskyt.mytvplayer.ui.common.ListHeader
import com.biskyt.mytvplayer.ui.common.MovableTile
import com.biskyt.mytvplayer.ui.common.MoveBackHandler
import com.biskyt.mytvplayer.ui.common.MoveHint
import com.biskyt.mytvplayer.ui.common.MoveLayout
import com.biskyt.mytvplayer.ui.common.Muted
import com.biskyt.mytvplayer.ui.common.NowNext
import com.biskyt.mytvplayer.ui.common.PillButton
import com.biskyt.mytvplayer.ui.common.ProgressLine
import com.biskyt.mytvplayer.ui.common.Tab
import com.biskyt.mytvplayer.ui.common.TabScaffold
import com.biskyt.mytvplayer.ui.common.TvCard
import com.biskyt.mytvplayer.ui.common.categoryEntries
import com.biskyt.mytvplayer.ui.common.collectIds
import com.biskyt.mytvplayer.ui.common.digit
import com.biskyt.mytvplayer.ui.common.distinctLabels
import com.biskyt.mytvplayer.ui.common.formatTime
import com.biskyt.mytvplayer.ui.common.isGuide
import com.biskyt.mytvplayer.ui.common.numberLabel
import com.biskyt.mytvplayer.ui.common.rememberMinuteClock
import com.biskyt.mytvplayer.ui.common.restoreFocusIf
import com.biskyt.mytvplayer.ui.theme.Palette

@Composable
fun LiveScreen(initialFilter: ChannelFilter?, initialGuide: Boolean) {
    val nav = LocalNavigator.current
    val vm = appViewModel { LiveViewModel(it, initialFilter, initialGuide, nav.picked) }
    vm.nowNext.PauseWhenHidden()
    val filter by vm.filter.collectAsState()
    val pid by vm.playlistId.collectAsState()
    val play: (ChannelEntity) -> Unit = { ch ->
        vm.focusStreamId = ch.streamId
        nav.push(Screen.Player(pid, ch.streamId, filter))
    }
    // In the Catch-up lists, OK opens the channel's past programmes (Watch, in its options, plays it live).
    val open: (ChannelEntity) -> Unit = { ch ->
        if (vm.catchupMode) {
            vm.focusStreamId = ch.streamId
            nav.push(Screen.Catchup(ch.playlistId, ch.streamId))
        } else {
            play(ch)
        }
    }

    LaunchedEffect(Unit) { vm.restoreFocusPending = true }
    // So a late focus target in the guide knows when the user has moved on (see GuideGrid).
    val screenFocus = remember { ScreenFocus() }

    TabScaffold(
        if (vm.catchupMode) Tab.CATCHUP else Tab.LIVE,
        // Anywhere on the screen, the tabs included (else the TV would take the Guide button for its own guide): 0–9
        // on the remote jumps straight to that channel number; the Guide button switches list/guide.
        Modifier.onFocusChanged { screenFocus.hasFocus = it.hasFocus }.onPreviewKeyEvent { e ->
            if (e.type == KeyEventType.KeyDown && e.key in arrowKeys) screenFocus.arrowPresses++
            val digit = e.key.digit()
            when {
                e.type != KeyEventType.KeyDown -> e.key.isGuide()
                digit != null -> {
                    vm.playNumber = play
                    vm.numberEntry.type(digit)
                    true
                }
                e.key.isGuide() -> {
                    vm.showGuide(!vm.guideMode)
                    true
                }
                else -> false
            }
        }
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 56.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            LiveCategories(vm, filter)
            Column(Modifier.weight(1f)) {
                val filterBox = remember { FocusRequester() }
                ListHeader(vm, filter, Modifier.focusRequester(filterBox))
                MoveHint(vm.mover, CAT_LIVE, MoveLayout.COLUMN, Modifier.padding(bottom = 8.dp))
                // One paged list for both views, so the one switched to has its rows (and somewhere for focus) at once.
                val items = vm.channels.collectAsLazyPagingItems()
                BackToFilter(filterBox, atTop = { vm.guideMode && vm.focusIndex == 0 }) {
                    when {
                        !vm.filters.settled -> Unit
                        vm.guideMode -> GuideGrid(vm, items, play, screenFocus)
                        else -> ChannelList(vm, items, open)
                    }
                }
            }
        }
    }
    vm.optionsFor?.let { ch -> ChannelOptionsSheet(ch, vm, play) }
    if (vm.numberEntry.typed.isNotEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) { FlashLabel(vm.numberEntry.typed) }
    }
}

/**
 * Back with focus in [content] (the list or guide) goes up to the filter box, as does up from the guide's first row
 * ([atTop]; the guide's own focus handling could keep it there); anywhere else Back leaves Live TV.
 */
@Composable
private fun BackToFilter(filterBox: FocusRequester, atTop: () -> Boolean, content: @Composable () -> Unit) {
    var inside by remember { mutableStateOf(false) }
    BackHandler(enabled = inside) { runCatching { filterBox.requestFocus() } }
    Box(
        Modifier.onFocusChanged { inside = it.hasFocus }.onPreviewKeyEvent { e ->
            e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp && atTop() && runCatching { filterBox.requestFocus() }.isSuccess
        }
    ) { content() }
}

@Composable
private fun LiveCategories(vm: LiveViewModel, selected: ChannelFilter) {
    if (vm.catchupMode) return CatchupCategories(vm, selected)
    val categories by vm.categories.collectAsState()
    val favourites = stringResource(R.string.common_favourites)
    val recent = stringResource(R.string.common_recent)
    val all = stringResource(R.string.live_all_channels)
    val entries = remember(categories, favourites, recent, all) {
        listOf<CategoryEntry<ChannelFilter>>(
            CategoryEntry(ChannelFilter.Favourites, favourites),
            CategoryEntry(ChannelFilter.Recent, recent),
            CategoryEntry(ChannelFilter.All, all)
        ) + categoryEntries<ChannelFilter>(categories) { ChannelFilter.Category(it.categoryId) }
    }
    FilteredCategoryColumn(entries, fixed = 3, selected, vm.filters::focus, vm.filters::select, vm::hideCategory)
}

/** The Catch-up tab's column: all catch-up channels, then each category that has some. */
@Composable
private fun CatchupCategories(vm: LiveViewModel, selected: ChannelFilter) {
    val categories by vm.catchupCategories.collectAsState()
    val all = stringResource(R.string.live_all_catchup)
    val entries = remember(categories, all) {
        listOf<CategoryEntry<ChannelFilter>>(CategoryEntry(ChannelFilter.Catchup, all, categories.sumOf { it.count })) +
            distinctLabels(categories, { it.label }, { it.lang }) { c, label ->
                CategoryEntry<ChannelFilter>(ChannelFilter.CatchupCategory(c.categoryId), label, c.count)
            }
    }
    FilteredCategoryColumn(entries, fixed = 1, selected, vm.filters::focus, vm.filters::select)
}

@Composable
private fun ListHeader(vm: LiveViewModel, filter: ChannelFilter, filterModifier: Modifier) {
    val query by vm.query.collectAsState()
    // Each tab reads only its own category list.
    val (title, count) = if (vm.catchupMode) {
        val categories by vm.catchupCategories.collectAsState()
        val shown = (filter as? ChannelFilter.CatchupCategory)?.let { f -> categories.firstOrNull { it.categoryId == f.id } }
        (shown?.label ?: stringResource(R.string.common_catchup)) to (shown?.count ?: categories.sumOf { it.count })
    } else {
        val categories by vm.categories.collectAsState()
        val total by vm.totalCount.collectAsState()
        val favs = vm.favouriteIds.collectIds()
        when (filter) {
            ChannelFilter.All -> stringResource(R.string.live_all_channels) to total
            ChannelFilter.Favourites -> stringResource(R.string.common_favourites) to favs.size
            is ChannelFilter.Category -> categories.firstOrNull { it.categoryId == filter.id }.let {
                (it?.label ?: stringResource(R.string.common_channels)) to
                    it?.channelCount
            }
            else -> stringResource(R.string.common_recent) to null
        }
    }
    ListHeader(title, count, query, { vm.query.value = it }, stringResource(R.string.live_filter_hint), filterModifier) {
        Row(Modifier.clip(RoundedCornerShape(12.dp)).background(Palette.surface).padding(4.dp)) {
            PillButton(stringResource(R.string.live_view_list), { vm.showGuide(false) }, primary = !vm.guideMode)
            PillButton(stringResource(R.string.live_view_guide), { vm.showGuide(true) }, primary = vm.guideMode)
        }
    }
}

@Composable
private fun ChannelList(vm: LiveViewModel, items: LazyPagingItems<ChannelEntity>, play: (ChannelEntity) -> Unit) {
    val favs = vm.favouriteIds.collectIds()
    val listState = vm.listState
    val filter by vm.filter.collectAsState()
    val query by vm.query.collectAsState()
    MoveBackHandler(vm.mover, filter, query)
    KeepInView(listState) { vm.mover.movingId(CAT_LIVE)?.let { p -> items.itemSnapshotList.indexOfFirst { it?.streamId == p } } }
    LaunchedEffect(Unit) { if (vm.restoreFocusPending) vm.revealFocused() }
    val now by rememberMinuteClock()

    LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
        items(count = items.itemCount, key = items.itemKey { it.streamId }) { index ->
            val ch = items[index]
            if (ch == null) {
                PlaceholderRow()
            } else {
                // Once per visit, focus goes back to the channel last played (or the first row the first time).
                val wantsFocus = vm.restoreFocusPending && (vm.focusStreamId?.let { it == ch.streamId } ?: (index == 0))
                LaunchedEffect(ch.streamId) { vm.nowNext.request(ch.streamId) }
                MovableTile(vm.mover, CAT_LIVE, ch.streamId, MoveLayout.COLUMN) {
                    ChannelRow(
                        ch = ch,
                        nn = vm.nowNext.entries[ch.streamId],
                        favourite = ch.streamId in favs,
                        nowMs = now,
                        onClick = { play(ch) },
                        onLongClick = { vm.openOptions(ch) },
                        modifier = Modifier.restoreFocusIf(wantsFocus) { vm.restoreFocusPending = false }
                            .onFocusChanged { if (it.isFocused) vm.focusMoved(ch.streamId, index) }
                    )
                }
            }
        }
    }
    if (items.itemCount == 0) {
        Muted(stringResource(R.string.live_no_channels), Modifier.padding(top = 24.dp))
    }
}

@Composable
fun ChannelRow(
    ch: ChannelEntity,
    nn: NowNext?,
    favourite: Boolean,
    nowMs: Long,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TvCard(onClick = onClick, onLongClick = onLongClick, modifier = modifier.fillMaxWidth().height(64.dp), color = Palette.surfaceLow) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                ch.numberLabel,
                style = MaterialTheme.typography.titleSmall,
                color = Palette.textDim,
                textAlign = TextAlign.End,
                modifier = Modifier.width(48.dp)
            )
            ChannelLogo(ch.logoUrl, ch.name, favourite = favourite)
            // Provider names are long ("US: NBC 4 (WDIV) DETROIT HD"), so the name gets the larger share of the row.
            Row(Modifier.weight(1.2f), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    ch.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                CatchupMark(ch, Modifier.padding(start = 6.dp))
            }
            Column(Modifier.weight(1f)) {
                when {
                    nn == null -> SkeletonLines()
                    nn.now == null -> Muted(stringResource(R.string.common_no_guide))
                    else -> {
                        Text(
                            programmeTitle(nn.now.title),
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            nn.next?.let { stringResource(R.string.live_next, formatTime(it.startMs), programmeTitle(it.title)) }
                                ?: stringResource(R.string.live_until, formatTime(nn.now.stopMs)),
                            style = MaterialTheme.typography.bodySmall,
                            color = Palette.textDim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            if (nn?.now != null) ProgressLine(nn.progress(nowMs), Modifier.width(80.dp))
        }
    }
}

@Composable
private fun SkeletonLines() {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(Modifier.fillMaxWidth(0.46f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(Palette.surfaceHigh))
        Box(Modifier.fillMaxWidth(0.28f).height(9.dp).clip(RoundedCornerShape(5.dp)).background(Palette.surface))
    }
}

@Composable
private fun PlaceholderRow() {
    Box(Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(14.dp)).background(Palette.surfaceLow))
}

private val arrowKeys = setOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight)
