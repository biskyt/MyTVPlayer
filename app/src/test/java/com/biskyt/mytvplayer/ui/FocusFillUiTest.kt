package com.biskyt.mytvplayer.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.drawScreen
import com.biskyt.mytvplayer.testutil.focusedText
import com.biskyt.mytvplayer.testutil.waitAdvancingClock
import com.biskyt.mytvplayer.ui.common.MenuOption
import com.biskyt.mytvplayer.ui.common.SettingRow
import com.biskyt.mytvplayer.ui.common.rememberFirstFocus
import com.biskyt.mytvplayer.ui.theme.LocalAccent
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings rows and menu options with focus are filled with the accent colour, with no ring (the owner, 2026-11-06),
 * and their text takes the accent's text colour.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class FocusFillUiTest {
    @get:Rule val compose = createUiComposeRule<ComponentActivity>()

    private var accent = Color.Unspecified

    /** Shows [focused] with focus, beside another row, and checks the focused one's edge and inner room. */
    private fun assertFilled(focused: String, row: @Composable (String, Boolean) -> Unit) {
        compose.setContent {
            MyTvTheme {
                accent = LocalAccent.current.color
                Column {
                    row(focused, true)
                    row("Another", false)
                }
            }
        }
        compose.waitAdvancingClock(stepMs = 50) { compose.focusedText().startsWith(focused) }
        val screen = compose.drawScreen()
        val node = compose.onNode(hasText(focused, substring = true) and hasClickAction()).fetchSemanticsNode()
        val middle = (node.positionInRoot.y + node.size.height / 2).toInt()
        // At its edge (where a ring would be) and in the room before the text: the accent fill.
        listOf(1, 6).forEach { x ->
            val pixel = Color(screen.getPixel(node.positionInRoot.x.toInt() + x, middle))
            assertEquals("$focused, pixel $x across", accent.toArgb(), pixel.toArgb())
        }
    }

    @Test
    fun `a settings row with focus is filled with the accent colour`() = assertFilled("Playback") { label, first ->
        SettingRow(label, "Skip times and subtitles", {}, if (first) rememberFirstFocus() else Modifier)
    }

    @Test
    fun `a menu option with focus is filled with the accent colour`() = assertFilled("Add to favourites") { label, first ->
        MenuOption(label, "Shown first on Home", if (first) rememberFirstFocus() else Modifier) {}
    }
}
