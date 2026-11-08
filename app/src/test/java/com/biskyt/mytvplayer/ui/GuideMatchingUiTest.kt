package com.biskyt.mytvplayer.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.Navigators
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.addHomeTv
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.focusedText
import com.biskyt.mytvplayer.testutil.waitAdvancingClock
import com.biskyt.mytvplayer.testutil.waitForText
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TV guide matching for one channel (from its menu): focus starts on the channel, not the filter, and each guide channel
 * offered says what is on now on it, before it is chosen (the owner, 2026-11-08).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class GuideMatchingUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val provider = FakeProvider()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var playlistId = 0L

    @Before
    fun setUp() = runBlocking<Unit> {
        // A second guide channel for Channel One: not used, so its programmes are not stored.
        provider.guideChannels = provider.guideChannels + ("one.extra" to "Channel One Extra")
        playlistId = app.container.addHomeTv(provider, guide = true)
    }

    @After
    fun tearDown() = provider.close()

    private fun show() {
        val navigator = navigators.new(Screen.GuideMatching(playlistId, 101))
        compose.setContent { MyTvTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
    }

    @Test
    fun `opened for a channel, focus is on that channel`() {
        show()
        compose.waitAdvancingClock(stepMs = 50) { compose.focusedText().isNotEmpty() }
        // The channel's row, with its guide channel under its name: not the filter box, which holds the name too.
        val row = compose.onAllNodes(
            isFocused() and hasText("UK: CHANNEL ONE HD", substring = true) and hasText("Channel One (", substring = true)
        )
        assertTrue("Focus is on: ${compose.focusedText()}", row.fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `each guide channel offered says what is on now, and choosing one shows it at once`() {
        show()
        compose.waitForText("Channel One Extra", stepMs = 50)
        // Read from its guide's file for the choice: it was not stored.
        compose.waitAdvancingClock(stepMs = 50) { shown("On now: Now on one.extra (", substring = true) > 0 }
        compose.waitAdvancingClock(stepMs = 50) { shown("On now: Now on one.uk (", substring = true) > 0 }

        compose.onNode(hasText("Channel One Extra") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        // Above the choices too (for the guide in use), from the programmes already read.
        compose.waitAdvancingClock(stepMs = 50) { shown("On now: Now on one.extra (", substring = true) == 2 }
    }

    private fun shown(text: String, substring: Boolean) =
        compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().size
}
