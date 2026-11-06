package com.biskyt.mytvplayer.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.drawScreen
import com.biskyt.mytvplayer.testutil.focusedText
import com.biskyt.mytvplayer.testutil.waitAdvancingClock
import com.biskyt.mytvplayer.ui.common.BoardOption
import com.biskyt.mytvplayer.ui.common.BoardPanel
import com.biskyt.mytvplayer.ui.common.rememberFirstFocus
import com.biskyt.mytvplayer.ui.theme.LocalAccent
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
import com.biskyt.mytvplayer.ui.theme.bd
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every side panel is a BoardPanel (the owner, 2026-11-06): the boards' 400 wide, with their padding, and options that
 * fill with the accent colour.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class BoardPanelUiTest {
    @get:Rule val compose = createUiComposeRule<ComponentActivity>()

    @Test
    fun `a panel is the boards' 400 wide, its content inside their padding`() {
        var panel = 0
        var inside = 0
        compose.setContent {
            MyTvTheme {
                with(LocalDensity.current) {
                    panel = 400.bd.toPx().roundToInt()
                    inside = (400.bd - 26.bd * 2).toPx().roundToInt()
                }
                BoardPanel(onDismiss = {}) { BoardOption("Watch", modifier = rememberFirstFocus()) {} }
            }
        }
        compose.waitAdvancingClock(stepMs = 50) { compose.focusedText() == "Watch" }
        val option = compose.onNode(hasText("Watch") and hasClickAction()).fetchSemanticsNode()
        val window = compose.onAllNodes(isRoot()).fetchSemanticsNodes().last().size.width
        assertEquals("The option's width", inside.toFloat(), option.size.width.toFloat(), 1f)
        // The panel is on the right: its padding from the window's right edge.
        assertEquals("Its right edge", (window - (panel - inside) / 2).toFloat(), option.positionInRoot.x + option.size.width, 1f)
    }

    @Test
    fun `the option in use has a ring inside its fill`() {
        var accent = Color.Unspecified
        var onAccent = Color.Unspecified
        compose.setContent {
            MyTvTheme {
                accent = LocalAccent.current.color
                onAccent = LocalAccent.current.onAccent
                Column {
                    BoardOption("English", modifier = rememberFirstFocus(), selected = true) {}
                    Text("Under it")
                }
            }
        }
        compose.waitAdvancingClock(stepMs = 50) { compose.focusedText() == "English" }
        val screen = compose.drawScreen()
        val node = compose.onNode(hasText("English") and hasClickAction()).fetchSemanticsNode()
        val middle = (node.positionInRoot.y + node.size.height / 2).toInt()
        val x = node.positionInRoot.x.toInt()
        assertEquals("Its edge", accent.toArgb(), Color(screen.getPixel(x, middle)).toArgb())
        // The ring: 2 wide, inside the edge (at this test's density, about pixels 2 to 4 across); then the fill again.
        assertNear("Its ring", onAccent, Color(screen.getPixel(x + 3, middle)))
        assertEquals("Inside its ring", accent.toArgb(), Color(screen.getPixel(x + 8, middle)).toArgb())
    }

    /** [actual] is [expected], give or take the edge's smoothing (a few steps in each channel). */
    private fun assertNear(what: String, expected: Color, actual: Color) {
        val far = listOf(expected.red - actual.red, expected.green - actual.green, expected.blue - actual.blue).maxOf { abs(it) }
        assertTrue("$what: $actual, not near $expected", far < NEAR)
    }

    private companion object {
        const val NEAR = 0.04f
    }
}
