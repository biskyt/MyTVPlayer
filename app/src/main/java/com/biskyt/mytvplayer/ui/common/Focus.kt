package com.biskyt.mytvplayer.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.currentCompositeKeyHash
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.flow.first

/**
 * Modifier that takes focus once the element is shown (and again whenever [key] changes, e.g. when data arrives),
 * so each screen opens with the remote already on its main content.
 */
@Composable
fun rememberFirstFocus(key: Any? = Unit): Modifier {
    val r = remember { FocusRequester() }
    val visit = LocalFocusVisit.current
    val back = LocalReturnFocus.current
    val hash = currentCompositeKeyHash
    LaunchedEffect(key) {
        // Once a visit: a list's item composed again as it scrolls back into view must not take focus back from the
        // item the remote is on.
        val site = hash to key
        if (visit?.taken?.contains(site) == true) return@LaunchedEffect
        // Coming back to a list, the row last used takes focus instead, if it is there (see ReturnFocus).
        if (back?.rowTakesFocus() == true) return@LaunchedEffect
        runCatching { r.requestFocus() }.onSuccess { visit?.taken?.add(site) }
    }
    return Modifier.focusRequester(r)
}

/**
 * The [rememberFirstFocus] requests made during one visit to a screen (NavHost gives each visit its own) or one opening
 * of a side panel (SidePanel gives each its own).
 */
class FocusVisit {
    internal val taken = mutableSetOf<Pair<Int, Any?>>()
}

val LocalFocusVisit = staticCompositionLocalOf<FocusVisit?> { null }

/**
 * The row of a list that had focus last (rows record themselves with [returnsFocus], by a label that is different for
 * each row on the page), kept with the screen's state: coming back to the list (from the screen that row opened), that
 * row has focus again, not the first one. If it is not there (a row shown only with some setting on), the first one
 * has it after all.
 */
class ReturnFocus internal constructor(private val last: MutableState<String?>) {
    /** Coming back to [last], until it has focus or [giveUp]. */
    private val pending = mutableStateOf(last.value != null)
    private var restored = false

    internal fun note(row: String) {
        last.value = row
    }

    internal fun wants(row: String) = pending.value && last.value == row

    internal fun restored() {
        restored = true
        pending.value = false
    }

    /** The row did not appear: the first focus goes ahead. */
    internal fun giveUp() {
        pending.value = false
    }

    /** Waits until the row has focus (true) or is not coming (false). */
    internal suspend fun rowTakesFocus(): Boolean {
        snapshotFlow { pending.value }.first { !it }
        return restored
    }
}

/** A [ReturnFocus] for a list: the row is saved with the screen, and each visit starts out returning to it. */
@Composable
fun rememberReturnFocus(): ReturnFocus {
    val last = rememberSaveable { mutableStateOf<String?>(null) }
    val back = remember(last) { ReturnFocus(last) }
    // A row shows in the first frame or two; one that has not by then is not on the page.
    LaunchedEffect(back) {
        repeat(ROW_FRAMES) { withFrameNanos {} }
        back.giveUp()
    }
    return back
}

private const val ROW_FRAMES = 2

val LocalReturnFocus = staticCompositionLocalOf<ReturnFocus?> { null }

/** For a row named [row] in a list with a [ReturnFocus]: notes when it has focus, and gives it focus again coming back. */
@Composable
fun Modifier.returnsFocus(row: String): Modifier {
    val back = LocalReturnFocus.current ?: return this
    // Derived: only the row it is for recomposes, not every row at each move of the remote.
    val wanted by remember(back, row) { derivedStateOf { back.wants(row) } }
    return onFocusChanged { if (it.isFocused) back.note(row) }.restoreFocusIf(wanted) { back.restored() }
}

/**
 * Gives this element focus once when [wanted] (e.g. returning to a list, on the item last opened), then calls
 * [onRestored]. Items not composed yet simply try when they appear.
 */
@Composable
fun Modifier.restoreFocusIf(wanted: Boolean, onRestored: () -> Unit): Modifier {
    if (!wanted) return this
    val r = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (runCatching { r.requestFocus() }.isSuccess) onRestored() }
    return focusRequester(r)
}
