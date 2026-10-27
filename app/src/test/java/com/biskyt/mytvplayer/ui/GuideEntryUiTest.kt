package com.biskyt.mytvplayer.ui

import android.os.Handler
import android.os.Looper
import androidx.arch.core.executor.ArchTaskExecutor
import androidx.arch.core.executor.TaskExecutor
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.data.ChannelFilter
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.Navigators
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.addHomeTv
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.focusedText
import com.biskyt.mytvplayer.testutil.settle
import com.biskyt.mytvplayer.testutil.waitAdvancingClock
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
import java.util.concurrent.LinkedBlockingQueue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The TV guide keeps the remote's highlight in it: Right from the categories goes back into its programmes, and a
 * highlight the TV moved elsewhere by itself while programmes load comes back (ported from MyPlexPlayer 0.10.2).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class GuideEntryUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val provider = FakeProvider()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()

    @Before
    fun setUp() = runBlocking<Unit> {
        app.container.addHomeTv(provider, guide = true)
    }

    @After
    fun tearDown() = provider.close()

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.settle()
    }

    private fun live() {
        compose.setContent {
            MyTvTheme { NavHost(remember { navigators.new(Screen.Live(ChannelFilter.All)) }) { ScreenContent(it) } }
        }
        compose.waitAdvancingClock { compose.focusedText().contains("CHANNEL ONE") }
    }

    /** A programme in the guide (or a row without one). */
    private fun inGuide(focus: String) = "Now on" in focus || "Next on" in focus || "No guide" in focus

    /** The steps reported: click the TV guide button, scroll the guide, back to the categories, and into the guide again. */
    @Test
    fun `from the categories, right goes back into the guide, not to the buttons above it`() {
        live()
        compose.onNode(hasText("TV guide") and hasClickAction()).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitAdvancingClock { compose.focusedText().contains("TV guide") }
        press(Key.DirectionCenter)
        // The button keeps focus (the guide is below it, a press away).
        compose.waitAdvancingClock(50) { compose.onAllNodes(hasText("Now on one.uk")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("on the button after the click: ${compose.focusedText()}", compose.focusedText().contains("TV guide"))
        // (Past the touch screen's Earlier and Later buttons, which the test app shows.)
        repeat(3) { if (!inGuide(compose.focusedText())) press(Key.DirectionDown) }
        assertTrue("in the guide: ${compose.focusedText()}", inGuide(compose.focusedText()))
        repeat(3) { press(Key.DirectionRight) }
        // Over to the categories (Left at the guide's edge scrolls back through catch-up first).
        compose.onNode(hasText("All channels") and hasClickAction()).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.settle()
        assertTrue("in the categories: ${compose.focusedText()}", compose.focusedText().contains("All channels"))
        repeat(8) {
            press(Key.DirectionRight)
            val focus = compose.focusedText()
            assertTrue("Right ${it + 1} from the categories: $focus", inGuide(focus))
        }
    }

    /**
     * The Guide button from the list takes focus into the guide, which keeps it while its programmes load and arrive.
     * (Before 1.5.0 a placeholder cell held focus and was replaced when they arrived; some TVs then handed focus to
     * the Home tab. The guide is now one focus stop that stays put, whatever its rows do.)
     */
    @Test
    fun `the guide keeps focus while its programmes load and arrive`() {
        live()
        val held = holdDatabase()
        try {
            press(Key.Guide)
            compose.settle()
            assertTrue(
                "in the guide while loading: ${compose.focusedText()}",
                inGuide(compose.focusedText()) || compose.focusedText() == ""
            )
            assertFalse(compose.focusedText(), compose.focusedText().contains("CHANNEL ONE"))
        } finally {
            release(held)
        }
        compose.waitAdvancingClock(50) { compose.focusedText().contains("Now on one.uk") }
    }

    /** Opened on the guide, the list to show is found before the guide is there: Right meanwhile mustn't fail. */
    @Test
    fun `right from the categories before the guide is there doesn't fail`() {
        val held = holdDatabase()
        try {
            compose.setContent {
                MyTvTheme { NavHost(remember { navigators.new(Screen.Live(guide = true)) }) { ScreenContent(it) } }
            }
            compose.waitAdvancingClock(50) {
                compose.onAllNodes(hasText("All channels") and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNode(hasText("All channels") and hasClickAction()).performSemanticsAction(SemanticsActions.RequestFocus)
            compose.settle()
            press(Key.DirectionRight)
        } finally {
            release(held)
        }
        compose.settle()
    }

    /** Holds the database's work (Room answers), until [release]. */
    private fun holdDatabase(): LinkedBlockingQueue<Runnable> {
        val held = LinkedBlockingQueue<Runnable>()
        ArchTaskExecutor.getInstance().setDelegate(
            object : TaskExecutor() {
                override fun executeOnDiskIO(runnable: Runnable) {
                    held += runnable
                }

                override fun postToMainThread(runnable: Runnable) {
                    Handler(Looper.getMainLooper()).post(runnable)
                }

                override fun isMainThread() = Looper.getMainLooper().isCurrentThread
            }
        )
        return held
    }

    private fun release(held: LinkedBlockingQueue<Runnable>) {
        ArchTaskExecutor.getInstance().setDelegate(null)
        generateSequence { held.poll() }.forEach(ArchTaskExecutor.getInstance()::executeOnDiskIO)
    }
}
