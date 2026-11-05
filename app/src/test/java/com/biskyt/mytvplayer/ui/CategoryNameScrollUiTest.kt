package com.biskyt.mytvplayer.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import com.biskyt.mytvplayer.testutil.Navigators
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.waitAdvancingClock
import com.biskyt.mytvplayer.ui.common.CategoryColumn
import com.biskyt.mytvplayer.ui.common.CategoryEntry
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A long category name (Live TV, Movies, Series and Catch-up all use [CategoryColumn]) is cut short with "…", and
 * scrolls to show the rest while its row has focus.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class CategoryNameScrollUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    /** The name's own text layout, and the width of its row's text area. */
    private fun name(): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNode(hasText(LONG), useUnmergedTree = true).fetchSemanticsNode()
            .config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
        return layouts.single()
    }

    private fun row(): SemanticsNodeInteraction = compose.onNode(hasText(LONG) and hasClickAction())

    @Test
    fun `a long name is cut short, and laid out in full to scroll while its row has focus`() {
        compose.setContent {
            MyTvTheme {
                CategoryColumn(
                    entries = listOf(CategoryEntry("short", "News"), CategoryEntry("long", LONG)),
                    // Selected: coming into the column, focus goes to the selected entry.
                    selected = "long",
                    onFocus = {},
                    onSelect = {}
                )
            }
        }
        compose.waitAdvancingClock(0) { runCatching { name() }.isSuccess }
        assertTrue("cut short with … without focus", name().hasVisualOverflow)

        row().performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitAdvancingClock(0) { !name().hasVisualOverflow }
        // Laid out at its full width, wider than the column, for the scroll to show the rest.
        val rowWidth = row().fetchSemanticsNode().size.width
        assertFalse(name().hasVisualOverflow)
        assertTrue("${name().size.width} px wide, in a row of $rowWidth px", name().size.width > rowWidth)
    }

    private companion object {
        const val LONG = "UK | ENTERTAINMENT AND DOCUMENTARIES FROM AROUND THE WORLD"
    }
}
