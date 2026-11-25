package com.biskyt.mytvplayer.ui.phone

import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Touch on the phone layouts (the owner, 2026-11-25): what a long press does, and rows that share a channel. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "w390dp-h844dp-xxhdpi")
class PhoneTouchUiTest {
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

    private fun show(screen: Screen) {
        val navigator = navigators.new(screen)
        compose.setContent {
            MyTvTheme {
                Box(Modifier.fillMaxSize().background(Palette.background)) { NavHost(remember { navigator }) { ScreenContent(it) } }
            }
        }
    }

    @Test
    fun `a favourite channel that was also watched lately shows in both rows of Home`() {
        runBlocking {
            app.container.channels.setFavourite(pid, 101, true)
            app.container.channels.addRecent(pid, 101, System.currentTimeMillis())
        }
        show(Screen.Home)
        compose.waitForText("Recently watched", stepMs = 50)
        compose.waitAdvancingClock(stepMs = 50) {
            compose.onAllNodes(hasText("UK: CHANNEL ONE HD")).fetchSemanticsNodes().size == 2
        }
    }

    @Test
    fun `hiding a category that is not shown leaves the list shown as it is`() {
        show(Screen.Live(ChannelFilter.All))
        compose.waitForText("UK: CHANNEL ONE HD", stepMs = 50)
        // The chips scroll sideways: the row is brought to the category's chip first.
        val sport = hasText("SPORT", substring = true, ignoreCase = true) and hasClickAction()
        compose.onNode(hasScrollAction() and hasAnyDescendant(hasText("All channels"))).performScrollToNode(sport)
        compose.onNode(sport).performTouchInput { longClick() }
        compose.waitForText("Hide category", stepMs = 50)
        compose.onNode(hasText("Hide category") and hasClickAction()).performClick()
        compose.waitAdvancingClock(stepMs = 50) {
            compose.onAllNodes(hasText("SPORT", substring = true, ignoreCase = true)).fetchSemanticsNodes().isEmpty()
        }
        assertTrue(compose.onAllNodes(hasText("All channels") and isSelected()).fetchSemanticsNodes().isNotEmpty())
    }
}
