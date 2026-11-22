package com.biskyt.mytvplayer.ui.common

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.appString
import com.biskyt.mytvplayer.data.Favourites
import com.biskyt.mytvplayer.ui.theme.LocalAccent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** How a list of favourites is laid out, which says which keys move one: a row, a column, or a grid of [columns]. */
enum class MoveLayout(val arrows: String) {
    ROW("◀ ▶"),
    COLUMN("▲ ▼"),
    GRID("◀ ▶ ▲ ▼")
}

/**
 * Moving a favourite within its list (Home's rows, Live TV's Favourites, the Movies and Series Favourites grids). Each
 * step is stored at once, so every list showing it follows; Back puts the order back as it was.
 */
class FavouriteMover(private val scope: CoroutineScope, private val favourites: Favourites, private val playlistId: () -> Long) {
    /**
     * The favourite being moved: its list's type (CAT_LIVE, CAT_SERIES or CAT_MOVIE) and id, the order before, and how
     * many of them its list shows (Home's rows show the first few).
     */
    class Moving(val type: String, val id: Long, val original: List<Long>, val shown: Int)

    var moving by mutableStateOf<Moving?>(null)
        private set

    // The order as moved so far, and as stored: steps change the first at once, and a write catches the second up
    // (writes queued behind it, from a held arrow, then find nothing left to do).
    private var order = emptyList<Long>()
    private var stored = emptyList<Long>()
    private val lock = Mutex()

    /** The id of the favourite of [type] being moved, if one is. */
    fun movingId(type: String) = moving?.takeIf { it.type == type }?.id

    fun start(type: String, id: Long, shown: Int = Int.MAX_VALUE) = scope.launch {
        lock.withLock {
            val original = favourites.order(playlistId(), type)
            // Every favourite gets a place of its own (those from before 1.2.0 share place 0).
            favourites.setOrder(playlistId(), type, original)
            order = original
            stored = original
            moving = Moving(type, id, original, shown)
        }
    }

    fun step(places: Int) {
        val m = moving ?: return
        store(m.type, Favourites.moved(order, m.id, places, m.shown))
    }

    fun save() {
        moving = null
    }

    fun cancel() {
        val m = moving ?: return
        moving = null
        store(m.type, m.original)
    }

    private fun store(type: String, target: List<Long>) {
        order = target
        scope.launch {
            lock.withLock {
                val next = order
                if (next != stored) {
                    favourites.setOrder(playlistId(), type, next, before = stored)
                    stored = next
                }
            }
        }
    }
}

/**
 * "Move", in a favourite's options: starts moving it ([type] CAT_LIVE, CAT_SERIES or CAT_MOVIE; its list shows [shown]
 * of them) once [close] closes them.
 */
fun moveAction(mover: FavouriteMover, type: String, id: Long, close: () -> Unit, shown: Int = Int.MAX_VALUE) =
    ItemAction.Run(appString(R.string.common_move)) {
        close()
        mover.start(type, id, shown)
    }

/**
 * While a favourite is moved, Back puts its list back as it was. Leaving the list, or changing what it shows ([keys]:
 * its filter and search), keeps the order as moved so far.
 */
@Composable
fun MoveBackHandler(mover: FavouriteMover, vararg keys: Any?) {
    BackHandler(enabled = mover.moving != null) { mover.cancel() }
    DisposableEffect(*keys) { onDispose { mover.save() } }
}

/** A row of favourites of [type] that [mover] can move: Home's. */
class MovableRow(val mover: FavouriteMover, val type: String)

/**
 * A card in a list of favourites of [type], laid out as [layout] ([columns] across in a grid). While it's the one being
 * moved it is outlined in the accent colour with arrows at its ends: the arrow keys (or a tap on an arrow) move it, and
 * OK (or a tap on it) keeps it there. Otherwise it's just the card.
 */
@Composable
fun MovableTile(mover: FavouriteMover?, type: String, id: Long, layout: MoveLayout, columns: Int = 1, content: @Composable () -> Unit) {
    val active = mover != null && mover.movingId(type) == id
    // One Box either way, so the card (and its focus) stays as moving starts and ends.
    val moved = if (mover != null && active) {
        // Laid out afresh (moved past the edge of its list, once the list scrolls to it), the card takes focus.
        // Its glow shows it has focus, and the arrows at its ends that it is being moved (no border: the owner, 2026-11-22).
        rememberFirstFocus().moveKeys(mover, layout, columns)
    } else {
        Modifier
    }
    Box(moved) {
        content()
        if (mover != null && active) MoveControls(mover, layout)
    }
}

/** Over the card being moved: a tap keeps it where it is (rather than opening it), and the arrows at its ends move it. */
@Composable
private fun BoxScope.MoveControls(mover: FavouriteMover, layout: MoveLayout) {
    val accent = LocalAccent.current
    val column = layout == MoveLayout.COLUMN
    Box(Modifier.matchParentSize().onTap(null) { mover.save() })
    val ends = if (column) Alignment.TopCenter to Alignment.BottomCenter else Alignment.CenterStart to Alignment.CenterEnd
    val arrows = if (column) "▲" to "▼" else "◀" to "▶"
    listOf(Triple(arrows.first, ends.first, -1), Triple(arrows.second, ends.second, 1)).forEach { (arrow, align, step) ->
        val nudge = if (column) Modifier.offset(y = (step * 14).dp) else Modifier.offset(x = (step * 14).dp)
        Box(
            Modifier.align(align).then(nudge).size(28.dp).clip(CircleShape).background(accent.color).onTap(null) { mover.step(step) },
            contentAlignment = Alignment.Center
        ) { Text(arrow, style = MaterialTheme.typography.labelMedium, color = accent.onAccent) }
    }
}

/**
 * The keys of the card being moved, seen before the card itself: the arrows of [layout] move it ([columns] places up or
 * down in a grid) and the others are kept from moving focus off it; OK keeps it there.
 */
private fun Modifier.moveKeys(mover: FavouriteMover, layout: MoveLayout, columns: Int) = onPreviewKeyEvent { e ->
    if (e.key.isSelect()) {
        if (e.type == KeyEventType.KeyUp) mover.save()
        return@onPreviewKeyEvent true
    }
    val step = when (e.key) {
        Key.DirectionLeft -> if (layout == MoveLayout.COLUMN) 0 else -1
        Key.DirectionRight -> if (layout == MoveLayout.COLUMN) 0 else 1
        Key.DirectionUp -> when (layout) {
            MoveLayout.ROW -> 0
            MoveLayout.COLUMN -> -1
            MoveLayout.GRID -> -columns
        }
        Key.DirectionDown -> when (layout) {
            MoveLayout.ROW -> 0
            MoveLayout.COLUMN -> 1
            MoveLayout.GRID -> columns
        }
        else -> return@onPreviewKeyEvent false
    }
    // Held, an arrow keeps moving it.
    if (e.type == KeyEventType.KeyDown && step != 0) mover.step(step)
    true
}

/** How to move the favourite while one is moved, under its list's title. */
@Composable
fun MoveHint(mover: FavouriteMover, type: String, layout: MoveLayout, modifier: Modifier = Modifier) {
    if (mover.moving?.type != type) return
    // By touch: the arrows on the card, and the card itself.
    val hint = if (LocalTouch.current) {
        stringResource(
            R.string.common_move_hint_touch
        )
    } else {
        stringResource(R.string.common_move_hint, layout.arrows)
    }
    Muted(hint, modifier)
}

/** Keeps the favourite being moved, at [index] of the list (null when none is), in view. */
@Composable
fun KeepInView(state: LazyListState, index: () -> Int?) = KeepInView(index, { state.layoutInfo.visibleItemsInfo.indexRange { it.index } }) {
    state.scrollToItem(it)
}

/** Keeps the favourite being moved, at [index] of the grid (null when none is), in view. */
@Composable
fun KeepInView(state: LazyGridState, index: () -> Int?) = KeepInView(index, { state.layoutInfo.visibleItemsInfo.indexRange { it.index } }) {
    state.scrollToItem(it)
}

// The index is read as it changes (a step, a page loading), without recomposing the list around it.
@Composable
private fun KeepInView(index: () -> Int?, visible: () -> IntRange, scrollTo: suspend (Int) -> Unit) {
    val current by rememberUpdatedState(index)
    LaunchedEffect(Unit) {
        snapshotFlow { current() }.distinctUntilChanged().collect { i -> if (i != null && i >= 0 && i !in visible()) scrollTo(i) }
    }
}

private inline fun <T> List<T>.indexRange(index: (T) -> Int) = if (isEmpty()) IntRange.EMPTY else index(first())..index(last())
