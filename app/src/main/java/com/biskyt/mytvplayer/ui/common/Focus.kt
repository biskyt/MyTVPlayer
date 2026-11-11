package com.biskyt.mytvplayer.ui.common

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.currentCompositeKeyHash
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import kotlin.random.Random
import kotlinx.coroutines.flow.first

/**
 * Modifier that takes focus once the element is shown (and again whenever [key] changes, e.g. when data arrives),
 * so each screen opens with the remote already on its main content. Shown again (content swapped back, a panel's
 * question answered), it takes focus again.
 *
 * With [once], it takes focus once a visit to the screen: for the first item of a list that scrolls on a TV. The list
 * composes it again as it scrolls back into view, which must not take focus from the item the remote is on (the top
 * guide was skipped in TV guide sources). The visit it took focus in is kept in its saved state, which a lazy list
 * keeps while the item is out of view and drops when the item is really removed. Outside a NavHost (no visit), it
 * takes focus each time, as without it.
 */
@Composable
fun rememberFirstFocus(key: Any? = Unit, once: Boolean = false): Modifier {
    val r = remember { FocusRequester() }
    val back = LocalReturnFocus.current
    val visit = LocalFocusVisit.current
    val takenIn = if (once) rememberSaveable(key) { mutableStateOf<Int?>(null) } else null
    LaunchedEffect(key) {
        if (takenIn != null && visit != null && takenIn.value == visit.id) return@LaunchedEffect
        // Coming back to a list, the row last used takes focus instead, if it is there (see ReturnFocus).
        if (back?.rowTakesFocus() == true) return@LaunchedEffect
        runCatching { r.requestFocus() }.onSuccess { takenIn?.value = visit?.id }
    }
    return Modifier.focusRequester(r)
}

/** One visit to a screen: NavHost gives each its own (see rememberFirstFocus's once). */
class FocusVisit {
    /** Random, not counted from 0: an id saved before the app was restarted must not match a new visit. */
    val id = Random.nextInt()
}

val LocalFocusVisit = staticCompositionLocalOf<FocusVisit?> { null }

/**
 * The item of a screen that had focus last (cards and buttons record themselves with [returnsFocus], by where they are
 * in the screen), kept with the screen's state: coming back to the screen (from one that item opened), that item has
 * focus again, not the first one (the owner, 2026-11-05 for Settings, 2026-11-06 for every screen). If it is not there
 * (a row shown only with some setting on), the first one has it after all.
 */
class ReturnFocus internal constructor(private val last: MutableState<Int?>) {
    /** Coming back to the screen (it was shown before, and an item had focus then). */
    val cameBack = last.value != null

    /** Coming back to [last], until it has focus or [giveUp]. */
    private val pending = mutableStateOf(cameBack)
    private var restored = false

    /** Coming back, and the item not found yet. */
    val returning: Boolean get() = pending.value

    /**
     * [item] has focus: the one to give it back to. Not while coming back: a real TV gives the screen's first item (the
     * top bar's Home) focus for a moment as the screen over it goes, which must not take the place of the item used last
     * (the emulators, 2026-11-08; Robolectric does not).
     */
    internal fun note(item: Int) {
        if (pending.value && !restoring) return
        last.value = item
    }

    /** A screen's own restore (see restoreFocusIf) is giving an item focus: noted, as it ends coming back. */
    internal var restoring = false

    internal fun wants(item: Int) = pending.value && last.value == item

    internal fun restored() {
        restored = true
        pending.value = false
    }

    /** The item did not appear: the first focus goes ahead. */
    internal fun giveUp() {
        pending.value = false
    }

    /** Came back, and the item used last was not there (see KeepsFocus: focus left on the top bar moves on). */
    val notFound: Boolean get() = cameBack && !pending.value && !restored

    /**
     * While coming back: waits until the item has focus (true) or is not coming (false). Afterwards false, so that a
     * first focus later in the visit (content shown again) is not held back.
     */
    internal suspend fun rowTakesFocus(): Boolean {
        if (!pending.value) return false
        snapshotFlow { pending.value }.first { !it }
        return restored
    }
}

/** A [ReturnFocus] for a screen: the item is saved with the screen, and each visit starts out returning to it. */
@Composable
fun rememberReturnFocus(): ReturnFocus {
    val last = rememberSaveable { mutableStateOf<Int?>(null) }
    val back = remember(last) { ReturnFocus(last) }
    // A list's item shows within a few frames of the screen; one that has not by then is not there.
    LaunchedEffect(back) {
        repeat(SETTLE_FRAMES) { withFrameNanos {} }
        back.giveUp()
    }
    return back
}

val LocalReturnFocus = staticCompositionLocalOf<ReturnFocus?> { null }

/**
 * For a card or button on a screen with a [ReturnFocus]: notes when it has focus, and gives it focus again coming back.
 * It is known by where it is in the screen (its composition key: in a lazy list, its item's key), so nothing needs
 * naming it.
 */
@Composable
fun Modifier.returnsFocus(): Modifier {
    val back = LocalReturnFocus.current ?: return this
    val item = currentCompositeKeyHash
    // Derived: only the item it is for recomposes, not every item at each move of the remote.
    val wanted by remember(back, item) { derivedStateOf { back.wants(item) } }
    // Asked again just before: items drawn in a loop without a key share a key, and only the first restores.
    return onFocusChanged { if (it.isFocused) back.note(item) }
        .restoreFocusIf(wanted, still = { back.wants(item) })
}

/**
 * Gives this element focus once when [wanted] (e.g. returning to a list, on the item last opened), then calls
 * [onRestored]. Items not composed yet simply try when they appear. [still] is asked again just before. Once it has
 * focus, coming back to the screen ends (see [ReturnFocus]): the screen's own choice replaces the item used last.
 */
@Composable
fun Modifier.restoreFocusIf(wanted: Boolean, still: () -> Boolean = { true }, onRestored: () -> Unit = {}): Modifier {
    if (!wanted) return this
    val r = remember { FocusRequester() }
    val back = LocalReturnFocus.current
    LaunchedEffect(Unit) {
        if (!still()) return@LaunchedEffect
        // Noted while coming back (see ReturnFocus.note), so that the item given focus is the one used last from now:
        // a screen's own restore (the channel played last) is not undone by the item used before.
        back?.restoring = true
        val done = runCatching { r.requestFocus(FocusDirection.Enter) }.getOrDefault(false)
        back?.restoring = false
        if (!done) return@LaunchedEffect
        back?.restored()
        onRestored()
    }
    return focusRequester(r)
}

/**
 * A screen that is not left with nothing focused (the owner, 2026-11-06). Coming back to it, the item used last takes
 * focus again (see [ReturnFocus]). With nothing focused after that (that item gone), or when the item with focus goes
 * (a list reloads under it), its first item below the top bar takes focus. A screen's own choice comes first: this
 * waits [SETTLE_FRAMES] frames and does nothing if anything on the screen has focus by then, and nothing as the screen
 * first opens. [key]: the entry shown.
 */
@Composable
fun KeepsFocus(key: Any, content: @Composable () -> Unit) {
    val screen = remember(key) { FocusRequester() }
    val state = remember(key) { ScreenFocus() }
    val focusManager = LocalFocusManager.current
    var focused by remember(key) { mutableStateOf(false) }
    var hadFocus by remember(key) { mutableStateOf(false) }
    val back = rememberReturnFocus()
    val track = Modifier.onFocusChanged {
        focused = it.hasFocus
        if (it.hasFocus) hadFocus = true
    }
    Box(Modifier.fillMaxSize().focusRequester(screen).then(track).focusGroup()) {
        CompositionLocalProvider(LocalReturnFocus provides back, LocalScreenFocus provides state, content = content)
    }
    LaunchedEffect(key) {
        // Back, with the item used last gone: a real TV has given the top bar's Home focus meanwhile (see
        // ReturnFocus.note). It moves on into the screen, as a screen with nothing focused does: once, as the return ends.
        var returnEnding = back.cameBack
        snapshotFlow { focused to back.returning }.collect { (hasIt, returning) ->
            // Not as a screen first opens: it chooses its own first focus, maybe once its data is in.
            if (returning || !(hadFocus || back.cameBack)) return@collect
            val fromTopBar = returnEnding && back.notFound
            returnEnding = false
            if (hasIt && !(fromTopBar && state.inTopBar)) return@collect
            repeat(SETTLE_FRAMES) { withFrameNanos {} }
            if (focused && !(fromTopBar && state.inTopBar)) return@collect
            runCatching { screen.requestFocus() }
            // Its first item may be the top bar's Home: the content below it, then.
            if (state.inTopBar) focusManager.moveFocus(FocusDirection.Down)
        }
    }
}

/** What [KeepsFocus] needs to know of a screen: whether its top bar (see TopBar) has focus. */
class ScreenFocus {
    var inTopBar = false
}

val LocalScreenFocus = staticCompositionLocalOf<ScreenFocus?> { null }

/** How long a screen's own focus has to settle: a list's items are laid out within a few frames. */
private const val SETTLE_FRAMES = 10

/** Tries [done] each frame for as long as a screen's own focus takes to settle (see SETTLE_FRAMES); whether it was. */
suspend fun settleFor(done: () -> Boolean): Boolean {
    repeat(SETTLE_FRAMES) {
        withFrameNanos {}
        if (done()) return true
    }
    return false
}
