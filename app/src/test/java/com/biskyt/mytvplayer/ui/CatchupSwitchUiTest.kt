@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.biskyt.mytvplayer.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.core.player.RemoteKey
import com.biskyt.mytvplayer.data.ChannelFilter
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.Navigators
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.addHomeTv
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.topRoot
import com.biskyt.mytvplayer.testutil.waitAdvancingClock
import com.biskyt.mytvplayer.testutil.waitForText
import com.biskyt.mytvplayer.ui.player.PlayerViewModel
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Skipping back on live TV: a channel with catch-up switches to it, from that moment (the owner, 2026-11-22). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class CatchupSwitchUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val provider = FakeProvider()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var playlistId = 0L
    private lateinit var navigator: Navigator

    @Before
    fun setUp() = runBlocking {
        playlistId = app.container.addHomeTv(provider, guide = true)
    }

    @After
    fun tearDown() = provider.close()

    private fun play(streamId: Long) {
        navigator = navigators.new(Screen.Player(playlistId, streamId, ChannelFilter.All))
        compose.setContent { MyTvTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
        compose.waitForText("Channel guide")
        // The player takes keys once it has read the skip settings, which can be just after the controls show.
        val vm = ViewModelProvider(navigator.current)[PlayerViewModel::class.java]
        compose.waitAdvancingClock { vm.actsOn(RemoteKey.REWIND) }
    }

    private fun press(key: Key) {
        compose.topRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    @Test
    fun `back on a channel with catch-up switches to catch-up, from a little earlier`() {
        play(101) // with 3 days of catch-up
        val pressed = System.currentTimeMillis()
        press(Key.MediaRewind)
        compose.waitAdvancingClock { navigator.current.screen is Screen.CatchupPlayer }
        val screen = navigator.current.screen as Screen.CatchupPlayer
        assertEquals(101L, screen.streamId)
        assertTrue(screen.switched)
        assertTrue(screen.startMs!! < pressed)
        // It says why the picture pauses.
        compose.waitForText("Switching to catch-up…")
    }

    @Test
    fun `back on a channel without catch-up still says it can't skip`() {
        play(102)
        press(Key.MediaRewind)
        compose.waitForText("Skipping isn't available here")
        assertTrue(navigator.current.screen is Screen.Player)
    }
}
