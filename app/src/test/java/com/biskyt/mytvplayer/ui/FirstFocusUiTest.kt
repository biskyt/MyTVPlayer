package com.biskyt.mytvplayer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import com.biskyt.mytvplayer.testutil.Navigators
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.focusedText
import com.biskyt.mytvplayer.testutil.idleFrames
import com.biskyt.mytvplayer.testutil.waitAdvancingClock
import com.biskyt.mytvplayer.ui.common.PillButton
import com.biskyt.mytvplayer.ui.common.SettingRow
import com.biskyt.mytvplayer.ui.common.rememberFirstFocus
import com.biskyt.mytvplayer.ui.settings.SettingsPage
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * rememberFirstFocus takes focus each time its element is shown, within a visit too: content swapped back (a panel's
 * question answered "No", Backup's Restore cancelled, a channel's guide closed) and the same question or error shown a
 * second time. Only its once mode, for a list's first item, holds back within a visit (see GuideSourcesFocusUiTest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class FirstFocusUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private var swapped by mutableStateOf(false)
    private var shown by mutableStateOf(true)

    private fun focusIs(text: String) {
        compose.waitAdvancingClock(stepMs = 50) { compose.focusedText() == text }
        assertEquals(text, compose.focusedText())
    }

    @Test
    fun `content swapped back takes focus again`() {
        val navigator = navigators.new(Screen.Settings)
        compose.setContent {
            MyTvTheme {
                NavHost(remember { navigator }) {
                    if (swapped) {
                        PillButton("Question", {}, rememberFirstFocus())
                    } else {
                        PillButton("Actions", {}, rememberFirstFocus())
                    }
                }
            }
        }
        focusIs("Actions")
        swapped = true
        focusIs("Question")
        swapped = false // "No"
        focusIs("Actions")
    }

    @Test
    fun `the same key shown a second time takes focus again`() {
        val navigator = navigators.new(Screen.Settings)
        compose.setContent {
            MyTvTheme {
                NavHost(remember { navigator }) {
                    Column {
                        PillButton("Play", {}, rememberFirstFocus())
                        if (shown) PillButton("Retry", {}, rememberFirstFocus(ERROR))
                    }
                }
            }
        }
        focusIs("Retry")
        shown = false
        compose.idleFrames()
        shown = true // the same error again
        focusIs("Retry")
    }

    /** A settings page of rows A, B and (while [shown]) C, and another screen to open from it. */
    private fun settingsPages(): Navigator {
        val navigator = navigators.new(Screen.Settings)
        compose.setContent {
            MyTvTheme {
                NavHost(remember { navigator }) { screen ->
                    if (screen == Screen.Settings) {
                        SettingsPage("Page") {
                            item { SettingRow("Row A", null, {}, rememberFirstFocus(once = true)) }
                            item { SettingRow("Row B", null, { navigator.push(Screen.About) }) }
                            if (shown) item { SettingRow("Row C", null, { navigator.push(Screen.About) }) }
                            item { if (swapped) PillButton("Confirm", {}, rememberFirstFocus()) else PillButton("Restore", {}, Modifier) }
                        }
                    } else {
                        PillButton("Other screen", {}, rememberFirstFocus())
                    }
                }
            }
        }
        focusIs("Row A")
        return navigator
    }

    private fun focusRow(row: String) {
        compose.onNode(hasText(row) and hasClickAction()).performSemanticsAction(SemanticsActions.RequestFocus)
        focusIs(row)
    }

    @Test
    fun `a page whose last row is gone gives the first row focus`() {
        val navigator = settingsPages()
        focusRow("Row C")
        compose.runOnUiThread { navigator.push(Screen.About) }
        focusIs("Other screen")
        shown = false // the row is no longer on the page
        compose.runOnUiThread { navigator.pop() }
        focusIs("Row A")
    }

    @Test
    fun `after a return, content shown later in the visit still takes focus`() {
        val navigator = settingsPages()
        focusRow("Row B")
        compose.runOnUiThread { navigator.push(Screen.About) }
        focusIs("Other screen")
        compose.runOnUiThread { navigator.pop() }
        focusIs("Row B")
        swapped = true // Restore pressed: its question appears
        focusIs("Confirm")
    }

    private companion object {
        const val ERROR = "Network error"
    }
}
