package com.biskyt.mytvplayer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
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
import com.biskyt.mytvplayer.testutil.settle
import com.biskyt.mytvplayer.testutil.waitAdvancingClock
import com.biskyt.mytvplayer.ui.common.PillButton
import com.biskyt.mytvplayer.ui.common.Tab
import com.biskyt.mytvplayer.ui.common.TopBar
import com.biskyt.mytvplayer.ui.common.rememberFirstFocus
import com.biskyt.mytvplayer.ui.common.restoreFocusIf
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A screen is not left with nothing focused (the owner, 2026-11-06): back from another screen, the item used last has
 * focus again, and when the focused item goes, the first takes focus. (As a screen first opens, it chooses its own.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class KeepsFocusUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private var third by mutableStateOf(true)

    /** The screen's own restore asks for Two (the channel played last, after a zap in the player). */
    private var ownRestore by mutableStateOf(false)

    /** A screen with no focus logic of its own (One, Two and, while [third], Three), and another to open from it. */
    private fun show(): Navigator {
        val navigator = navigators.new(Screen.Settings)
        compose.setContent {
            MyTvTheme {
                NavHost(remember { navigator }) { screen ->
                    if (screen == Screen.Settings) {
                        Column {
                            PillButton("One", {})
                            PillButton("Two", {}, Modifier.restoreFocusIf(ownRestore) { ownRestore = false })
                            if (third) PillButton("Three", {})
                        }
                    } else {
                        PillButton("Other screen", {}, rememberFirstFocus())
                    }
                }
            }
        }
        return navigator
    }

    private fun focusIs(text: String) {
        compose.waitAdvancingClock(stepMs = 20) { compose.focusedText() == text }
        assertEquals(text, compose.focusedText())
    }

    private fun focus(text: String) {
        compose.onNode(hasText(text) and hasClickAction()).performSemanticsAction(SemanticsActions.RequestFocus)
        focusIs(text)
    }

    @Test
    fun `back from another screen, the item used last has focus`() {
        val navigator = show()
        focus("Two")
        compose.runOnUiThread { navigator.push(Screen.About) }
        focusIs("Other screen")
        compose.runOnUiThread { navigator.pop() }
        focusIs("Two")
    }

    @Test
    fun `a screen's own restore is not undone by the item used last`() {
        val navigator = show()
        focus("Three")
        compose.runOnUiThread { navigator.push(Screen.About) }
        focusIs("Other screen")
        ownRestore = true
        compose.runOnUiThread { navigator.pop() }
        focusIs("Two")
        compose.settle()
        assertEquals("Two", compose.focusedText())
    }

    @Test
    fun `back to the top bar, the tab used last has focus`() {
        val navigator = navigators.new(Screen.Home)
        compose.setContent {
            MyTvTheme {
                CompositionLocalProvider(LocalNavigator provides navigator) {
                    NavHost(remember { navigator }) { screen ->
                        if (screen == Screen.Home) TopBar(Tab.HOME) else PillButton("Other screen", {}, rememberFirstFocus())
                    }
                }
            }
        }
        focus("Movies")
        compose.runOnUiThread { navigator.push(Screen.About) }
        focusIs("Other screen")
        compose.runOnUiThread { navigator.pop() }
        focusIs("Movies")
    }

    @Test
    fun `when the focused item goes, the screen still has focus`() {
        show()
        focus("Three")
        third = false
        compose.waitAdvancingClock(stepMs = 20) { compose.focusedText().isNotEmpty() }
        assertEquals("One", compose.focusedText())
    }
}
