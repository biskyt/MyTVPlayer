package com.biskyt.mytvplayer.ui.phone

import android.content.pm.PackageManager
import androidx.compose.runtime.remember
import androidx.compose.ui.test.onRoot
import androidx.lifecycle.ViewModelProvider
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
import com.biskyt.mytvplayer.ui.player.PlaybackViewModel
import com.biskyt.mytvplayer.ui.player.PlayerViewModel
import com.biskyt.mytvplayer.ui.player.VodPlayerViewModel
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
import com.biskyt.mytvplayer.ui.theme.autoFindsTv
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The player's touch controls on a phone held sideways (the owner, 2026-11-25): screenshots to look at. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "w844dp-h390dp-xxhdpi")
class PhonePlayerControlsTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val provider = FakeProvider()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var playlistId = 0L

    @Before
    fun setUp() = runBlocking {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_TOUCHSCREEN, true)
        autoFindsTv = { false } // the phone layout (TestApp sets the TV\'s)
        playlistId = app.container.addHomeTv(provider, vod = true)
    }

    @After
    fun tearDown() = provider.close()

    private fun <T : PlaybackViewModel> play(screen: Screen, type: Class<T>): T {
        val navigator = navigators.new(screen)
        compose.setContent { MyTvTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
        val vm = ViewModelProvider(navigator.current)[type]
        compose.waitAdvancingClock { vm.engine?.player?.currentMediaItem != null }
        return vm
    }

    @Test
    fun `an episode's controls`() {
        play(Screen.VodPlayer(playlistId, 9001, seriesId = 77), VodPlayerViewModel::class.java)
        compose.waitForText("Next episode")
        compose.waitForText("Episodes")
        compose.onRoot().captureRoboImage("screenshots/phone-player-episode.png")
    }

    @Test
    fun `a live channel's controls`() {
        play(Screen.Player(playlistId, 101, ChannelFilter.All), PlayerViewModel::class.java)
        compose.waitForText("Channel guide")
        compose.onRoot().captureRoboImage("screenshots/phone-player-live.png")
    }
}
