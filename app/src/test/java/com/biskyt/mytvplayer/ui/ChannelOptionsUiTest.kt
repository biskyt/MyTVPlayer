package com.biskyt.mytvplayer.ui

import androidx.activity.ComponentDialog
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.data.ChannelFilter
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * A channel's options in Live TV (the owner, 2026-11-15): the options in their order, and More info, which Back leaves
 * for the options again, on More info.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class ChannelOptionsUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val provider = FakeProvider()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()

    @Before
    fun setUp() {
        runBlocking { app.container.addHomeTv(provider) }
        val navigator = navigators.new(Screen.Live(ChannelFilter.All))
        compose.setContent { MyTvTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
        compose.waitForText("UK: CHANNEL ONE HD", stepMs = 50)
        // Held OK, to the screen's own window: the options open in a window of their own (see AppUiTest).
        compose.onAllNodes(isRoot())[0].performKeyInput {
            keyDown(Key.DirectionCenter)
            advanceEventTime(1_500)
            keyUp(Key.DirectionCenter)
        }
        compose.waitForText("More info", stepMs = 50)
    }

    @After
    fun tearDown() = provider.close()

    /** The panel's options' labels, from the top (each option's first text). */
    private fun options() = compose.onAllNodes(hasClickAction() and hasAnyAncestor(isDialog())).fetchSemanticsNodes()
        .sortedBy { it.boundsInRoot.top }
        .mapNotNull { node -> node.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text }

    @Test
    fun `the options in their order, with switching to the grid last`() {
        assertEquals(
            listOf(
                "Watch",
                "Catch-up",
                "Restart current show",
                "Add to favourites",
                "What's on",
                "More info",
                "Change guide source…",
                "Hide channel",
                "Switch to grid view"
            ),
            options()
        )
    }

    @Test
    fun `More info shows the channel's category, and Back returns to the options on More info`() {
        // As with the remote: focus on More info, then OK.
        val moreInfo = compose.onAllNodes(hasText("More info") and hasClickAction()).onLast()
        moreInfo.performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitAdvancingClock(50) { compose.focusedText().contains("More info") }
        moreInfo.performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForText("UK | ENTERTAINMENT", stepMs = 50)
        compose.waitForText("Programmes from the last 3 days", stepMs = 50)
        assertTrue("its guide id", compose.onAllNodes(hasText("one.uk")).fetchSemanticsNodes().isNotEmpty())

        // The remote's Back, as the panel's window gets it.
        compose.runOnUiThread { (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressedDispatcher.onBackPressed() }
        compose.waitAdvancingClock(50) { compose.focusedText().contains("More info") }
        assertTrue("the panel is still open", options().contains("Watch"))
    }
}
