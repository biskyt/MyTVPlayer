package com.biskyt.mytvplayer.ui.theme

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.onNodeWithText
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.testutil.PlainApp
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The boards' pages keep Settings › large text: their text is a step larger when it is on, and as drawn when off. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = PlainApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class BoardTextScaleTest {
    @get:Rule val compose = createUiComposeRule()

    /** The heights of the board's 30 px title as drawn, and as the pages draw it. */
    private fun heights(large: Boolean): Pair<Int, Int> {
        compose.setContent {
            MyTvTheme(largeText = large) {
                Column {
                    Text("Drawn", style = boardText(30, 800))
                    Text("Shown", style = BoardStyle(30, 800).current)
                }
            }
        }
        fun height(text: String) = compose.onNodeWithText(text).fetchSemanticsNode().size.height
        return height("Drawn") to height("Shown")
    }

    @Test
    fun `with large text, the boards' text is larger`() {
        val (drawn, shown) = heights(large = true)
        assertTrue("$shown > $drawn", shown > drawn)
    }

    @Test
    fun `without it, the boards' sizes are kept`() {
        val (drawn, shown) = heights(large = false)
        assertEquals(drawn, shown)
    }
}
