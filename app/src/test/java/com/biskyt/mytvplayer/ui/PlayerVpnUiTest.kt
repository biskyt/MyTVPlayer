package com.biskyt.mytvplayer.ui

import android.net.ConnectivityManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.ui.test.hasText
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.data.ChannelFilter
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.Navigators
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.addHomeTv
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.focusedText
import com.biskyt.mytvplayer.testutil.reportNetwork
import com.biskyt.mytvplayer.testutil.settle
import com.biskyt.mytvplayer.testutil.waitAdvancingClock
import com.biskyt.mytvplayer.testutil.waitForText
import com.biskyt.mytvplayer.ui.player.PlayerViewModel
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * With "Warn if VPN is not connected", the player shows whether a VPN is connected next to its clock, as the top bar
 * does (the owner, 2026-11-12); without it, nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class PlayerVpnUiTest {
    @get:Rule val compose = createUiComposeRule<ComponentActivity>()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val provider = FakeProvider().apply { streamsTrickle = true }
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private var playlistId = 0L

    @Before
    fun setUp() = runBlocking<Unit> { playlistId = app.container.addHomeTv(provider) }

    @After
    fun tearDown() = provider.close()

    private lateinit var vm: PlayerViewModel

    /** The player, with its controls up. */
    private fun play() {
        val navigator = navigators.new(Screen.Player(playlistId, 101, ChannelFilter.All))
        compose.setContent { MyTvTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
        vm = ViewModelProvider(navigator.current)[PlayerViewModel::class.java]
        compose.waitAdvancingClock(stepMs = 50) { vm.channel != null && compose.focusedText().let { it == "Pause" || it == "Play" } }
    }

    private fun shown(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `off by default, the player shows no VPN status`() {
        play()
        compose.settle()
        assertFalse(shown("VPN on"))
        assertFalse(shown("VPN not connected"))
    }

    @Test
    fun `on, the player shows whether a VPN is connected as it changes`() {
        runBlocking { app.container.settings.update { it.copy(warnVpn = true) } }
        play()
        compose.waitForText("VPN not connected")
        compose.waitAdvancingClock { shadowOf(connectivity).networkCallbacks.isNotEmpty() }
        connectivity.reportNetwork(vpn = true)
        compose.waitForText("VPN on")
        connectivity.reportNetwork(vpn = null)
        compose.waitForText("VPN not connected")
    }

    @Test
    fun `the status shows only with the controls, as the clock does`() {
        runBlocking { app.container.settings.update { it.copy(warnVpn = true) } }
        play()
        compose.waitForText("VPN not connected")
        compose.runOnIdle { vm.hideOverlay() }
        compose.waitAdvancingClock { !shown("VPN not connected") }
    }
}
