package com.biskyt.mytvplayer.ui.phone

import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.data.ChannelFilter
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.LocalMidday
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
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
import com.biskyt.mytvplayer.ui.theme.Palette
import com.biskyt.mytvplayer.ui.theme.autoFindsTv
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The TV guide on a phone (the owner, 2026-11-26: "All screens should have a phone layout"). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "w390dp-h844dp-xxhdpi")
class PhoneGuideUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    @get:Rule val midday = LocalMidday() // the programmes on now and next stay on the same day

    private val provider = FakeProvider()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var pid = 0L
    private lateinit var navigator: Navigator

    @Before
    fun setUp() = runBlocking {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_TOUCHSCREEN, true)
        autoFindsTv = { false } // the phone layout (TestApp sets the TV's)
        pid = app.container.addHomeTv(provider, guide = true)
    }

    @After
    fun tearDown() = provider.close()

    private fun show(screen: Screen) {
        navigator = navigators.new(screen)
        compose.setContent {
            MyTvTheme {
                Box(Modifier.fillMaxSize().background(Palette.background)) { NavHost(remember { navigator }) { ScreenContent(it) } }
            }
        }
    }

    /** The channel list, then the guide opened from its header's button, with Channel One's programme on now shown. */
    private fun openGuide() {
        show(Screen.Live(ChannelFilter.All))
        compose.waitForText("UK: CHANNEL ONE HD", stepMs = 50)
        compose.onNode(hasText("TV guide") and hasClickAction()).performClick()
        compose.waitAdvancingClock(stepMs = 50) { navigator.current.screen == Screen.Live(ChannelFilter.All, guide = true) }
        compose.waitForText("Now on one.uk", stepMs = 50)
    }

    @Test
    fun `the guide on a phone is drawn for touch, with a back arrow and the category chips`() {
        openGuide()
        // The TV's guide (drawn as on a TV) has neither.
        compose.onNode(hasContentDescription("Back")).assertExists()
        compose.onNode(hasText("All channels") and hasClickAction()).assertExists()
        // Each programme is a block of its own, with its times.
        compose.onNode(hasText("Next on one.uk") and hasClickAction()).assertExists()
    }

    @Test
    fun `a tap on a programme on now plays its channel`() {
        openGuide()
        compose.onNode(hasText("Now on one.uk") and hasClickAction()).performClick()
        compose.waitAdvancingClock(stepMs = 50) { navigator.current.screen is Screen.Player }
        assertEquals(101L, (navigator.current.screen as Screen.Player).streamId)
    }

    @Test
    fun `the back arrow returns to the channel list`() {
        openGuide()
        compose.onNode(hasContentDescription("Back")).performClick()
        compose.waitAdvancingClock(stepMs = 50) { navigator.current.screen == Screen.Live(ChannelFilter.All) }
        assertEquals(1, navigator.stack.size)
    }

    @Test
    fun `a long press opens the programme's options, and Switch to channel list returns to the list`() {
        openGuide()
        compose.onNode(hasText("Now on one.uk") and hasClickAction()).performTouchInput { longClick() }
        compose.waitForText("Switch to channel list", stepMs = 50)
        // The last option: the sheet scrolls to it first.
        compose.onNode(hasText("Switch to channel list") and hasClickAction()).performScrollTo().performClick()
        compose.waitAdvancingClock(stepMs = 50) { navigator.current.screen == Screen.Live(ChannelFilter.All) }
        assertEquals(1, navigator.stack.size)
    }
}
