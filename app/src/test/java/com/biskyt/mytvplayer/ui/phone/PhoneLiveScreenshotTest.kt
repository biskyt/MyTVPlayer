package com.biskyt.mytvplayer.ui.phone

import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.data.ChannelFilter
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.Navigators
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.addHomeTv
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.waitAdvancingClock
import com.biskyt.mytvplayer.testutil.waitForText
import com.biskyt.mytvplayer.ui.NavHost
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.ScreenContent
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
import com.biskyt.mytvplayer.ui.theme.Palette
import com.biskyt.mytvplayer.ui.theme.autoFindsTv
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Live TV on a phone and on an unfolded foldable (the owner, 2026-11-25), recorded to look at. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "w390dp-h844dp-xxhdpi")
class PhoneLiveScreenshotTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val provider = FakeProvider()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()

    @After
    fun tearDown() = provider.close()

    private fun start() {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_TOUCHSCREEN, true)
        autoFindsTv = { false } // the phone layout (TestApp sets the TV\'s)
        runBlocking { app.container.addHomeTv(provider, guide = true) }
        val navigator = navigators.new(Screen.Live(ChannelFilter.All))
        compose.setContent {
            MyTvTheme {
                Box(Modifier.fillMaxSize().background(Palette.background)) { NavHost(remember { navigator }) { ScreenContent(it) } }
            }
        }
        compose.waitForText("UK: CHANNEL ONE HD", stepMs = 50)
        compose.waitAdvancingClock(stepMs = 50) {
            compose.onAllNodes(hasText("Now on", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `the channel list on a phone`() {
        start()
        compose.onRoot().captureRoboImage("screenshots/phone-live.png")
    }

    @Test
    fun `a long press opens the channel's options`() {
        start()
        compose.onNode(hasText("UK: KITE KIDZ") and hasClickAction(), useUnmergedTree = false).performTouchInput { longClick() }
        compose.waitAdvancingClock(stepMs = 50) { compose.onAllNodes(isRoot()).fetchSemanticsNodes().size > 1 }
        compose.onAllNodes(isRoot())[1].captureRoboImage("screenshots/phone-live-options.png")
    }

    @Test
    @Config(qualifiers = "w840dp-h720dp-xhdpi")
    fun `the channel list on an unfolded foldable`() {
        start()
        compose.onRoot().captureRoboImage("screenshots/phone-live-unfolded.png")
    }
}
