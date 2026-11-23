package com.biskyt.mytvplayer.ui

import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
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
import com.biskyt.mytvplayer.ui.player.PlaybackViewModel
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

/**
 * Skipping back on live TV: a channel with catch-up moves along the play bar, then switches to catch-up from there once
 * the button rests, with focus on the bar (the owner, 2026-11-22 and 2026-11-23).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class CatchupSwitchUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    // A stream that loads without end (no error panel over the player to take the keys).
    private val provider = FakeProvider().apply { streamsTrickle = true }
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
        vm = ViewModelProvider(navigator.current)[PlayerViewModel::class.java]
        compose.waitAdvancingClock { vm.actsOn(RemoteKey.REWIND) }
    }

    private lateinit var vm: PlayerViewModel

    /** The remote's button, to the player (which windows have focus is not what is tested here); [repeat] while held. */
    private fun press(key: RemoteKey, repeat: Int = 0) {
        compose.runOnUiThread { vm.onKey(key, repeat = repeat) }
        compose.waitForIdle()
    }

    @Test
    fun `back on a channel with catch-up moves along the bar, then switches to catch-up once the button rests`() {
        play(101) // with 3 days of catch-up
        val pressed = System.currentTimeMillis()
        press(RemoteKey.REWIND)
        // Not yet: the button may be pressed again, or held.
        assertTrue(navigator.current.screen is Screen.Player)
        assertTrue(vm.scrubMs!! < pressed)
        compose.waitAdvancingClock(stepMs = 100) { navigator.current.screen is Screen.CatchupPlayer }
        val screen = navigator.current.screen as Screen.CatchupPlayer
        assertEquals(101L, screen.streamId)
        assertTrue(screen.switched)
        // Far enough back for catch-up to have it, so that it does not go straight back to live.
        assertTrue(screen.startMs!! <= pressed - CatchupPlayerViewModel.LIVE_MARGIN_MS)
        // It says why the picture pauses, and focus is on the play bar, where ◀ ▶ go on skipping.
        compose.waitForText("Switching to catch-up…")
        val catchup = ViewModelProvider(navigator.current)[CatchupPlayerViewModel::class.java]
        assertEquals(PlaybackViewModel.ControlsFocus.VIDEO, catchup.controlsFocus)
    }

    @Test
    fun `held, it goes back further, and switches once, to where it was let go`() {
        play(101)
        repeat(30) { press(RemoteKey.LEFT, repeat = it) }
        val target = vm.scrubMs!!
        assertTrue(target < System.currentTimeMillis() - 10 * 60_000L)
        compose.waitAdvancingClock(stepMs = 100) { navigator.current.screen is Screen.CatchupPlayer }
        assertEquals(target, (navigator.current.screen as Screen.CatchupPlayer).startMs)
    }

    @Test
    fun `forward at the present says it can't skip, and stays live`() {
        play(101)
        press(RemoteKey.FAST_FORWARD)
        compose.waitForText("Skipping isn't available here")
        assertEquals(null, vm.scrubMs)
        assertTrue(navigator.current.screen is Screen.Player)
    }

    @Test
    fun `back on a channel without catch-up still says it can't skip`() {
        play(102)
        press(RemoteKey.REWIND)
        compose.waitForText("Skipping isn't available here")
        assertTrue(navigator.current.screen is Screen.Player)
    }
}
