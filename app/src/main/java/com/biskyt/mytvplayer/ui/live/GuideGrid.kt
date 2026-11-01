package com.biskyt.mytvplayer.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.AppContainer
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.container
import com.biskyt.mytvplayer.data.EpgRepository
import com.biskyt.mytvplayer.data.db.ChannelEntity
import com.biskyt.mytvplayer.data.db.ChannelProgramme
import com.biskyt.mytvplayer.data.db.PlaylistEntity
import com.biskyt.mytvplayer.data.programmeTitle
import com.biskyt.mytvplayer.ui.LocalNavigator
import com.biskyt.mytvplayer.ui.Navigator
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.common.BatchLoader
import com.biskyt.mytvplayer.ui.common.CardShape
import com.biskyt.mytvplayer.ui.common.CatchupMark
import com.biskyt.mytvplayer.ui.common.ChannelLogo
import com.biskyt.mytvplayer.ui.common.FocusStroke
import com.biskyt.mytvplayer.ui.common.LocalTouch
import com.biskyt.mytvplayer.ui.common.Muted
import com.biskyt.mytvplayer.ui.common.TouchStepButtons
import com.biskyt.mytvplayer.ui.common.collectIds
import com.biskyt.mytvplayer.ui.common.formatDay
import com.biskyt.mytvplayer.ui.common.formatDayTime
import com.biskyt.mytvplayer.ui.common.formatRange
import com.biskyt.mytvplayer.ui.common.formatTime
import com.biskyt.mytvplayer.ui.common.ignoreHeldSelect
import com.biskyt.mytvplayer.ui.common.isLive
import com.biskyt.mytvplayer.ui.common.isSelect
import com.biskyt.mytvplayer.ui.common.isToday
import com.biskyt.mytvplayer.ui.common.numberLabel
import com.biskyt.mytvplayer.ui.common.onOptionsKey
import com.biskyt.mytvplayer.ui.common.onTap
import com.biskyt.mytvplayer.ui.common.rememberDetails
import com.biskyt.mytvplayer.ui.common.rememberMinuteClock
import com.biskyt.mytvplayer.ui.theme.LocalAccent
import com.biskyt.mytvplayer.ui.theme.Palette
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val HALF_HOUR = 30 * 60_000L
private const val HOUR = 2 * HALF_HOUR

// The window is as many half hours as the screen has room for (see [span]): on a TV, an hour and a half.
private const val MIN_HALF_HOURS = 2

// Programmes are fetched in 6-hour chunks starting on 3-hour boundaries, so the window always fits in one chunk and
// moving it by an hour usually needs no new query (and no rows blanking while they reload).
private const val CHUNK_STEP = 3 * HOUR
private const val CHUNK_MS = 6 * HOUR

/** At most what always fits in one chunk, from the latest half hour in it that the window can start on. */
private const val MAX_HALF_HOURS = ((CHUNK_MS - CHUNK_STEP + HALF_HOUR) / HALF_HOUR).toInt()

private const val DAY_MS = 24 * HOUR
private const val SHOWN_ROWS = 300
private const val NEW_LIST_MS = 5_000L

/**
 * The guide's highlight: a row (its position in the channel list) and a time; the programme on that time in that row
 * is the highlighted one. Moving up and down keeps the time, so the highlight stays in the same column.
 */
private class GuideCursor(row: Int, time: Long) {
    var row by mutableIntStateOf(row)
    var time by mutableLongStateOf(time)

    /** Whether it has been placed on arriving (until then, the row it's on isn't the channel to remember). */
    var placed by mutableStateOf(false)

    /** Moves the user has made (a key or a tap): a placement that took a while doesn't undo them. */
    var moves = 0
}

/** The rows' programmes: fetched per 6-hour chunk, for rows on screen only (see [BatchLoader]). */
private class GuideRows(
    private val scope: CoroutineScope,
    private val epg: EpgRepository,
    private val playlistId: () -> Long,
    private val catchup: GuideCatchup,
    private val window: GuideWindow
) {
    private var chunk = Long.MIN_VALUE
    private var guide: BatchLoader<List<ChannelProgramme>>? = null
    private var past: BatchLoader<List<ChannelProgramme>>? = null

    /**
     * What rows had in chunks the window has left: shown while the window's chunk loads, instead of blanking. The most
     * recently used rows only, as the loaders keep.
     */
    private val shown = object : LinkedHashMap<Long, List<ChannelProgramme>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, List<ChannelProgramme>>?) = size > SHOWN_ROWS
    }

    /** The loader for the window's chunk: a fresh one per chunk. */
    fun guide(): BatchLoader<List<ChannelProgramme>> {
        val start = window.start / CHUNK_STEP * CHUNK_STEP
        guide?.takeIf { chunk == start }?.let { return it }
        guide?.entries?.let(shown::putAll)
        past?.entries?.let(shown::putAll)
        chunk = start
        past = null
        return BatchLoader<List<ChannelProgramme>>(scope) { ids ->
            epg.programmes(playlistId(), ids, start, start + CHUNK_MS, details = false).let { r -> ids.associateWith { r[it].orEmpty() } }
        }.also { guide = it }
    }

    /** In the past, catch-up channels' programmes come from the provider (see [GuideCatchup]). */
    fun loaderFor(ch: ChannelEntity): BatchLoader<List<ChannelProgramme>> {
        val guide = guide()
        // Back to the present: going back again fetches the past afresh (programmes have ended meanwhile).
        if (!window.inPast) {
            past = past?.let {
                it.entries.let(shown::putAll)
                null
            }
        }
        if (!window.inPast || ch.archiveDays <= 0) return guide
        return past ?: catchup.loader(chunk, chunk + CHUNK_MS).also {
            guide.entries.let(shown::putAll) // shown while the provider's programmes load
            past = it
        }
    }

    /** A row's programmes as shown: the window's, or while those load, the last it had (null before any). */
    fun programmes(ch: ChannelEntity) = loaderFor(ch).entries[ch.streamId] ?: shown[ch.streamId]

    fun hasProgrammes(ch: ChannelEntity) = programmes(ch) != null

    /** Whether a row's programmes for the window's chunk are in (not just those it had before). */
    fun loaded(ch: ChannelEntity) = loaderFor(ch).entries[ch.streamId] != null

    /** Asks for a row's programmes for the window (a row on screen, or the highlighted one). */
    fun request(ch: ChannelEntity) {
        if (ch.archiveDays > 0 && window.inPast) catchup.rows[ch.streamId] = ch
        loaderFor(ch).request(ch.streamId)
    }

    /** A row's programmes in the window (as shown), and which of them is on [time] (-1 when there are none). */
    fun inWindow(ch: ChannelEntity, time: Long): Pair<List<ChannelProgramme>, Int> {
        val visible = visibleIn(programmes(ch).orEmpty(), window.start, window.end)
        return visible to cellAt(visible, time)
    }
}

private fun visibleIn(programmes: List<ChannelProgramme>, windowStart: Long, windowEnd: Long): List<ChannelProgramme> =
    programmes.filter { it.stopMs > windowStart && it.startMs < windowEnd }

/** The programme on [time], else the next one after it, else the last: what a highlight on [time] shows. */
internal fun cellAt(visible: List<ChannelProgramme>, time: Long): Int =
    visible.indexOfFirst { it.stopMs > time }.takeIf { it >= 0 } ?: visible.lastIndex

/**
 * A time for the highlight on the programme after ([dir] 1) or before (-1) [visible]`[i]`: the first that a time can
 * pick out (guides can have programmes that overlap, or last no time at all), or null at the window's edge.
 */
internal fun stepTime(visible: List<ChannelProgramme>, i: Int, dir: Int, windowStart: Long): Long? {
    val others = if (dir > 0) i + 1..visible.lastIndex else i - 1 downTo 0
    return others.firstNotNullOfOrNull { j -> timeOn(visible, j, windowStart) }
}

/** A time that picks out [visible]`[j]`: the earliest on it that the ones before it have all ended by (if any). */
private fun timeOn(visible: List<ChannelProgramme>, j: Int, windowStart: Long): Long? {
    val earlier = (0 until j).maxOfOrNull { visible[it].stopMs } ?: Long.MIN_VALUE
    return maxOf(visible[j].startMs, windowStart, earlier).takeIf { cellAt(visible, it) == j }
}

/** The narrowest a minute is drawn: the window's half hours are stretched to fill the width. */
private val DpPerMinute = 6.dp
private val ChannelColumn = 288.dp
private val arrowKeys = setOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight)

/**
 * The full TV guide grid. Rows come from the same paged channel list; programmes are fetched only for rows on
 * screen and only around the visible time window.
 *
 * The grid is one focus stop with a highlight of its own (a [GuideCursor]): every arrow press, held or not, moves the
 * highlight one row or one programme, whatever is loaded or on screen. Left and right past the window's edge move the
 * window (at least an hour); the highlight stays in the grid at its earliest and latest times. Up from the first row,
 * and Back, leave it for the filter box (see LiveScreen).
 */
@Composable
fun GuideGrid(
    vm: LiveViewModel,
    items: LazyPagingItems<ChannelEntity>,
    play: (ChannelEntity) -> Unit,
    entry: FocusRequester,
    /** Where Up from the first row goes (the filter box). */
    up: FocusRequester
) {
    val container = LocalContext.current.container
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val guideVersion by container.epg.changes.collectAsState()
    val now by rememberMinuteClock()
    // Catch-up (see GuideCatchup): only once the user scrolls back before now.
    val playlistId by vm.playlistId.collectAsState()
    val favourites = vm.favouriteIds.collectIds()
    val catchup = remember(playlistId) { GuideCatchup(container, scope, playlistId) }
    val window = remember(catchup) { GuideWindow(scope, catchup) }
    val rows = remember(window) { GuideRows(scope, container.epg, { vm.playlistId.value }, catchup, window) }
    val cursor = remember { GuideCursor(vm.focusIndex ?: 0, System.currentTimeMillis()) }
    val open by rememberUpdatedState<(ChannelEntity, ChannelProgramme?) -> Unit> { ch, p -> openProgramme(nav, ch, p, play) }
    val highlight = remember(rows, items) { GuideHighlight(cursor, rows, window, items, vm, scope, entry, up) { ch, p -> open(ch, p) } }
    var hasFocus by remember { mutableStateOf(false) }
    val noGuide = stringResource(R.string.common_no_guide)
    // After a guide update, reload the rows already shown (keeping them on screen meanwhile).
    LaunchedEffect(guideVersion) { rows.guide().refreshAll() }

    // Arriving (from the list, the player or another tab): the highlight goes to what's on now on the channel last
    // used. Focus comes too, unless the view was switched with its buttons (they keep it).
    LaunchedEffect(Unit) {
        // Another list (a category, or a search): its first row, at the same time.
        launch {
            combine(vm.filter, vm.query, ::Pair).drop(1).collectLatest {
                cursor.row = 0
                vm.focusStreamId = null
                vm.listState.scrollToItem(0)
                // When the new list's rows arrive, to the top again: a list keeps the channel on top in view, wherever
                // it is in the new one. (A search typed and deleted at once makes no new list: nothing arrives.)
                withTimeoutOrNull(NEW_LIST_MS) {
                    snapshotFlow { items.itemSnapshotList }.drop(1).first()
                    vm.listState.requestScrollToItem(0)
                }
            }
        }
        // Focus first: the grid is one focus stop, there whichever row the highlight goes to (that can take a lookup,
        // and the user may have moved on meanwhile).
        withFrameNanos { }
        if (vm.restoreFocusPending) {
            vm.restoreFocusPending = false
            runCatching { entry.requestFocus() }
        }
        val moves = cursor.moves
        val row = vm.revealFocused() ?: 0.also { vm.listState.scrollToItem(0) }
        if (cursor.moves == moves) highlight.place(row)
        cursor.placed = true
    }
    // A shorter list (a channel hidden, or no longer a favourite): the highlight stays on it.
    LaunchedEffect(items.itemCount) {
        if (cursor.row >= items.itemCount && items.itemCount > 0) highlight.place(items.itemCount - 1)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val span = span(window.start, maxWidth)
        // Before the rows are drawn: the highlight's steps and the window's moves go by this length too.
        window.length = span.length
        val midnight = remember(span) { midnightIn(span) }
        Column(Modifier.fillMaxSize()) {
            FocusedInfo(highlight, now)
            TimeHeader(span, midnight, now) { dir -> window.move(dir, null) { cursor.time = it } }
            GridFocus(
                label = { highlight.at()?.let { (ch, p) -> cellTitle(ch, p, now, noGuide.takeIf { rows.hasProgrammes(ch) }) } },
                modifier = Modifier.fillMaxSize()
                    .focusRequester(entry)
                    .onFocusChanged { hasFocus = it.isFocused }
                    .ignoreHeldSelect()
                    .onOptionsKey(highlight::options)
                    .onKeyEvent(highlight::onKey)
                    .focusable()
            ) {
                LazyColumn(
                    state = vm.listState,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(bottom = 40.dp)
                ) {
                    items(count = items.itemCount, key = items.itemKey { it.streamId }) { index ->
                        val ch = items[index] ?: return@items
                        LaunchedEffect(ch.streamId, rows.loaderFor(ch)) { rows.request(ch) }
                        // Derived per row, so moving the highlight only recomposes the rows it leaves and enters.
                        val time by remember(index) { derivedStateOf { cursor.time.takeIf { cursor.row == index } } }
                        // The highlight's channel, for the list and coming back (also when it got here before the row did).
                        if (time != null && cursor.placed) LaunchedEffect(ch.streamId) { vm.focusMoved(ch.streamId, index) }
                        GuideRow(
                            ch = ch,
                            favourite = ch.streamId in favourites,
                            programmes = rows.programmes(ch),
                            span = span,
                            now = now,
                            highlight = time?.takeIf { hasFocus },
                            onTap = { p -> highlight.tap(index, ch, p) },
                            onLongTap = { p -> highlight.longTap(index, ch, p) }
                        )
                    }
                }
                if (now in span.start..span.end) DayLine(span, now, LocalAccent.current.color)
                midnight?.let { DayLine(span, it, Palette.textDim) }
            }
        }
    }
}

/** The window as drawn: from [start], [halfHours] long, with a minute [perMinute] wide. */
@Immutable
private data class Span(val start: Long, val halfHours: Int, val perMinute: Dp) {
    val length get() = halfHours * HALF_HOUR
    val end get() = start + length

    fun width(ms: Long) = perMinute * (ms / 60_000f)
}

/**
 * The window from [start] in [width]: as many half hours as fit beside the channel names, stretched to fill the rest.
 * (A window wider than the screen had programmes off its edge, and the highlight could go there.)
 */
private fun span(start: Long, width: Dp): Span {
    val times = maxOf(width - ChannelColumn, DpPerMinute * (MIN_HALF_HOURS * 30))
    val halfHours = (times / (DpPerMinute * 30)).toInt().coerceAtMost(MAX_HALF_HOURS)
    return Span(start, halfHours, times / (halfHours * 30f))
}

/**
 * The grid's one focus stop. [label] (what the highlight is on) is what it says it is, to accessibility and tests.
 * Read here, in a scope of its own, so a key press recomposes this box and the rows it changes, not the whole grid.
 */
@Composable
private fun GridFocus(label: () -> String?, modifier: Modifier, content: @Composable () -> Unit) {
    val text = label()
    Box(modifier.semantics { contentDescription = text.orEmpty() }) { content() }
}

/** The highlight ([cursor]) and what the remote's keys do with it. */
private class GuideHighlight(
    private val cursor: GuideCursor,
    private val rows: GuideRows,
    private val window: GuideWindow,
    private val items: LazyPagingItems<ChannelEntity>,
    private val vm: LiveViewModel,
    private val scope: CoroutineScope,
    /** The grid's own focus (a tap takes it there). */
    private val self: FocusRequester,
    private val up: FocusRequester,
    /** Opens a programme (OK, or a tap). */
    private val open: (ChannelEntity, ChannelProgramme?) -> Unit
) {
    private var revealing: Job? = null
    private var okHeld = false

    /** The channel in row [row], once its page is loaded (paging throws for a row it doesn't have yet). */
    private fun channelAt(row: Int) = if (row in 0 until items.itemCount) items.peek(row) else null

    /** The channel and programme the highlight is on. */
    fun at(): Pair<ChannelEntity, ChannelProgramme?>? {
        val ch = channelAt(cursor.row) ?: return null
        val (visible, i) = rows.inWindow(ch, cursor.time)
        return ch to visible.getOrNull(i)
    }

    fun place(row: Int) {
        cursor.row = row
        channelAt(row)?.let { vm.focusMoved(it.streamId, row) }
    }

    private fun moveTo(row: Int) {
        place(row)
        revealing?.cancel()
        if (!vm.listState.inFullView(row)) revealing = scope.launch { vm.listState.reveal(row) }
    }

    /**
     * Left (-1) or right (1): the previous or next programme, moving the window past its edge. The window doesn't move
     * while the row's programmes for it are loading: it would move on an hour a press, past programmes not yet shown.
     */
    private fun step(dir: Int) {
        val ch = channelAt(cursor.row) ?: return
        val (visible, i) = rows.inWindow(ch, cursor.time)
        val next = if (i >= 0) stepTime(visible, i, dir, window.start) else null
        when {
            next != null -> cursor.time = next
            rows.loaded(ch) -> window.move(dir, visible.getOrNull(i)) { cursor.time = it }
            else -> rows.request(ch) // (scrolled off screen by touch, it may not have been asked for)
        }
    }

    /** A tap on a programme (a touch screen): the highlight goes there, and it opens. */
    fun tap(row: Int, ch: ChannelEntity, p: ChannelProgramme?) {
        touch(row, ch, p)
        open(ch, p)
    }

    /** A long press on a programme: the highlight goes there, and its options open. */
    fun longTap(row: Int, ch: ChannelEntity, p: ChannelProgramme?) {
        touch(row, ch, p)
        vm.openOptions(ch, p)
    }

    private fun touch(row: Int, ch: ChannelEntity, p: ChannelProgramme?) {
        cursor.moves++
        val (visible, _) = rows.inWindow(ch, cursor.time)
        visible.indexOf(p).takeIf { it >= 0 }?.let { timeOn(visible, it, window.start) }?.let { cursor.time = it }
        moveTo(row)
        runCatching { self.requestFocus() }
    }

    fun options() {
        at()?.let { (ch, p) -> vm.openOptions(ch, p) }
    }

    /**
     * Every arrow press moves the highlight one step (Up from the first row goes to the filter box);
     * OK opens what it's on and holding OK opens its options (as Info/Menu do, see [GuideGrid]).
     */
    fun onKey(e: KeyEvent): Boolean = when {
        e.key.isSelect() -> true.also { ok(e) }
        e.key !in arrowKeys -> false
        e.type != KeyEventType.KeyDown -> true
        else -> arrow(e)
    }

    private fun arrow(e: KeyEvent): Boolean {
        cursor.moves++
        return when {
            e.key == Key.DirectionUp && cursor.row > 0 -> true.also { moveTo(cursor.row - 1) }
            // Leaving takes a press of its own: held, Up stops on the first row (as Down does on the last).
            e.key == Key.DirectionUp -> e.nativeKeyEvent.repeatCount > 0 || runCatching { up.requestFocus() }.isSuccess
            e.key == Key.DirectionDown -> true.also { if (cursor.row < items.itemCount - 1) moveTo(cursor.row + 1) }
            else -> true.also { step(if (e.key == Key.DirectionLeft) -1 else 1) }
        }
    }

    /** A press opens; held, the options open instead (a press that began elsewhere never gets here: ignoreHeldSelect). */
    private fun ok(e: KeyEvent) {
        when {
            e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0 -> okHeld = false
            e.type == KeyEventType.KeyDown -> if (!okHeld) {
                okHeld = true
                options()
            }
            e.type == KeyEventType.KeyUp && !okHeld -> at()?.let { (ch, p) -> open(ch, p) }
        }
    }
}

/** Whether row [index] is on screen in full. */
private fun LazyListState.inFullView(index: Int): Boolean {
    val end = layoutInfo.viewportEndOffset - layoutInfo.afterContentPadding
    return layoutInfo.visibleItemsInfo.any { it.index == index && it.offset >= 0 && it.offset + it.size <= end }
}

/** Scrolls the list just enough to show row [index] in full (it's usually the row next to one on screen). */
internal suspend fun LazyListState.reveal(index: Int) {
    val info = layoutInfo
    val end = info.viewportEndOffset - info.afterContentPadding
    val item = info.visibleItemsInfo.firstOrNull { it.index == index }
    when {
        item == null -> {
            val inFull = info.visibleItemsInfo.count { it.offset >= 0 && it.offset + it.size <= end }
            scrollToItem(if (index < firstVisibleItemIndex) index else maxOf(0, index - maxOf(1, inFull) + 1))
        }
        item.offset < 0 -> scrollBy(item.offset.toFloat())
        item.offset + item.size > end -> scrollBy((item.offset + item.size - end).toFloat())
    }
}

/** What a programme's cell says: its title (⟲ when it can be replayed), or, without one, [noGuide] (null: not loaded). */
private fun cellTitle(ch: ChannelEntity, p: ChannelProgramme?, now: Long, noGuide: String?): String = when {
    p != null -> programmeTitle(p.title).let { if (ch.archiveDays > 0 && p.stopMs <= now) "⟲ $it" else it }
    else -> noGuide.orEmpty()
}

/** Local midnight within the window [span], if there is one. */
private fun midnightIn(span: Span): Long? {
    val zone = ZoneId.systemDefault()
    val next = Instant.ofEpochMilli(span.start).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    return next.takeIf { it < span.end }
}

/** A line down the grid at the time [at] in the window: now, or where a day begins. */
@Composable
private fun DayLine(span: Span, at: Long, color: Color) {
    Box(Modifier.offset(x = ChannelColumn + span.width(at - span.start)).width(2.dp).fillMaxHeight().background(color))
}

@Composable
private fun FocusedInfo(highlight: GuideHighlight, now: Long) {
    // Two lines for the channel, time and description: most descriptions can be read just by moving around.
    Column(Modifier.fillMaxWidth().height(86.dp)) {
        val (ch, p) = remember(highlight) { derivedStateOf(highlight::at) }.value ?: return@Column
        if (p != null) {
            // Descriptions aren't kept for the whole grid (they're large); the highlighted one is read when it rests.
            val about = rememberDetails(ch.playlistId, p, delayMs = 300).value?.description
            Text(programmeTitle(p.title), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // The day too, when it isn't today: the times alone don't say how far ahead the guide is.
            val day = formatDay(p.startMs, now).takeIf { !isToday(p.startMs, now) }
            val time = listOfNotNull(day, formatRange(p.startMs, p.stopMs)).joinToString(" ")
            Muted("${ch.name} · $time" + (about?.let { " · $it" } ?: ""), maxLines = 2)
        } else {
            Text(ch.name, style = MaterialTheme.typography.titleMedium)
            Muted(stringResource(R.string.common_no_guide))
        }
    }
}

@Composable
private fun TimeHeader(span: Span, midnight: Long?, now: Long, onMove: (dir: Int) -> Unit) {
    Row(Modifier.fillMaxWidth().height(if (LocalTouch.current) 48.dp else 30.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.width(ChannelColumn).padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            // A touch screen has no ◀ ▶ to reach past the edge: buttons move the window instead.
            TouchStepButtons(stringResource(R.string.live_guide_earlier), stringResource(R.string.live_guide_later), onMove)
        }
        for (i in 0 until span.halfHours) {
            val time = span.start + i * HALF_HOUR
            // A new day starts here: its name instead of 00:00. The first time says the day, too ("Today 20:00"), so
            // the times say how far ahead (or back) the guide is.
            val newDay = midnight?.takeIf { it >= time && it < time + HALF_HOUR }
            Text(
                when {
                    newDay != null -> formatDay(newDay, now)
                    i == 0 -> formatDayTime(time, now)
                    else -> formatTime(time)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (newDay != null) Palette.text else Palette.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(span.width(HALF_HOUR)).padding(start = 8.dp)
            )
        }
    }
}

@Composable
private fun GuideRow(
    ch: ChannelEntity,
    favourite: Boolean,
    programmes: List<ChannelProgramme>?,
    span: Span,
    now: Long,
    /** The highlight's time when it's on this row (and the grid has focus). */
    highlight: Long?,
    onTap: (ChannelProgramme?) -> Unit,
    onLongTap: (ChannelProgramme?) -> Unit
) {
    Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.width(ChannelColumn).padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(ch.numberLabel, style = MaterialTheme.typography.bodyMedium, color = Palette.textDim, modifier = Modifier.width(44.dp))
            ChannelLogo(ch.logoUrl, ch.name, 56.dp, 34.dp, favourite)
            // A size down from the list's, on two lines: most channel names fit in full.
            Text(
                ch.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 10.dp).weight(1f, fill = false)
            )
            CatchupMark(ch, Modifier.padding(start = 4.dp))
        }
        val visible = remember(programmes, span) { visibleIn(programmes.orEmpty(), span.start, span.end) }
        if (visible.isEmpty()) {
            // One cell for the whole window, so the row can still be highlighted and played.
            Cell(
                span.width(span.length),
                live = true,
                highlighted = highlight != null,
                cellTitle(ch, null, now, stringResource(R.string.common_no_guide).takeIf { programmes != null }),
                { onTap(null) },
                { onLongTap(null) }
            )
        } else {
            val highlighted = highlight?.let { cellAt(visible, it) }
            var cursor = span.start
            visible.forEachIndexed { i, p ->
                val start = maxOf(p.startMs, span.start)
                if (start > cursor) Spacer(Modifier.width(span.width(start - cursor)))
                val end = minOf(p.stopMs, span.end)
                Cell(
                    span.width(end - start),
                    p.isLive(now),
                    i == highlighted,
                    cellTitle(ch, p, now, noGuide = null),
                    { onTap(p) },
                    { onLongTap(p) }
                )
                cursor = end
            }
        }
    }
}

/** A programme: not focusable itself (the grid is, see [GuideGrid]); drawn as a focused card when [highlighted]. */
@Composable
private fun Cell(width: Dp, live: Boolean, highlighted: Boolean, title: String, onTap: () -> Unit, onLongTap: () -> Unit) {
    Box(
        Modifier.width(width).fillMaxHeight().padding(horizontal = 2.dp)
            .clip(CardShape)
            .background(
                when {
                    highlighted -> Palette.surfaceHigh
                    live -> Palette.surface
                    else -> Palette.surfaceLow
                }
            )
            .then(if (highlighted) Modifier.border(FocusStroke, CardShape) else Modifier)
            .onTap(onLongTap, onTap)
            // (For accessibility services: a screen reader's double tap, and its long press.)
            .semantics {
                onClick { true.also { onTap() } }
                onLongClick { true.also { onLongTap() } }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp)
        )
    }
}

/** The guide's time window, and moving it (see [nextWindow]). */
internal class GuideWindow(
    private val scope: CoroutineScope,
    private val catchup: GuideCatchup,
    private val now: () -> Long = System::currentTimeMillis
) {
    /** The half hour it was opened in, or last moved in: "now", as far as the window goes. */
    private var anchor = now() / HALF_HOUR * HALF_HOUR

    var start by mutableLongStateOf(anchor)
        private set

    /** How long it is: the half hours the screen has room for (set as the grid is laid out, see [span]). */
    var length = MAX_HALF_HOURS * HALF_HOUR
    val end get() = start + length

    /**
     * Before "now" as of the last move: past programmes of catch-up channels are then fetched from the provider. Time
     * passing doesn't count (the guide left open past the half hour mustn't start asking the provider).
     */
    val inPast: Boolean get() = start < anchor

    private var lookingUp = false

    /** Moves the window, then [then] gets the time to show beyond [p]. False at the limits. */
    fun move(dir: Int, p: ChannelProgramme?, then: (target: Long) -> Unit = {}): Boolean {
        if (dir < 0 && catchup.needsLookup(start)) {
            // First time back past now: find out whether any channel has catch-up, then carry on (once, however many
            // times it's asked meanwhile).
            if (!lookingUp) {
                lookingUp = true
                scope.launch {
                    val days = catchup.lookUpDays()
                    lookingUp = false
                    if (days > 0) move(dir, p, then)
                }
            }
            return true
        }
        val (next, target) = nextWindow(start, length, dir, p, catchup.days ?: 0) ?: return false
        anchor = now() / HALF_HOUR * HALF_HOUR
        start = next
        then(target)
        return true
    }
}

/** OK on a programme: a finished one on a catch-up channel is replayed; anything else plays the channel live. */
private fun openProgramme(nav: Navigator, ch: ChannelEntity, p: ChannelProgramme?, play: (ChannelEntity) -> Unit) {
    val start = p?.takeIf { ch.archiveDays > 0 && it.stopMs <= System.currentTimeMillis() }?.startMs
    if (start != null) nav.push(Screen.CatchupPlayer(ch.playlistId, ch.streamId, start)) else play(ch)
}

/**
 * The TV guide's past, which is only ever loaded on request so the guide's normal loading never touches the provider:
 * how far back it may go is looked up the first time the user presses left at "now", and past programmes of catch-up
 * channels are fetched from the provider only for rows on screen while the window is in the past.
 */
internal class GuideCatchup(private val c: AppContainer, private val scope: CoroutineScope, private val playlistId: Long) {
    /** The longest catch-up in the playlist, once looked up. */
    var days by mutableStateOf<Int?>(null)
        private set

    /** The catch-up channels on screen, by stream id (the loader only gets ids). */
    val rows = HashMap<Long, ChannelEntity>()

    /** Going back from [windowStart]: true the first time it would pass now, before [days] is known. */
    fun needsLookup(windowStart: Long) = days == null && windowStart <= System.currentTimeMillis() / HALF_HOUR * HALF_HOUR

    suspend fun lookUpDays(): Int = c.db.channels().maxArchiveDays(playlistId).also { days = it }

    fun loader(from: Long, to: Long) = BatchLoader<List<ChannelProgramme>>(scope) { ids ->
        val p = c.playlists.get(playlistId)
        // Side by side: a slow provider costs one round trip for the rows on screen, not one per row.
        coroutineScope { ids.map { id -> async { id to programmes(p, id, from, to) } }.awaitAll().toMap() }
    }

    private suspend fun programmes(p: PlaylistEntity?, id: Long, from: Long, to: Long): List<ChannelProgramme> {
        val ch = rows[id]
        val listing = if (p == null || ch == null) {
            null
        } else {
            try {
                c.catchup.listing(p, ch)
            } catch (_: java.io.IOException) {
                null
            }
        }
        suspend fun guide() = c.epg.programmes(playlistId, listOf(id), from, to, details = false)[id].orEmpty()
        // The provider didn't answer: the row keeps what the downloaded guide has.
        if (ch == null || listing == null) return guide()
        val now = System.currentTimeMillis()
        // M3U: any time can be replayed, so the guide's own programmes stay; hour slots fill the past it doesn't cover.
        if (p?.isM3u == true) {
            val known = guide()
            val slots = c.catchup.playable(listing, ch)
                .filter { s -> s.stopMs > from && s.startMs < to && known.none { it.startMs < s.stopMs && it.stopMs > s.startMs } }
                .map { ChannelProgramme(id, it.startMs, it.stopMs, it.title, null, it.description) }
            return (known + slots).sortedBy { it.startMs }
        }
        // What can be replayed, plus what's on now and later (the rest isn't offered).
        return (c.catchup.playable(listing, ch) + listing.programmes.filter { it.stopMs > now })
            .distinctBy { it.startMs }
            .filter { it.stopMs > from && it.startMs < to }
            .sortedBy { it.startMs }
            .map { ChannelProgramme(id, it.startMs, it.stopMs, it.title, null, it.description) }
    }
}

/**
 * The window after moving past its edge from [p] in direction [dir]: at least an hour, and far enough to show the
 * time just beyond [p]; with the time focus goes to. Null at the limits (now minus [catchupDays], three days ahead).
 */
private fun nextWindow(windowStart: Long, length: Long, dir: Int, p: ChannelProgramme?, catchupDays: Int): Pair<Long, Long>? {
    val target = if (dir > 0) p?.stopMs ?: (windowStart + length) else (p?.startMs ?: windowStart) - 1
    val wanted = if (dir > 0) {
        maxOf(windowStart + HOUR, target / HALF_HOUR * HALF_HOUR - length + HALF_HOUR)
    } else {
        minOf(windowStart - HOUR, target / HALF_HOUR * HALF_HOUR)
    }
    val now = System.currentTimeMillis() / HALF_HOUR * HALF_HOUR
    val next = wanted.coerceIn(now - catchupDays * DAY_MS, now + 3 * DAY_MS)
    return if (next == windowStart) null else next to target.coerceIn(next, next + length - 1)
}
