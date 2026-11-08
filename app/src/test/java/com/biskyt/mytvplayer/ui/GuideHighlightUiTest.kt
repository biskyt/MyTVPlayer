package com.biskyt.mytvplayer.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.data.ChannelFilter
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.Navigators
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.addHomeTv
import com.biskyt.mytvplayer.testutil.assertColourAcross
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.drawScreen
import com.biskyt.mytvplayer.testutil.focusedText
import com.biskyt.mytvplayer.testutil.waitAdvancingClock
import com.biskyt.mytvplayer.ui.theme.LocalAccent
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The TV guide's highlighted programme is filled with the accent colour, with no ring, as menus are (the owner, 2026-11-08). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class GuideHighlightUiTest {
    @get:Rule val compose = createUiComposeRule<ComponentActivity>()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val provider = FakeProvider()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()

    @Before
    fun setUp() = runBlocking<Unit> { app.container.addHomeTv(provider, guide = true) }

    @After
    fun tearDown() = provider.close()

    @Test
    fun `the highlighted programme is filled with the accent colour, to its edge`() {
        var accent = Color.Unspecified
        val navigator = navigators.new(Screen.Live(ChannelFilter.All, guide = true))
        compose.setContent {
            MyTvTheme {
                accent = LocalAccent.current.color
                NavHost(remember { navigator }) { ScreenContent(it) }
            }
        }
        // The grid's highlight, on what is on now on the first channel (not a button above the grid).
        compose.waitAdvancingClock(stepMs = 50) { compose.focusedText().startsWith("Now on") }
        val title = compose.focusedText()
        val screen = compose.drawScreen()
        // The programme's own cell: the narrowest node that can be pressed around the narrowest text of its title (the
        // grid and the line above it say the title too).
        val text = compose.onAllNodes(hasText(title), useUnmergedTree = true).fetchSemanticsNodes().minBy { it.size.width }
        val cell = compose.onAllNodes(hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes()
            .filter { it.boundsInRoot.contains(text.boundsInRoot.center) }
            .minBy { it.size.width }
        // Where a ring would be, and the room before its text.
        assertColourAcross(screen, cell, listOf(EDGE, INSIDE), accent)
    }

    private companion object {
        /** Pixels across the cell: on its edge, where the old ring was drawn; and inside it. */
        const val EDGE = 1
        const val INSIDE = 8
    }
}
