package com.biskyt.mytvplayer.ui

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.Navigators
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.addHomeTv
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.settle
import com.biskyt.mytvplayer.testutil.waitAdvancingClock
import com.biskyt.mytvplayer.testutil.waitForText
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities

/** "Warn if VPN is not connected": the top bar says whether Android reports a VPN, as it changes; nothing when off. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class VpnBadgeUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val provider = FakeProvider()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)

    @Before
    fun setUp() = runBlocking<Unit> {
        app.container.addHomeTv(provider)
    }

    @After
    fun tearDown() = provider.close()

    private fun show() {
        val navigator = navigators.new(Screen.Live())
        compose.setContent { MyTvTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
        compose.waitForText("Live TV")
    }

    private fun shown(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    /** What Android tells the app as the default network changes: [vpn], or the network gone. */
    private fun network(vpn: Boolean?) {
        val network = ShadowNetwork.newInstance(1)
        shadowOf(connectivity).networkCallbacks.forEach { callback ->
            if (vpn == null) {
                callback.onLost(network)
            } else {
                val caps = ShadowNetworkCapabilities.newInstance()
                if (vpn) shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                callback.onCapabilitiesChanged(network, caps)
            }
        }
    }

    @Test
    fun `off by default, with nothing in the top bar`() {
        show()
        compose.settle()
        assertFalse(shown("VPN on"))
        assertFalse(shown("VPN not connected"))
        assertTrue("nothing listens to the network", shadowOf(connectivity).networkCallbacks.isEmpty())
    }

    @Test
    fun `on, the top bar says whether a VPN is connected as it changes`() {
        runBlocking { app.container.settings.update { it.copy(warnVpn = true) } }
        show()
        compose.waitForText("VPN not connected")
        compose.waitAdvancingClock { shadowOf(connectivity).networkCallbacks.isNotEmpty() }
        network(vpn = true)
        compose.waitForText("VPN on")
        network(vpn = null)
        compose.waitForText("VPN not connected")
    }

    @Test
    fun `the setting is in the main settings list`() {
        val navigator = navigators.new(Screen.Settings)
        compose.setContent { MyTvTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
        compose.waitForText("Playlists")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Warn if VPN is not connected"))
        compose.onNode(hasText("Warn if VPN is not connected") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitAdvancingClock { runBlocking { app.container.settings.current().warnVpn } }
        compose.waitForText("VPN not connected")
    }
}
