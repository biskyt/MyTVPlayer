package com.biskyt.mytvplayer.ui

import android.content.pm.PackageManager
import androidx.compose.runtime.remember
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.data.ChannelFilter
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.LocalMidday
import com.biskyt.mytvplayer.testutil.Navigators
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.addHomeTv
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.waitAdvancingClock
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
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

/** A phone or tablet: taps and long presses instead of the remote. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class TouchUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    @get:Rule val midday = LocalMidday() // at midnight the guide's header shows the day's name, not the time

    private val provider = FakeProvider()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var playlistId = 0L
    private lateinit var navigator: Navigator

    @Before
    fun setUp() = runBlocking {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_TOUCHSCREEN, true)
        playlistId = app.container.addHomeTv(provider)
    }

    @After
    fun tearDown() = provider.close()

    private fun show(start: Screen) {
        navigator = navigators.new(start)
        compose.setContent { MyTvTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
    }

    private fun waitFor(matcher: androidx.compose.ui.test.SemanticsMatcher, what: String) = try {
        compose.waitAdvancingClock(100) { compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
        throw AssertionError("$what not shown. Screen:\n" + compose.onRoot(useUnmergedTree = true).printToString(), e)
    }

    private fun gone(matcher: androidx.compose.ui.test.SemanticsMatcher) =
        compose.waitAdvancingClock(100) { compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }

    @Test
    fun `a tap presses a button and a long press opens a channel's options`() {
        show(Screen.Welcome)
        waitFor(hasText("Add my TV provider"), "Welcome")
        compose.onNode(hasText("Add my TV provider")).performTouchInput { click() }
        compose.waitAdvancingClock { navigator.current.screen != Screen.Welcome }
        assertEquals(Screen.PlaylistEdit(null, firstRun = true), navigator.current.screen)

        compose.runOnUiThread { navigator.reset(Screen.Live()) }
        waitFor(hasText("UK: CHANNEL ONE HD"), "the channel list")
        compose.onNode(hasText("UK: CHANNEL ONE HD")).performTouchInput { longClick() }
        waitFor(hasText("Add to favourites"), "the channel's options")
    }

    @Test
    fun `on the player a tap shows or hides the controls, and Back at the top goes back to the list`() {
        show(Screen.Live())
        compose.runOnUiThread { navigator.push(Screen.Player(playlistId, 101, ChannelFilter.All)) }
        // The controls are up as playback starts, with Back in the corner on a touch screen.
        waitFor(hasContentDescription("Back"), "Back")
        // The channel's own controls come once it's been looked up.
        waitFor(hasContentDescription("Skip back"), "the controls")
        compose.onRoot().performTouchInput { click(center) }
        gone(hasContentDescription("Skip back"))
        compose.onRoot().performTouchInput { click(center) }
        waitFor(hasContentDescription("Skip back"), "the controls")

        compose.onNode(hasContentDescription("Back")).performTouchInput { click() }
        compose.waitAdvancingClock { navigator.current.screen !is Screen.Player }
        assertEquals(Screen.Live(), navigator.current.screen)
    }

    @Test
    fun `the TV guide has buttons to move later and earlier`() {
        show(Screen.Live(guide = true))
        waitFor(hasText("Later ▶"), "Later")
        // The header starts at the current half hour; moving later leaves its first half hours behind, and earlier
        // brings them back. Checked on its second time: the first is also the clock's for a minute (e.g. 22:00).
        val halfHour = 30 * 60_000L
        val second = com.biskyt.mytvplayer.ui.common.formatTime(System.currentTimeMillis() / halfHour * halfHour + halfHour)
        waitFor(hasText(second), "the time $second")
        val before = navigator.current.screen
        compose.onNode(hasText("Later ▶")).performTouchInput { click() }
        gone(hasText(second))
        compose.onNode(hasText("◀ Earlier")).performTouchInput { click() }
        waitFor(hasText(second), "the time $second again")
        assertEquals(before, navigator.current.screen)
    }
}
