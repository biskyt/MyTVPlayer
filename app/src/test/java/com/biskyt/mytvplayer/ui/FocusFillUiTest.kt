package com.biskyt.mytvplayer.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.assertColourAcross
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.drawScreen
import com.biskyt.mytvplayer.testutil.focusedText
import com.biskyt.mytvplayer.testutil.waitAdvancingClock
import com.biskyt.mytvplayer.ui.common.BoardOption
import com.biskyt.mytvplayer.ui.common.PillButton
import com.biskyt.mytvplayer.ui.common.SettingRow
import com.biskyt.mytvplayer.ui.common.TvCard
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
 * Settings rows, menu options and buttons with focus are filled with the accent colour, with no ring (the owner,
 * 2026-11-06, and 2026-11-13 for every button), and their text takes the accent's text colour.
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
        // At its edge (where a ring would be) and in the room before the text: the accent fill.
        assertColourAcross(screen, node, listOf(1, 6), accent)
    }

    @Test
    fun `a settings row with focus is filled with the accent colour`() = assertFilled("Playback") { label, first ->
        SettingRow(label, "Skip times and subtitles", {}, if (first) rememberFirstFocus() else Modifier)
    }

    @Test
    fun `a button with focus is filled with the accent colour, with no white ring`() = assertFilled("Save") { label, first ->
        PillButton(label, {}, if (first) rememberFirstFocus() else Modifier)
    }

    @Test
    fun `a filled choice in use shows a ring inside its fill`() {
        var onAccent = Color.Unspecified
        compose.setContent {
            MyTvTheme {
                accent = LocalAccent.current.color
                onAccent = LocalAccent.current.onAccent
                TvCard(onClick = {}, modifier = rememberFirstFocus(), selected = true, fill = true) {
                    Text("English", Modifier.padding(16.dp))
                }
            }
        }
        compose.waitAdvancingClock(stepMs = 50) { compose.focusedText() == "English" }
        val screen = compose.drawScreen()
        val node = compose.onNode(hasText("English") and hasClickAction()).fetchSemanticsNode()
        val middle = (node.positionInRoot.y + node.size.height / 2).toInt()
        val x = node.positionInRoot.x.toInt()
        assertEquals("Its edge", accent.toArgb(), Color(screen.getPixel(x, middle)).toArgb())
        // The ring: 2 dp wide, inside the edge (at this test's density, pixels 2 and 3 across); then the fill again.
        assertEquals("Its ring", onAccent.toArgb(), Color(screen.getPixel(x + 3, middle)).toArgb())
        assertEquals("Inside its ring", accent.toArgb(), Color(screen.getPixel(x + 6, middle)).toArgb())
    }

    @Test
    fun `a filled card's content is not made again as focus moves on and off it`() {
        var made = 0
        compose.setContent {
            MyTvTheme {
                Column {
                    TvCard(onClick = {}, modifier = rememberFirstFocus(), fill = true) {
                        remember { made++ }
                        Text("Filled")
                    }
                    TvCard(onClick = {}) { Text("Other") }
                }
            }
        }
        compose.waitAdvancingClock(stepMs = 50) { compose.focusedText() == "Filled" }
        compose.onNode(hasText("Other") and hasClickAction()).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitAdvancingClock(stepMs = 50) { compose.focusedText() == "Other" }
        compose.onNode(hasText("Filled") and hasClickAction()).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitAdvancingClock(stepMs = 50) { compose.focusedText() == "Filled" }
        assertEquals(1, made)
    }

    @Test
    fun `a side panel's option with focus is filled with the accent colour`() = assertFilled("Add to favourites") { label, first ->
        BoardOption(label, "Shown first on Home", if (first) rememberFirstFocus() else Modifier) {}
    }
}
