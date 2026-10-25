package com.biskyt.mytvplayer.testutil

import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.printToString
import java.util.concurrent.TimeUnit
import org.robolectric.shadows.ShadowLooper

/**
 * How long UI tests wait for something to appear or take focus. A wait ends as soon as its condition holds, so this
 * only matters when a test fails; it's generous because CI runners can be slow and busy.
 */
const val UI_WAIT_MS = 15_000L

/**
 * Waits for [condition], running the main looper's due work before each check and moving its (simulated) clock on by
 * [stepMs]: what appears after posted work (a database change notice) needs the looper to run, and what appears after
 * a delay (a debounce, a timeout) needs that time to pass as well.
 */
fun ComposeTestRule.waitAdvancingClock(stepMs: Long = 0, condition: () -> Boolean) = waitUntil(UI_WAIT_MS) {
    ShadowLooper.idleMainLooper(stepMs, TimeUnit.MILLISECONDS)
    condition()
}

/** Waits for [text] (or text containing it) on screen, as [waitAdvancingClock] does; on a timeout, says what's shown. */
fun ComposeTestRule.waitForText(text: String, stepMs: Long = 0) = try {
    waitAdvancingClock(stepMs) { onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
} catch (e: ComposeTimeoutException) {
    throw AssertionError("'$text' not shown. Screen:\n${screen()}", e)
}

/** What's on screen, for a wait that timed out: every window, as a side panel is one of its own. */
fun ComposeTestRule.screen(): String = onAllNodes(isRoot(), useUnmergedTree = true).printToString()

/** What every other thread is doing (the top of its stack), for a wait that timed out. */
fun otherThreads(): String = Thread.getAllStackTraces().filterKeys { it.isAlive && it != Thread.currentThread() }.entries
    .joinToString("\n") { (t, stack) -> "${t.name} ${t.state}: " + stack.take(8).joinToString(" < ") }

/** The text of whatever has focus ("" for focus on something without text). */
fun ComposeTestRule.focusedText(): String = onAllNodes(isFocused()).fetchSemanticsNodes().joinToString(" | ") { node ->
    node.config.getOrNull(SemanticsProperties.Text)?.joinToString().orEmpty()
}

/**
 * Runs what's due on the main thread for a few frames without moving the (simulated) clock on: for work that follows
 * a key press, without letting timed work (a player's stall check or retry) happen.
 */
fun ComposeTestRule.idleFrames() = repeat(10) { waitAdvancingClock { true } }

/** The topmost window's root: where keys go (a side panel is a window of its own). */
fun ComposeTestRule.topRoot() = onAllNodes(isRoot()).let { it[it.fetchSemanticsNodes().lastIndex] }

/** A remote key event ([action] down or up, [repeat] while held) to the topmost window; whether the app kept it. */
@OptIn(ExperimentalTestApi::class)
fun ComposeTestRule.sendKey(code: Int, action: Int, repeat: Int = 0): Boolean =
    topRoot().performKeyPress(KeyEvent(NativeKeyEvent(0, 0, action, code, repeat))).also { idleFrames() }

/** Lets a second of (simulated) time pass, idling as it goes: for checking that something did *not* happen. */
fun ComposeTestRule.settle() = repeat(10) {
    ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
    waitForIdle()
}
