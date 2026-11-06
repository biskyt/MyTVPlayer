package com.biskyt.mytvplayer.ui.theme

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
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

/**
 * The boards' text is sized as the rest of the app sizes text (the theme's scale, not the boards' 4/3), and is a step
 * larger with Settings › large text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = PlainApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class BoardTextScaleTest {
    @get:Rule val compose = createUiComposeRule()

    /** The heights of a theme-sized 30 and of the board's 30 px title as the pages show it. */
    private fun heights(large: Boolean): Pair<Int, Int> {
        compose.setContent {
            MyTvTheme(largeText = large) {
                Column {
                    Text("Theme", style = TextStyle(fontFamily = Nunito, fontSize = (30 * TEXT_SCALE).sp, fontWeight = FontWeight(800)))
                    Text("Shown", style = BoardStyle(30, 800).current)
                }
            }
        }
        fun height(text: String) = compose.onNodeWithText(text).fetchSemanticsNode().size.height
        return height("Theme") to height("Shown")
    }

    @Test
    fun `without large text, the boards' text is the size the rest of the app gives it`() {
        val (theme, shown) = heights(large = false)
        assertEquals(theme, shown)
    }

    @Test
    fun `with large text, the boards' text is larger`() {
        val (theme, shown) = heights(large = true)
        assertTrue("$shown > $theme", shown > theme)
    }
}
