package com.biskyt.mytvplayer.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithText
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.testutil.Navigators
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A screen's saved state comes back with the same run's navigator (after a configuration change), but not to a new
 * run's: after Android has stopped the app and starts it again, the app opens fresh (the owner, 2026-11-11: Home had
 * opened far down its page).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class)
class NavHostRestartUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private lateinit var navigator: Navigator

    /** Adds one to the shown screen's saved count. */
    private var bump: () -> Unit = {}

    /** A screen that saves a count, shown with [navigator]. */
    private fun show(restoration: StateRestorationTester) {
        navigator = navigators.new(Screen.Home)
        restoration.setContent {
            MyTvTheme {
                NavHost(navigator) {
                    var count by rememberSaveable { mutableIntStateOf(0) }
                    bump = { count++ }
                    Text("Count $count")
                }
            }
        }
        compose.runOnIdle { bump() }
        compose.onNodeWithText("Count 1").assertExists()
    }

    @Test
    fun `the same run's navigator gets a screen's saved state back`() {
        val restoration = StateRestorationTester(compose)
        show(restoration)
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Count 1").assertExists()
    }

    @Test
    fun `the old run's saved state is let go, not kept for ever`() {
        val restoration = StateRestorationTester(compose)
        show(restoration)
        val old = navigator
        navigator = navigators.new(Screen.Home) // a new run
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Count 0").assertExists()
        // Were the old run's entry still kept, it would come back with its own navigator (its numbers).
        navigator = old
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Count 0").assertExists()
    }

    @Test
    fun `a new run's navigator does not get the old run's saved state`() {
        val restoration = StateRestorationTester(compose)
        show(restoration)
        navigator = navigators.new(Screen.Home) // what the app makes when Android starts it again
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Count 0").assertExists()
    }
}
