package com.biskyt.mytvplayer.ui

import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.core.player.OverlayState
import com.biskyt.mytvplayer.data.ChannelFilter
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.Navigators
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.addHomeTv
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.sendKey
import com.biskyt.mytvplayer.testutil.waitAdvancingClock
import com.biskyt.mytvplayer.ui.player.PlaybackViewModel
import com.biskyt.mytvplayer.ui.player.PlayerViewModel
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A held Guide opens the channel guide; the rest of that press, which lands in the guide's window, isn't the TV's. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class HeldGuideUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val provider = FakeProvider()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var playlistId = 0L

    @Before
    fun setUp() = runBlocking {
        playlistId = app.container.addHomeTv(provider, guide = true)
    }

    @After
    fun tearDown() = provider.close()

    private fun key(code: Int, action: Int, repeat: Int = 0) = compose.sendKey(code, action, repeat)

    private fun guide(action: Int, repeat: Int = 0) = key(NativeKeyEvent.KEYCODE_GUIDE, action, repeat)

    private lateinit var vm: PlayerViewModel

    private fun player() {
        val navigator = navigators.new(Screen.Player(playlistId, 101, ChannelFilter.All))
        compose.setContent { MyTvTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
        vm = ViewModelProvider(navigator.current)[PlayerViewModel::class.java]
        compose.waitAdvancingClock { vm.channel != null && vm.engine?.player?.currentMediaItem != null }
    }

    @Test
    fun `the rest of the guide press that opened the guide stays in the app`() {
        player()
        assertTrue("pressed", guide(NativeKeyEvent.ACTION_DOWN))
        compose.waitAdvancingClock { vm.overlay == OverlayState.GUIDE }
        assertTrue("held", guide(NativeKeyEvent.ACTION_DOWN, repeat = 1))
        assertTrue("let go", guide(NativeKeyEvent.ACTION_UP))
        assertEquals("still open", OverlayState.GUIDE, vm.overlay)
    }

    @Test
    fun `the rest of the menu press that brought up the controls over the guide stays in the app`() {
        player()
        guide(NativeKeyEvent.ACTION_DOWN)
        guide(NativeKeyEvent.ACTION_UP)
        compose.waitAdvancingClock { vm.overlay == OverlayState.GUIDE }
        assertTrue("pressed", key(NativeKeyEvent.KEYCODE_MENU, NativeKeyEvent.ACTION_DOWN))
        compose.waitAdvancingClock { vm.overlay == OverlayState.CONTROLS }
        assertTrue("held", key(NativeKeyEvent.KEYCODE_MENU, NativeKeyEvent.ACTION_DOWN, repeat = 1))
        assertTrue("let go", key(NativeKeyEvent.KEYCODE_MENU, NativeKeyEvent.ACTION_UP))
        assertEquals("still up", OverlayState.CONTROLS, vm.overlay)
        assertEquals("on More", PlaybackViewModel.ControlsFocus.MORE, vm.controlsFocus)
        assertNull("no panel", vm.sheet)
    }
}
