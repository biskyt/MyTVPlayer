package com.biskyt.mytvplayer.testutil

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher

/*
 * Compose test rules whose composition (recomposer, LaunchedEffects, collectAsState) runs on the main thread only, as
 * it does in the app.
 *
 * The plain rules run it on an UnconfinedTestDispatcher, where a coroutine carries on on whichever thread resumed it.
 * A Room flow resumes its collector on Room's query thread (arch_disk_io_*); the state it writes then wakes the
 * recomposer on that thread too, which records the change into the composition while the main thread may be
 * composing it. Now and then the change was lost: the UI never showed what the database had (no Catch-up tab, a
 * stale selected category), however long a test waited.
 */

/** [createComposeRule], with the composition confined to the main thread. */
@OptIn(ExperimentalTestApi::class)
fun createUiComposeRule(): ComposeContentTestRule = createComposeRule(mainThreadEffects())

/** [createAndroidComposeRule], with the composition confined to the main thread. */
@OptIn(ExperimentalTestApi::class)
inline fun <reified A : ComponentActivity> createUiComposeRule(): AndroidComposeTestRule<ActivityScenarioRule<A>, A> =
    createAndroidComposeRule<A>(mainThreadEffects())

/** [createEmptyComposeRule] (for a test that launches its own activity), with the composition confined to the main thread. */
@OptIn(ExperimentalTestApi::class)
fun createUiEmptyComposeRule(): ComposeTestRule = createEmptyComposeRule(mainThreadEffects())

/**
 * A StandardTestDispatcher whose work runs on the main thread as soon as it's queued, through the main looper (which
 * every wait in a test runs). On its own it only runs work as the test clock moves on, and waitForIdle doesn't move it
 * unless a frame is due: scrolling to a node, for one, would then never finish.
 *
 * Compose 1.8's public hooks don't do it: an IdlingResource that runs the queue isn't asked while scrolling to a node
 * (which then loops until out of memory), and a TestDispatcher can't be subclassed or wrapped (the rule replaces any
 * other interceptor with an unconfined one). So the scheduler's queue notices are read directly; if a
 * kotlinx-coroutines-test upgrade renames them, every UI test fails at once (NoSuchFieldException), not now and then.
 * The consumer is stopped when the test ends (see [stopMainThreadEffects]), so work resumed late can't run in the next.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun mainThreadEffects(): TestDispatcher {
    val dispatcher = StandardTestDispatcher()
    val scheduler = dispatcher.scheduler
    val queued = scheduler.dispatchEvents()
    CoroutineScope(Dispatchers.Main + consumers).launch { queued.consumeEach { scheduler.runCurrent() } }
    return dispatcher
}

private val consumers = SupervisorJob()

/** Stops the tests' queue consumers (TestApp, when a test ends). */
fun stopMainThreadEffects() = consumers.cancelChildren()

/** Tells of each piece of work queued on the scheduler (what runTest waits on); kotlinx-coroutines-test keeps it private. */
@Suppress("UNCHECKED_CAST")
private fun TestCoroutineScheduler.dispatchEvents(): ReceiveChannel<Unit> =
    TestCoroutineScheduler::class.java.getDeclaredField("dispatchEvents").apply { isAccessible = true }.get(this)
        as ReceiveChannel<Unit>
