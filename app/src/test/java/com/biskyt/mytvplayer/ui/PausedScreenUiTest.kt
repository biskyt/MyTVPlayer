package com.biskyt.mytvplayer.ui

import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performKeyPress
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.core.player.OverlayState
import com.biskyt.mytvplayer.data.ChannelFilter
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.Navigators
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.addHomeTv
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.idleFrames
import com.biskyt.mytvplayer.testutil.topRoot
import com.biskyt.mytvplayer.testutil.waitAdvancingClock
import com.biskyt.mytvplayer.testutil.waitForText
import com.biskyt.mytvplayer.ui.player.PlaybackViewModel
import com.biskyt.mytvplayer.ui.player.PlayerViewModel
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Paused, the controls stay up; left alone two minutes, the screen dims (against burn-in), and any button lights it. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class PausedScreenUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val provider = FakeProvider()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var playlistId = 0L
    private lateinit var vm: PlayerViewModel

    @Before
    fun setUp() = runBlocking {
        provider.streamsTrickle = true // never fails, so no retry plays it again
        playlistId = app.container.addHomeTv(provider)
    }

    @After
    fun tearDown() = provider.close()

    private val playWhenReady get(): Boolean = compose.runOnIdle { vm.engine!!.player.playWhenReady }

    /** The live player, paused. */
    private fun paused() {
        val navigator = navigators.new(Screen.Player(playlistId, 101, ChannelFilter.All))
        compose.setContent { MyTvTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
        vm = ViewModelProvider(navigator.current)[PlayerViewModel::class.java]
        compose.waitAdvancingClock { vm.channel != null && vm.engine?.player?.currentMediaItem != null }
        compose.runOnIdle { vm.engine!!.player.pause() }
        compose.idleFrames()
    }

    /** Lets [ms] of (simulated) time pass. */
    private fun pass(ms: Long) = repeat((ms / 1_000).toInt()) { compose.waitAdvancingClock(1_000) { true } }

    /** One key event, down or up. */
    private fun key(action: Int, code: Int) {
        compose.topRoot().performKeyPress(KeyEvent(NativeKeyEvent(action, code)))
        compose.idleFrames()
    }

    private fun press(code: Int) {
        key(NativeKeyEvent.ACTION_DOWN, code)
        key(NativeKeyEvent.ACTION_UP, code)
    }

    @Test
    fun `paused, the controls stay up, and playing again they go after a while`() {
        paused()
        pass(30_000)
        assertEquals(OverlayState.CONTROLS, vm.overlay)
        compose.runOnIdle { vm.engine!!.player.play() }
        pass(10_000)
        assertEquals(OverlayState.NONE, vm.overlay)
    }

    @Test
    fun `left paused two minutes, the screen dims, and a button only lights it again`() {
        paused()
        pass(115_000)
        assertFalse("dimmed too soon", vm.dimmed)
        pass(10_000)
        assertTrue("dimmed", vm.dimmed)
        press(NativeKeyEvent.KEYCODE_DPAD_CENTER) // would press play/pause, or hide the controls
        assertFalse(vm.dimmed)
        assertFalse("still paused", playWhenReady)
        assertEquals(OverlayState.CONTROLS, vm.overlay)
    }

    @Test
    fun `a button pressed while paused puts the dimming off`() {
        paused()
        pass(90_000)
        press(NativeKeyEvent.KEYCODE_DPAD_UP)
        pass(90_000)
        assertFalse(vm.dimmed)
        pass(40_000)
        assertTrue(vm.dimmed)
    }

    @Test
    fun `play on the dimmed screen plays straight away`() {
        paused()
        pass(125_000)
        assertTrue(vm.dimmed)
        press(NativeKeyEvent.KEYCODE_MEDIA_PLAY)
        assertFalse(vm.dimmed)
        assertTrue("playing", playWhenReady)
    }

    @Test
    fun `keys in a panel put the dimming off too, and dimming closes the panel`() {
        paused()
        press(NativeKeyEvent.KEYCODE_MENU) // the controls, on More
        press(NativeKeyEvent.KEYCODE_DPAD_CENTER) // the More panel, a window of its own
        compose.waitForText("Sleep timer")
        pass(90_000)
        press(NativeKeyEvent.KEYCODE_DPAD_DOWN)
        pass(90_000)
        assertFalse(vm.dimmed)
        assertEquals(PlaybackViewModel.Sheet.MORE, vm.sheet)
        pass(40_000)
        assertTrue(vm.dimmed)
        assertNull("the panel closed", vm.sheet)
    }

    @Test
    fun `paused again after a stop, the screen still dims`() {
        paused()
        pass(60_000)
        compose.runOnIdle { vm.engine!!.player.stop() } // as a failure and a retry do
        pass(70_000)
        compose.runOnIdle { vm.engine!!.player.prepare() }
        pass(115_000)
        assertFalse("dimmed too soon", vm.dimmed)
        pass(10_000)
        assertTrue("dimmed", vm.dimmed)
    }

    @Test
    fun `a button whose release went elsewhere works on its next press`() {
        paused()
        pass(125_000)
        // OK with focus on play/pause: its press only lights the screen, and its release never comes here.
        key(NativeKeyEvent.ACTION_DOWN, NativeKeyEvent.KEYCODE_DPAD_CENTER)
        assertFalse(vm.dimmed)
        press(NativeKeyEvent.KEYCODE_DPAD_CENTER) // the button acts on its release
        assertTrue("playing", playWhenReady)
    }

    @Test
    fun `a paused stream that keeps stopping and starting still dims`() {
        paused()
        repeat(3) {
            pass(40_000)
            compose.runOnIdle { vm.engine!!.player.stop() } // as a failure and a retry do
            compose.runOnIdle { vm.engine!!.player.prepare() }
        }
        assertTrue("dimmed", vm.dimmed)
    }
}
