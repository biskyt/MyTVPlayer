package com.biskyt.mytvplayer.ui.settings

import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import com.biskyt.mytvplayer.testutil.Navigators
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.waitAdvancingClock
import com.biskyt.mytvplayer.testutil.waitForText
import com.biskyt.mytvplayer.ui.NavHost
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.ScreenContent
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The "http://" and "https://" buttons on a playlist's addresses: typing those on a TV remote is slow. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class AddressSchemeUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    @Test
    fun `an address gets the start chosen, in place of the one it had`() {
        assertEquals("http://", withScheme("", "http://"))
        assertEquals("https://example.com:8080", withScheme("example.com:8080", "https://"))
        assertEquals("http://example.com/list.m3u", withScheme(" HTTPS://example.com/list.m3u ", "http://"))
        assertEquals("https://example.com", withScheme("http://example.com", "https://"))
    }

    @Test
    fun `the button puts the start on the server address`() {
        compose.setContent { MyTvTheme { NavHost(remember { navigators.new(Screen.PlaylistEdit(null)) }) { ScreenContent(it) } } }
        fun press(text: String) {
            compose.waitAdvancingClock { compose.onAllNodes(hasText(text) and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodes(hasText(text) and hasClickAction())[0].performSemanticsAction(SemanticsActions.OnClick)
        }
        press("https://")
        compose.waitForText("https://")
        press("http://")
        // The box now holds "http://", and the https:// button is still there: two nodes say "http://" or "https://".
        compose.waitAdvancingClock { compose.onAllNodes(hasText("http://")).fetchSemanticsNodes().size >= 2 }
    }
}
