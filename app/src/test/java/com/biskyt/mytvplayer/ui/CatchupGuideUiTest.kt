package com.biskyt.mytvplayer.ui

import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.core.player.OverlayState
import com.biskyt.mytvplayer.core.player.RemoteKey
import com.biskyt.mytvplayer.data.ChannelFilter
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.Navigators
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.addHomeTv
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.waitAdvancingClock
import com.biskyt.mytvplayer.testutil.waitForText
import com.biskyt.mytvplayer.ui.player.CatchupPlayerViewModel
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Catch-up is live TV, only earlier (the owner, 2026-11-23): it has the channel guide, and the channel buttons go to the
 * next channel, live.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class CatchupGuideUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    // A stream that loads without end (no error panel over the player to take the keys).
    private val provider = FakeProvider().apply { streamsTrickle = true }
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var playlistId = 0L
    private lateinit var navigator: Navigator
    private lateinit var vm: CatchupPlayerViewModel

    @Before
    fun setUp() = runBlocking {
        playlistId = app.container.addHomeTv(provider, guide = true)
    }

    @After
    fun tearDown() = provider.close()

    private fun play() {
        navigator = navigators.new(Screen.CatchupPlayer(playlistId, 101, filter = ChannelFilter.All))
        compose.setContent { MyTvTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
        compose.waitForText("Channel guide")
        vm = ViewModelProvider(navigator.current)[CatchupPlayerViewModel::class.java]
        compose.waitAdvancingClock { vm.guide.ids.isNotEmpty() }
    }

    @Test
    fun `the Guide button opens the channel guide on this channel`() {
        play()
        compose.runOnUiThread { vm.onKey(RemoteKey.GUIDE) }
        compose.waitAdvancingClock { vm.overlay == OverlayState.GUIDE }
        assertEquals(101L, vm.guide.channel?.streamId)
        compose.waitForText("CHANNEL ONE")
    }

    @Test
    fun `the channel buttons go to the next channel, live`() {
        play()
        compose.runOnUiThread { vm.onKey(RemoteKey.CHANNEL_UP) }
        compose.waitAdvancingClock { navigator.current.screen is Screen.Player }
        assertNotEquals(101L, (navigator.current.screen as Screen.Player).streamId)
    }
}
