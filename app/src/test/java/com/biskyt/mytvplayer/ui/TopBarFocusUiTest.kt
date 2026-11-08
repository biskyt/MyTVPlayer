package com.biskyt.mytvplayer.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import com.biskyt.mytvplayer.testutil.Navigators
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.assertColourAcross
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.drawScreen
import com.biskyt.mytvplayer.ui.common.Tab
import com.biskyt.mytvplayer.ui.common.TopBar
import com.biskyt.mytvplayer.ui.theme.LocalAccent
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A top bar item with focus is filled with the accent colour, with no ring, as the film and series pages' buttons are. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class TopBarFocusUiTest {
    @get:Rule val compose = createUiComposeRule<ComponentActivity>()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    @Test
    fun `the item with focus is filled with the accent colour, to its edge`() {
        val navigator = navigators.new(Screen.Home)
        var accent = Color.Unspecified
        compose.setContent {
            MyTvTheme {
                accent = LocalAccent.current.color
                CompositionLocalProvider(LocalNavigator provides navigator) { TopBar(Tab.HOME) }
            }
        }
        // Not the current tab: its text is not in the accent colour.
        val live = compose.onNode(hasText("Live TV") and hasClickAction())
        live.performSemanticsAction(SemanticsActions.RequestFocus)
        val screen = compose.drawScreen()
        val node = live.fetchSemanticsNode()
        // At its edge (where a ring would be) and in the room before the text: the accent fill.
        assertColourAcross(screen, node, listOf(1, 6), accent)
    }
}
