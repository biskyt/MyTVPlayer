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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A playlist's proxy on its edit page: none at first; its boxes and Check proxy once a type is chosen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class ProxyFieldsUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private fun shown(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun press(text: String) {
        compose.waitAdvancingClock { compose.onAllNodes(hasText(text) and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText(text) and hasClickAction())[0].performSemanticsAction(SemanticsActions.OnClick)
    }

    @Test
    fun `choosing a proxy type shows its boxes, and an empty one is not checked`() {
        compose.setContent { MyTvTheme { NavHost(remember { navigators.new(Screen.PlaylistEdit(null)) }) { ScreenContent(it) } } }
        compose.waitForText("Proxy")
        assertTrue(!shown("Proxy address") && !shown("Check proxy"))
        press("SOCKS5")
        compose.waitForText("Proxy address")
        compose.waitForText("Proxy user name (optional)")
        press("Check proxy")
        compose.waitForText("Enter the proxy's address, and a port from 1 to 65535.")
        press("None")
        compose.waitAdvancingClock { !shown("Proxy address") }
    }
}
