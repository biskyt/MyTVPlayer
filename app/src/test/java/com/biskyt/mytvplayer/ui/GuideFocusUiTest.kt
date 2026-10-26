package com.biskyt.mytvplayer.ui

import android.os.Handler
import android.os.Looper
import androidx.arch.core.executor.ArchTaskExecutor
import androidx.arch.core.executor.TaskExecutor
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Focus in the TV guide on a slow box, where a row's programmes arrive after the user has moved on. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class GuideFocusUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val provider = FakeProvider()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()

    @Before
    fun setUp() = runBlocking {
        val c = app.container
        c.addHomeTv(provider)
        Unit
    }

    @After
    fun tearDown() = provider.close()

    // Live TV on UK | SPORT, with one channel (no guide for it).
    @Test
    fun `a guide row's programmes arriving late don't take focus back from where the user moved`() {
        compose.setContent {
            MyTvTheme { NavHost(remember { navigators.new(Screen.Live(ChannelFilter.Category("2"))) }) { ScreenContent(it) } }
        }
        compose.waitAdvancingClock { compose.focusedText().contains("UK: ARENA SPRTS 1 FHD") }
        // Database answers wait until let through, so the guide row's programmes are still on their way…
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
        try {
            compose.onRoot().performKeyInput { pressKey(Key.Guide) }
            compose.settle()
            // The row holds focus with its placeholder (no text yet)…
            assertEquals("", compose.focusedText())
            // …when the user moves up out of the grid, to the filter.
            compose.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
            compose.waitAdvancingClock(100) { compose.focusedText().contains("Filter by name") }
        } finally {
            ArchTaskExecutor.getInstance().setDelegate(null)
            // …through Room's own pool, now that it's back.
            generateSequence { held.poll() }.forEach(ArchTaskExecutor.getInstance()::executeOnDiskIO)
        }
        // The row's cell gets its text (as do the details above the grid, for the programme last focused there).
        compose.waitAdvancingClock(50) { compose.onAllNodes(hasText("No guide information")).fetchSemanticsNodes().size > 1 }
        compose.settle()
        assertTrue(compose.focusedText(), compose.focusedText().contains("Filter by name"))
    }
}
