package com.biskyt.mytvplayer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.core.net.ProxyCheck
import com.biskyt.mytvplayer.core.net.PublicIp
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.Navigators
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.addHomeTv
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.settle
import com.biskyt.mytvplayer.testutil.waitAdvancingClock
import com.biskyt.mytvplayer.testutil.waitForText
import com.biskyt.mytvplayer.ui.common.ProxyStatus
import com.biskyt.mytvplayer.ui.common.StatusLook
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * "Warn if the proxy is not working" (the owner, 2026-11-13): a setting in the main list, off by default; the status
 * says "Proxy on" with the address seen through the proxy, or warns.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class ProxyStatusUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val provider = FakeProvider()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()

    @Before
    fun setUp() = runBlocking<Unit> {
        app.container.addHomeTv(provider)
    }

    @After
    fun tearDown() = provider.close()

    private fun count(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().size

    @Test
    fun `the setting is in the main settings list, off by default, and turns on`() {
        val navigator = navigators.new(Screen.Settings)
        compose.setContent { MyTvTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
        compose.waitForText("Playlists")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Warn if the proxy is not working"))
        assertFalse(runBlocking { app.container.settings.current().warnProxy })
        compose.onNode(hasText("Warn if the proxy is not working") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitAdvancingClock { runBlocking { app.container.settings.current().warnProxy } }
    }

    @Test
    fun `on, with no proxy on the active playlist, the top bar shows no proxy status`() {
        runBlocking { app.container.settings.update { it.copy(warnProxy = true) } }
        val navigator = navigators.new(Screen.Live())
        compose.setContent { MyTvTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
        compose.waitForText("Live TV")
        compose.settle()
        assertEquals(0, count("Proxy on"))
        assertEquals(0, count("Proxy not working"))
    }

    @Test
    fun `the status shows the address seen through a working proxy, and warns when it is the same as without it`() {
        val home = PublicIp("1.1.1.1", "GB")
        val away = PublicIp("185.12.34.56", "NL")
        compose.setContent {
            MyTvTheme {
                Column {
                    ProxyStatus(ProxyCheck.Working(away, home), StatusLook.Bar)
                    ProxyStatus(ProxyCheck.SameAddress(home), StatusLook.Player)
                    ProxyStatus(ProxyCheck.Failed("Connection refused"), StatusLook.Bar)
                }
            }
        }
        compose.waitForText("Proxy on")
        compose.waitForText("185.12.34.56 · NL")
        compose.waitForText("1.1.1.1 · GB")
        compose.waitAdvancingClock { count("Proxy not working") == 2 }
    }
}
