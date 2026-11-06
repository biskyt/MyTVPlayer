package com.biskyt.mytvplayer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.idleFrames
import com.biskyt.mytvplayer.testutil.waitAdvancingClock
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
import com.biskyt.mytvplayer.ui.vod.PlotWithMore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A plot cut short shows More…, which opens the whole of it in a panel; one that fits shows no More… (2026-11-06). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class PlotWithMoreUiTest {
    @get:Rule val compose = createUiComposeRule()

    private fun show(plot: String) {
        compose.setContent {
            MyTvTheme {
                Column(Modifier.width(300.dp)) { PlotWithMore("Kitchen Masters", plot, TextStyle(fontSize = 14.sp), maxLines = 2) }
            }
        }
        compose.idleFrames()
    }

    private fun more() = compose.onAllNodes(hasText("More…") and hasClickAction()).fetchSemanticsNodes()

    @Test
    fun `a plot cut short opens in full from More`() {
        show(LONG)
        assertEquals(1, more().size)
        compose.onNode(hasText("More…") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        // The panel is a window of its own, with the title and the whole plot.
        compose.waitAdvancingClock(stepMs = 50) { compose.onAllNodes(isRoot()).fetchSemanticsNodes().size == 2 }
        val panel = compose.onAllNodes(isRoot())[1]
        assertTrue(compose.onAllNodes(hasText("Kitchen Masters"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        panel.assertExists()
        assertTrue(compose.onAllNodes(hasText(LONG), useUnmergedTree = true).fetchSemanticsNodes().size >= 2)
    }

    @Test
    fun `a plot that fits has no More`() {
        show("A short plot.")
        assertEquals(0, more().size)
    }

    private companion object {
        val LONG = "Two detectives on the north coast work the cases nobody else wants. ".repeat(6).trim()
    }
}
