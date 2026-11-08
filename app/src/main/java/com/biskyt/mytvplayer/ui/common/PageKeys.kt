package com.biskyt.mytvplayer.ui.common

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// The remote's channel up and down buttons move a page up or down in a long list (the owner, 2026-11-08): the channel
// list, the TV guide, and the film and series grids. Held, they go on a page at a time.

/** A list's or grid's focus for [pageKeys]: the item that has it, and the one a page press is taking it to. */
class PageFocus {
    internal var index = -1

    /** Until it has focus (see [pageItem]): a held button goes on from there. */
    internal var target by mutableStateOf<Int?>(null)
    internal var revealing: Job? = null
}

/**
 * This item, number [index] in its list or grid, for [pageKeys]: it records that it has focus (at its index now, as a
 * favourite being moved keeps focus), and takes focus as a page's target.
 */
@Composable
fun Modifier.pageItem(focus: PageFocus, index: Int): Modifier {
    var focused by remember { mutableStateOf(false) }
    if (focused) SideEffect { focus.index = index }
    // Derived: only the target recomposes at a press, not every item.
    val wanted by remember(focus, index) { derivedStateOf { focus.target == index } }
    val tracked = onFocusChanged {
        focused = it.isFocused
        if (it.isFocused) {
            focus.index = index
            focus.target = null
        }
    }
    if (!wanted) return tracked
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos {} // placed, not only composed ahead of the screen
        // Not able to take it: the next press goes on from the item that has focus.
        if (!runCatching { requester.requestFocus(FocusDirection.Enter) }.getOrDefault(false)) focus.target = null
    }
    return tracked.focusRequester(requester)
}

/**
 * Channel up and down move focus [rows] rows up or down in this list or grid of [count] items, [columns] to a row, in
 * the same column: [rows] is how many fit on the screen, so that a press shows the next page. It stops at the first and
 * last rows, so focus stays in the list. [reveal] scrolls an item into view, so that it is composed and takes focus.
 * While not [enabled] (a favourite being moved), the buttons do nothing.
 */
@Composable
fun Modifier.pageKeys(
    focus: PageFocus,
    count: () -> Int,
    columns: Int = 1,
    enabled: () -> Boolean = { true },
    rows: () -> Int,
    reveal: suspend (Int) -> Unit
): Modifier {
    val scope = rememberCoroutineScope()
    return onKeyEvent { e ->
        val step = e.key.pageStep() ?: return@onKeyEvent false
        val from = focus.target ?: focus.index
        if (e.type != KeyEventType.KeyDown || !enabled() || from < 0 || count() == 0) return@onKeyEvent true
        val to = pageTarget(from, step * rows(), columns, count())
        if (to != from) {
            focus.target = to
            focus.revealing?.cancel()
            focus.revealing = scope.launch { reveal(to) }
        }
        true
    }
}

/** The item [rowSteps] rows from item [from] (minus: up), in its column, kept between the first and last rows. */
internal fun pageTarget(from: Int, rowSteps: Int, columns: Int, count: Int): Int {
    val column = from % columns
    val lastRow = (count - 1) / columns
    val row = (from / columns + rowSteps).coerceIn(0, lastRow)
    return minOf(row * columns + column, count - 1)
}

/** How many rows fit on the list's screen (at least 1): the same however far it is scrolled. */
fun LazyListState.rowsInView(): Int {
    val info = layoutInfo
    // The tallest: a row still loading can be drawn with no height.
    val row = info.visibleItemsInfo.maxOfOrNull { it.size } ?: return 1
    return rowsFitting(info.viewportSize.height - info.beforeContentPadding - info.afterContentPadding, row, info.mainAxisItemSpacing)
}

/** How many rows fit on the grid's screen (at least 1): the same however far it is scrolled. */
fun LazyGridState.rowsInView(): Int {
    val info = layoutInfo
    val row = info.visibleItemsInfo.maxOfOrNull { it.size.height } ?: return 1
    return rowsFitting(info.viewportSize.height - info.beforeContentPadding - info.afterContentPadding, row, info.mainAxisItemSpacing)
}

private fun rowsFitting(space: Int, row: Int, gap: Int) = ((space + gap) / (row + gap).coerceAtLeast(1)).coerceAtLeast(1)

/** Whether row [index] is on screen in full. */
fun LazyListState.inFullView(index: Int): Boolean {
    val end = layoutInfo.viewportEndOffset - layoutInfo.afterContentPadding
    return layoutInfo.visibleItemsInfo.any { it.index == index && it.offset >= 0 && it.offset + it.size <= end }
}

/**
 * Scrolls the list just enough to show row [index] in full: a row next to one on screen by a little, one further away
 * to the top going up or the bottom going down.
 */
suspend fun LazyListState.reveal(index: Int) {
    val info = layoutInfo
    val end = info.viewportEndOffset - info.afterContentPadding
    val item = info.visibleItemsInfo.firstOrNull { it.index == index }
    when {
        item == null -> scrollToItem(if (index < firstVisibleItemIndex) index else maxOf(0, index - rowsInView() + 1))
        item.offset < 0 -> scrollBy(item.offset.toFloat())
        item.offset + item.size > end -> scrollBy((item.offset + item.size - end).toFloat())
    }
}

/** Scrolls the grid to show item [index]'s row in full, as [LazyListState.reveal] does a list's row. */
suspend fun LazyGridState.reveal(index: Int, columns: Int) {
    val info = layoutInfo
    val end = info.viewportEndOffset - info.afterContentPadding
    val item = info.visibleItemsInfo.firstOrNull { it.index == index }
    when {
        item == null -> {
            val row = index / columns
            scrollToItem((if (index < firstVisibleItemIndex) row else maxOf(0, row - rowsInView() + 1)) * columns)
        }
        item.offset.y < 0 -> scrollBy(item.offset.y.toFloat())
        item.offset.y + item.size.height > end -> scrollBy((item.offset.y + item.size.height - end).toFloat())
    }
}
