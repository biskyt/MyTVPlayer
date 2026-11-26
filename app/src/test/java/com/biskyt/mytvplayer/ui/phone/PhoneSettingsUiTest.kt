package com.biskyt.mytvplayer.ui.phone

import android.content.ClipboardManager
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.core.sync.DeviceCode
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.Navigators
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.addHomeTv
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.waitAdvancingClock
import com.biskyt.mytvplayer.testutil.waitForText
import com.biskyt.mytvplayer.ui.NavHost
import com.biskyt.mytvplayer.ui.Navigator
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.ScreenContent
import com.biskyt.mytvplayer.ui.common.LinkPanel
import com.biskyt.mytvplayer.ui.settings.SignInCode
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
import com.biskyt.mytvplayer.ui.theme.Palette
import com.biskyt.mytvplayer.ui.theme.TvCanvas
import com.biskyt.mytvplayer.ui.theme.autoFindsTv
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The settings pages on a phone (the owner, 2026-11-25): a back arrow, boxes one under the other, lists for a finger. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "w390dp-h844dp-xxhdpi")
class PhoneSettingsUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val provider = FakeProvider()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var pid = 0L

    @Before
    fun setUp() = runBlocking {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_TOUCHSCREEN, true)
        autoFindsTv = { false } // the phone layout (TestApp sets the TV's)
        pid = app.container.addHomeTv(provider)
    }

    @After
    fun tearDown() = provider.close()

    private fun show(vararg screens: Screen): Navigator {
        val navigator = navigators.new(screens.first())
        screens.drop(1).forEach(navigator::push)
        compose.setContent {
            MyTvTheme {
                Box(Modifier.fillMaxSize().background(Palette.background)) { NavHost(remember { navigator }) { ScreenContent(it) } }
            }
        }
        return navigator
    }

    private fun tap(text: String) = compose.onNode(hasText(text) and hasClickAction()).performClick()

    private fun tapBack() = compose.onNode(hasContentDescription("Back") and hasClickAction()).performClick()

    private fun shows(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `the back arrow on a settings page goes back, and nothing takes focus`() {
        val navigator = show(Screen.Settings, Screen.PlaybackSettings)
        compose.waitForText("Skip back", stepMs = 50)
        // No remote: the first row does not take focus, which would show it in the accent colour as if chosen.
        assertTrue(compose.onAllNodes(isFocused()).fetchSemanticsNodes().isEmpty())
        tapBack()
        compose.waitAdvancingClock(stepMs = 50) { navigator.current.screen == Screen.Settings }
        compose.waitForText("Appearance", stepMs = 50)
    }

    @Test
    fun `a playlist's boxes are one under the other`() {
        show(Screen.PlaylistEdit(pid))
        compose.waitForText("Server address", stepMs = 50)
        val name = compose.onNode(hasText("Name")).fetchSemanticsNode().boundsInRoot
        val server = compose.onNode(hasText("Server address")).fetchSemanticsNode().boundsInRoot
        assertTrue("$name, $server", server.top > name.bottom && server.left == name.left)
    }

    @Test
    fun `a tap on a country opens its categories under it, and another closes them`() {
        show(Screen.Categories(pid))
        // The first country is open to start with.
        compose.waitForText("UK | SPORT", stepMs = 50)
        tap("English (UK)")
        compose.waitAdvancingClock(stepMs = 50) { !shows("UK | SPORT") }
        tap("English (UK)")
        compose.waitForText("UK | SPORT", stepMs = 50)
        tap("UK | SPORT")
        compose.waitForText("1 of 2 categories", stepMs = 50)
    }

    @Test
    fun `a channel's guide choices fill the page, and Back goes back to the channels`() {
        val navigator = show(Screen.Settings, Screen.GuideMatching(pid))
        compose.waitForText("UK: CHANNEL ONE HD", stepMs = 50)
        tap("UK: CHANNEL ONE HD")
        compose.waitForText("Choose a guide for", stepMs = 50)
        assertTrue(!shows("Filter your channels by name"))
        tapBack()
        compose.waitForText("Filter your channels by name", stepMs = 50)
        assertTrue(!shows("Choose a guide for") && navigator.current.screen is Screen.GuideMatching)
    }

    @Test
    fun `a link panel has no QR code to scan with the same phone`() {
        show(Screen.About)
        compose.waitForText("Ko-fi", stepMs = 50)
        compose.onNode(hasText("Ko-fi") and hasClickAction()).performScrollTo().performClick()
        compose.waitForText("Open on this device", stepMs = 50)
        assertTrue(compose.onAllNodes(hasContentDescription("QR code", substring = true)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `a link panel on a page drawn as on a TV has no QR code either`() {
        // A screen with no phone layout is drawn as on a TV (TvCanvas), on the phone.
        compose.setContent { MyTvTheme { TvCanvas { LinkPanel("Ko-fi", "https://ko-fi.com/biskyt") {} } } }
        compose.waitForText("Open on this device", stepMs = 50)
        assertTrue(compose.onAllNodes(hasContentDescription("QR code", substring = true)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `the sign-in code can be copied on a phone`() {
        val code = DeviceCode("dev", "ABC-DEF", "https://www.google.com/device", 1800)
        compose.setContent { MyTvTheme { SignInCode(code) {} } }
        tap("Copy the code")
        compose.waitForText("Code copied", stepMs = 50)
        val clip = app.getSystemService(ClipboardManager::class.java).primaryClip
        assertEquals("ABC-DEF", clip?.getItemAt(0)?.text?.toString())
    }
}
