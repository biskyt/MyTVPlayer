package com.biskyt.mytvplayer.ui

import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.pressKey
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.data.ChannelFilter
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.Navigators
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.addHomeTv
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.focusedText
import com.biskyt.mytvplayer.testutil.idleFrames
import com.biskyt.mytvplayer.testutil.topRoot
import com.biskyt.mytvplayer.testutil.waitAdvancingClock
import com.biskyt.mytvplayer.testutil.waitForText
import com.biskyt.mytvplayer.ui.player.PlayerViewModel
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
import org.robolectric.annotation.Config

/** A keyboard on the player (the owner, 2026-11-13): Space plays and pauses, X stops, whatever button has focus. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class PlayerKeyboardUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val provider = FakeProvider().apply { streamsTrickle = true } // never fails, so no retry plays it again
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private lateinit var navigator: Navigator
    private lateinit var vm: PlayerViewModel

    @Before
    fun setUp() = runBlocking<Unit> {
        val playlistId = app.container.addHomeTv(provider)
        navigator = navigators.new(Screen.Live())
        compose.setContent { MyTvTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
        compose.runOnIdle { navigator.push(Screen.Player(playlistId, 101, ChannelFilter.All)) }
        // The player's screen makes its ViewModel: shown first, then asked for.
        compose.waitForText("Channel guide")
        vm = ViewModelProvider(navigator.current)[PlayerViewModel::class.java]
        compose.waitAdvancingClock(stepMs = 50) { vm.engine?.player?.currentMediaItem != null }
        // The controls are up, with play/pause focused.
        compose.waitAdvancingClock(stepMs = 50) { compose.focusedText().let { it == "Pause" || it == "Play" } }
    }

    @After
    fun tearDown() = provider.close()

    private val playing get(): Boolean = compose.runOnIdle { vm.engine!!.player.playWhenReady }

    private fun press(key: Key) {
        compose.topRoot().performKeyInput { pressKey(key) }
        compose.idleFrames()
    }

    /**
     * Paused. (Play/pause pauses only while the video plays, and the test's stream never stops buffering: from paused,
     * Space plays, which shows it acted.)
     */
    private fun pause() {
        compose.runOnIdle { vm.engine!!.player.pause() }
        compose.idleFrames()
        assertFalse(playing)
    }

    @Test
    fun `space plays from paused, with play-pause focused`() {
        pause()
        press(Key.Spacebar)
        assertTrue(playing)
    }

    @Test
    fun `space plays with another button focused, and does not press that button`() {
        press(Key.DirectionRight)
        compose.waitAdvancingClock(stepMs = 50) { compose.focusedText().let { it != "Pause" && it != "Play" && it.isNotEmpty() } }
        pause()
        press(Key.Spacebar)
        assertTrue(playing)
        assertTrue("no panel or message from the focused button", compose.runOnIdle { vm.sheet == null && vm.message == null })
        assertTrue("still playing, not stopped or moved on", navigator.current.screen is Screen.Player)
    }

    @Test
    fun `x stops playback, back where it was opened from`() {
        press(Key.X)
        compose.waitAdvancingClock(stepMs = 50) { navigator.current.screen is Screen.Live }
    }

    /** [code] held for [repeats] repeats, with [meta] (Ctrl, Alt) held too, as a keyboard sends it. */
    private fun hold(code: Int, repeats: Int = 0, meta: Int = 0) {
        for (r in 0..repeats) {
            compose.topRoot().performKeyPress(KeyEvent(NativeKeyEvent(0, 0, NativeKeyEvent.ACTION_DOWN, code, r, meta)))
        }
        compose.topRoot().performKeyPress(KeyEvent(NativeKeyEvent(0, 0, NativeKeyEvent.ACTION_UP, code, 0, meta)))
        compose.idleFrames()
    }

    @Test
    fun `a held x stops once, and stays on the screen the player was opened from`() {
        hold(NativeKeyEvent.KEYCODE_X, repeats = 5)
        compose.waitAdvancingClock(stepMs = 50) { navigator.current.screen is Screen.Live }
        compose.idleFrames()
        assertTrue(navigator.current.screen is Screen.Live)
    }

    @Test
    fun `ctrl x is a keyboard shortcut, not stop`() {
        hold(NativeKeyEvent.KEYCODE_X, meta = NativeKeyEvent.META_CTRL_ON or NativeKeyEvent.META_CTRL_LEFT_ON)
        assertTrue(navigator.current.screen is Screen.Player)
        assertTrue(playing)
    }

    @Test
    fun `space plays with a panel open over the video`() {
        press(Key.Menu)
        compose.waitAdvancingClock(stepMs = 50) { compose.focusedText().contains("More") }
        press(Key.DirectionCenter)
        compose.waitForText("Picture:")
        pause()
        press(Key.Spacebar)
        assertTrue(playing)
    }
}
