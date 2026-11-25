package com.biskyt.mytvplayer.ui.settings

import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.data.LayoutMode
import com.biskyt.mytvplayer.testutil.Navigators
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.waitAdvancingClock
import com.biskyt.mytvplayer.testutil.waitForText
import com.biskyt.mytvplayer.ui.NavHost
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.ScreenContent
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
import com.biskyt.mytvplayer.ui.theme.autoFindsTv
import kotlinx.coroutines.runBlocking
import org.junit.After
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

    private val app = ApplicationProvider.getApplicationContext<TestApp>()

    @After
    fun tearDown() {
        autoFindsTv = { true } // as TestApp sets it
    }

    private fun stepLayout(to: LayoutMode, shown: String) {
        compose.onNode(hasText("Layout") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitAdvancingClock { runBlocking { app.container.settings.current().layout } == to }
        compose.waitForText(shown)
    }

    private fun openLayout() {
        val navigator = navigators.new(Screen.MiscSettings)
        compose.setContent { MyTvTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
        compose.waitForText("Layout")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Layout"))
        compose.waitForText("Automatic")
    }

    @Test
    fun `on a touch screen, Layout steps through Automatic, TV, and Phone and tablet, and saves the choice`() {
        autoFindsTv = { false }
        openLayout()
        compose.waitForText("TV for a remote, phone and tablet for a touch screen")
        stepLayout(LayoutMode.TV, "TV")
        stepLayout(LayoutMode.TOUCH, "Phone and tablet")
        stepLayout(LayoutMode.AUTO, "Automatic")
    }

    @Test
    fun `on a TV, Layout offers no touch layout, which a remote could not use to set it back`() {
        openLayout()
        compose.waitForText("A TV has the TV layout only: a remote cannot use the touch layout")
        stepLayout(LayoutMode.TV, "TV")
        stepLayout(LayoutMode.AUTO, "Automatic")
    }

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
