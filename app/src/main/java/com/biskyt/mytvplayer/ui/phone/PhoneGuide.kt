package com.biskyt.mytvplayer.ui.phone

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.data.ChannelFilter
import com.biskyt.mytvplayer.data.db.CategoryEntity
import com.biskyt.mytvplayer.data.db.ChannelEntity
import com.biskyt.mytvplayer.data.db.ChannelProgramme
import com.biskyt.mytvplayer.ui.LocalNavigator
import com.biskyt.mytvplayer.ui.Navigator
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.common.CatchupMark
import com.biskyt.mytvplayer.ui.common.ChannelLogo
import com.biskyt.mytvplayer.ui.common.LockedCategory
import com.biskyt.mytvplayer.ui.common.Muted
import com.biskyt.mytvplayer.ui.common.collectIds
import com.biskyt.mytvplayer.ui.common.formatDay
import com.biskyt.mytvplayer.ui.common.formatDayTime
import com.biskyt.mytvplayer.ui.common.formatRange
import com.biskyt.mytvplayer.ui.common.formatTime
import com.biskyt.mytvplayer.ui.common.isLive
import com.biskyt.mytvplayer.ui.common.isLockedCategory
import com.biskyt.mytvplayer.ui.common.numberLabel
import com.biskyt.mytvplayer.ui.common.rememberMinuteClock
import com.biskyt.mytvplayer.ui.live.ChannelOptionsSheet
import com.biskyt.mytvplayer.ui.live.GuideRows
import com.biskyt.mytvplayer.ui.live.GuideWindow
import com.biskyt.mytvplayer.ui.live.HALF_HOUR
import com.biskyt.mytvplayer.ui.live.LiveViewModel
import com.biskyt.mytvplayer.ui.live.cellTitle
import com.biskyt.mytvplayer.ui.live.liveEntries
import com.biskyt.mytvplayer.ui.live.midnightIn
import com.biskyt.mytvplayer.ui.live.openProgramme
import com.biskyt.mytvplayer.ui.live.rememberGuideRows
import com.biskyt.mytvplayer.ui.live.visibleIn
import com.biskyt.mytvplayer.ui.theme.LocalAccent
import com.biskyt.mytvplayer.ui.theme.Palette
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlinx.coroutines.launch

// The TV guide on a phone or an unfolded foldable (the owner, 2026-11-26: "All screens should have a phone layout"). The
// same ViewModel, programmes and options as the TV's guide (GuideGrid), drawn for touch: a column of channels at the
// left, and their programmes in rows that scroll sideways together under a time ruler.

/**
 * The TV guide on a touch screen, opened on top of the channel list. A tap on a programme does what OK does on a TV: a
 * finished one on a catch-up channel is replayed, and anything else plays the channel. A long press opens the options,
 * as holding OK does. [play] plays a channel (also the options' Watch).
 */
@Composable
fun PhoneGuide(vm: LiveViewModel, play: (ChannelEntity) -> Unit) {
    val nav = LocalNavigator.current
    val filter by vm.filter.collectAsState()
    // The options' "Switch to list view": back to the channel list.
    LaunchedEffect(vm.guideMode) { if (!vm.guideMode) nav.toChannelList(vm, filter) }
    val rows = rememberGuideRows(vm)
    val window = rows.window
    // One sideways position for the ruler and every row, so that they scroll together.
    val scroll = remember(window) { ScrollState(0) }
    Column(Modifier.fillMaxSize()) {
        PageTop(stringResource(R.string.live_view_guide))
        // The Catch-up tab's lists have no options (as on a TV).
        val onHide: ((CategoryEntity) -> Unit)? = if (vm.catchupMode) null else ({ vm.hideCategory(it) })
        PhoneCategoryChips(liveEntries(vm), filter, vm.filters::select, onHide)
        TimeButtons(window, scroll)
        GuideBody(vm, filter, remember(window, rows, scroll) { GuideParts(window, rows, scroll) }, play)
    }
    vm.optionsFor?.let { ch -> ChannelOptionsSheet(ch, vm, play) }
}

/** Back to the channel list: the one under the guide, or else a new one in the guide's place. */
private fun Navigator.toChannelList(vm: LiveViewModel, filter: ChannelFilter) {
    val below = stack.getOrNull(stack.lastIndex - 1)?.screen
    if (below is Screen.Live && !below.guide) pop() else replace(Screen.Live(filter, radio = vm.radio))
}

/** The time window, the rows' programmes and the shared sideways position, which the grid's parts all use. */
private class GuideParts(val window: GuideWindow, val rows: GuideRows, val scroll: ScrollState)

/**
 * Earlier and Later: they scroll the rows most of a screen's width, and at the window's edge move the window an hour
 * (see GuideWindow), as the TV's buttons do. The view then scrolls on from the same time (see [KeepTimeInView]).
 */
@Composable
private fun TimeButtons(window: GuideWindow, scroll: ScrollState) {
    val scope = rememberCoroutineScope()
    val step: (Int) -> Unit = { dir ->
        scope.launch {
            val more = if (dir > 0) scroll.canScrollForward else scroll.canScrollBackward
            if (more) scroll.animateScrollBy(dir * scroll.viewportSize * PAGE_PART) else window.move(dir, null)
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = PhoneGutter, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
    ) {
        PhonePill("◀ " + stringResource(R.string.live_guide_earlier)) { step(-1) }
        PhonePill(stringResource(R.string.live_guide_later) + " ▶") { step(1) }
    }
}

/** The part of the view's width that Earlier and Later scroll by: some of what was shown stays in view. */
private const val PAGE_PART = 0.75f

/** The programmes of the list picked, or the restricted category's notice, as on a TV. */
@Composable
private fun GuideBody(vm: LiveViewModel, filter: ChannelFilter, parts: GuideParts, play: (ChannelEntity) -> Unit) {
    val items = vm.channels.collectAsLazyPagingItems()
    val categories by vm.categories.collectAsState()
    // A channel taken off the list (from its options): a TV moves focus on from it; a touch screen has no focus to move.
    vm.removedStreamId?.let { removed -> LaunchedEffect(removed) { vm.removedStreamId = null } }
    when {
        !vm.filters.settled -> Unit
        isLockedCategory(categories, (filter as? ChannelFilter.Category)?.id) ->
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) { LockedCategory() }
        items.itemCount == 0 -> Muted(
            stringResource(R.string.live_no_channels),
            Modifier.padding(horizontal = PhoneGutter, vertical = 24.dp)
        )
        else -> BoxWithConstraints(Modifier.fillMaxSize()) {
            // A minute is drawn at least [MIN_PER_MINUTE] wide, wider when the window fits on the screen (a tablet).
            val minutes = parts.window.length / MINUTE
            val perMinute = maxOf(MIN_PER_MINUTE, (maxWidth - CHANNEL_COLUMN) / minutes.toFloat())
            KeepTimeInView(parts.window, parts.scroll, perMinute)
            Grid(vm, items, filter, parts, perMinute, play)
        }
    }
}

/**
 * When the window moves, the time in view stays in view (the rows are drawn from the window's new start), then the view
 * scrolls on in the same direction, as Earlier or Later asked.
 */
@Composable
private fun KeepTimeInView(window: GuideWindow, scroll: ScrollState, perMinute: Dp) {
    val density = LocalDensity.current
    LaunchedEffect(window, perMinute) {
        var last = window.start
        snapshotFlow { window.start }.collect { start ->
            val moved = start - last
            last = start
            if (moved != 0L) {
                val px = with(density) { perMinute.forMs(moved).toPx() }
                // A later start draws each time further left: the view goes back by as much.
                scroll.scrollTo((scroll.value - px).roundToInt())
                scroll.animateScrollBy(moved.sign * scroll.viewportSize * PAGE_PART)
            }
        }
    }
}

@Composable
private fun Grid(
    vm: LiveViewModel,
    items: LazyPagingItems<ChannelEntity>,
    filter: ChannelFilter,
    parts: GuideParts,
    perMinute: Dp,
    play: (ChannelEntity) -> Unit
) {
    val nav = LocalNavigator.current
    val now by rememberMinuteClock()
    val favourites = vm.favouriteIds.collectIds()
    val query by vm.query.collectAsState()
    // The TV's list state, so the guide is where it was back from the player; a new list starts at the top.
    val state = vm.listState
    var shown by remember { mutableStateOf(filter to query) }
    LaunchedEffect(filter, query) {
        if (shown != filter to query) {
            shown = filter to query
            state.scrollToItem(0)
        }
    }
    val window = parts.window
    Column(Modifier.fillMaxSize()) {
        TimeRuler(window.start, window.end, now, perMinute, parts.scroll)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(state = state, contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(ROW_GAP)) {
                items(count = items.itemCount, key = items.itemKey { it.streamId }) { index ->
                    val ch = items[index]
                    if (ch == null) {
                        Box(Modifier.fillMaxWidth().height(ROW_HEIGHT))
                        return@items
                    }
                    LaunchedEffect(ch.streamId, parts.rows.loaderFor(ch)) { parts.rows.request(ch) }
                    // The channel to come back to, as in the TV's guide.
                    val noteChannel = { vm.focusMoved(ch.streamId, index) }
                    GuideRow(
                        ch,
                        favourite = ch.streamId in favourites,
                        programmes = parts.rows.programmes(ch),
                        window = window,
                        now = now,
                        perMinute = perMinute,
                        scroll = parts.scroll,
                        onTap = { p ->
                            noteChannel()
                            openProgramme(nav, ch, p, play)
                        },
                        onLongTap = { p ->
                            noteChannel()
                            vm.openOptions(ch, p)
                        }
                    )
                }
            }
            if (now in window.start until window.end) NowLine(window.start, now, perMinute, parts.scroll)
        }
    }
}

/** The half hours of the window from [start] to [end], over the rows and scrolled with them. */
@Composable
private fun TimeRuler(start: Long, end: Long, now: Long, perMinute: Dp, scroll: ScrollState) {
    val midnight = remember(start, end) { midnightIn(start, end) }
    Row(Modifier.fillMaxWidth().height(RULER_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(CHANNEL_COLUMN))
        // As wide as the rows (see GuideRow), so that they share one sideways position.
        Row(Modifier.weight(1f).horizontalScroll(scroll).width(perMinute.forMs(end - start))) {
            var time = start
            while (time < end) {
                // A new day starts here: its name instead of 00:00. The first time says the day too ("Today 20:00").
                val newDay = midnight?.takeIf { it >= time && it < time + HALF_HOUR }
                Text(
                    when {
                        newDay != null -> formatDay(newDay, now)
                        time == start -> formatDayTime(time, now)
                        else -> formatTime(time)
                    },
                    color = if (newDay != null) Palette.text else Palette.textDim,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(perMinute.forMs(HALF_HOUR)).padding(start = 6.dp)
                )
                time += HALF_HOUR
            }
        }
    }
}

/** The line down the rows at [now], in the accent colour, where it is in the scrolled view. */
@Composable
private fun NowLine(start: Long, now: Long, perMinute: Dp, scroll: ScrollState) {
    val accent = LocalAccent.current.color
    Box(Modifier.fillMaxSize().padding(start = CHANNEL_COLUMN).clipToBounds()) {
        val at = perMinute.forMs(now - start)
        Box(
            Modifier.offset { IntOffset(at.roundToPx() - scroll.value, 0) }.width(NOW_LINE).fillMaxHeight().background(accent)
        )
    }
}

/**
 * A channel's row: its number and logo at the left, which a tap plays, then its programmes in the window, each as wide
 * as it is long. [programmes] is null while they load. A tap on a programme calls [onTap], a long press [onLongTap].
 */
@Composable
private fun GuideRow(
    ch: ChannelEntity,
    favourite: Boolean,
    programmes: List<ChannelProgramme>?,
    window: GuideWindow,
    now: Long,
    perMinute: Dp,
    scroll: ScrollState,
    onTap: (ChannelProgramme?) -> Unit,
    onLongTap: (ChannelProgramme?) -> Unit
) {
    val start = window.start
    val end = window.end
    Row(Modifier.fillMaxWidth().height(ROW_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
        ChannelCell(ch, favourite, { onTap(null) }, { onLongTap(null) })
        fun width(ms: Long) = perMinute.forMs(ms)
        // Every row (and the ruler) exactly as wide as the window, whatever its programmes: they share one sideways
        // position, which a row of another width would move whenever it is measured.
        Row(Modifier.weight(1f).fillMaxHeight().horizontalScroll(scroll).width(width(end - start))) {
            val visible = remember(programmes, start, end) { visibleIn(programmes.orEmpty(), start, end) }
            if (visible.isEmpty()) {
                // One block for the whole window, so the row can still be played. Blank while the programmes load.
                val title = cellTitle(ch, null, now, stringResource(R.string.common_no_guide).takeIf { programmes != null })
                ProgrammeBlock(width(end - start), title, null, live = true, { onTap(null) }, { onLongTap(null) })
            } else {
                var at = start
                visible.forEach { p ->
                    val from = maxOf(p.startMs, start)
                    if (from > at) Spacer(Modifier.width(width(from - at)))
                    val to = minOf(p.stopMs, end)
                    ProgrammeBlock(width(to - from), cellTitle(ch, p, now, null), p, p.isLive(now), { onTap(p) }, { onLongTap(p) })
                    at = to
                }
            }
        }
    }
}

/** A channel at the start of its row: its number as the channel list shows it, and its logo. */
@Composable
private fun ChannelCell(ch: ChannelEntity, favourite: Boolean, onTap: () -> Unit, onLongTap: () -> Unit) {
    Column(
        Modifier.width(CHANNEL_COLUMN).fillMaxHeight().padding(start = 8.dp, end = 6.dp)
            .clip(RoundedCornerShape(10.dp)).background(PhoneCard)
            .combinedClickable(role = Role.Button, onLongClick = onLongTap, onClick = onTap),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)
    ) {
        ChannelLogo(ch.logoUrl, ch.name, width = 52.dp, height = 30.dp, favourite = favourite)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                ch.numberLabel,
                color = Palette.textDim,
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
            CatchupMark(ch, Modifier.padding(start = 3.dp))
        }
    }
}

/** A programme, [width] wide: its [title] and, when it has one, its times. On now when [live]: a lighter fill. */
@Composable
private fun ProgrammeBlock(width: Dp, title: String, p: ChannelProgramme?, live: Boolean, onTap: () -> Unit, onLongTap: () -> Unit) {
    Column(
        Modifier.width(width).fillMaxHeight().padding(horizontal = 2.dp)
            .clip(RoundedCornerShape(10.dp)).background(if (live) PhoneChip else PhoneCard)
            .combinedClickable(role = Role.Button, onLongClick = onLongTap, onClick = onTap)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)
    ) {
        Text(
            title,
            color = Palette.text,
            fontSize = 14.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (p != null) {
            Text(
                formatRange(p.startMs, p.stopMs),
                color = Palette.textDim,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private const val MINUTE = 60_000L

/** The width of [ms] on a scale of this many dp a minute. */
private fun Dp.forMs(ms: Long): Dp = this * (ms / MINUTE.toFloat())

/** The narrowest a minute is drawn: half an hour is 120 dp, so a short programme still shows some of its title. */
private val MIN_PER_MINUTE = 4.dp
private val CHANNEL_COLUMN = 76.dp
private val ROW_HEIGHT = 64.dp
private val ROW_GAP = 6.dp
private val RULER_HEIGHT = 28.dp
private val NOW_LINE = 2.dp
