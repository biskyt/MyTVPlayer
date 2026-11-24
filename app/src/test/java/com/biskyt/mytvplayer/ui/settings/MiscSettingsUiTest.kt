package com.biskyt.mytvplayer.ui.settings

import androidx.compose.runtime.remember
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import com.biskyt.mytvplayer.testutil.Navigators
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.waitForText
import com.biskyt.mytvplayer.ui.NavHost
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.ScreenContent
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Radio stations and Downloads are in Misc settings, not in Playback (the owner, 2026-11-24). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class MiscSettingsUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    @Test
    fun `misc settings hold radio stations, downloads and the network warnings`() {
        val navigator = navigators.new(Screen.MiscSettings)
        compose.setContent { MyTvTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
        compose.waitForText("Radio stations")
        listOf("Downloads", "Warn if VPN is not connected", "Warn if the proxy is not working").forEach {
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(it))
        }
    }

    @Test
    fun `playback settings no longer hold radio stations or downloads`() {
        val navigator = navigators.new(Screen.PlaybackSettings)
        compose.setContent { MyTvTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
        compose.waitForText("Skip back")
        // Scrolling looks through the whole list.
        listOf("Radio stations", "Downloads").forEach { text ->
            assertThrows(AssertionError::class.java) { compose.onNode(hasScrollAction()).performScrollToNode(hasText(text)) }
        }
    }
}
