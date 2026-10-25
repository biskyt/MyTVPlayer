package com.biskyt.mytvplayer.ui

import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performKeyPress
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.Navigators
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.addHomeTv
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.idleFrames
import com.biskyt.mytvplayer.testutil.topRoot
import com.biskyt.mytvplayer.testutil.waitAdvancingClock
import com.biskyt.mytvplayer.ui.player.VodPlayerViewModel
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** On a film the player has no channel guide: the Guide button is the TV's, the whole press, held or not. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class FilmGuideKeyUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val provider = FakeProvider()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var playlistId = 0L

    @Before
    fun setUp() = runBlocking {
        playlistId = app.container.addHomeTv(provider, vod = true)
    }

    @After
    fun tearDown() = provider.close()

    /** Sends a Guide key event; whether the app kept it. */
    private fun guide(action: Int, repeat: Int = 0): Boolean =
        compose.topRoot().performKeyPress(KeyEvent(NativeKeyEvent(0, 0, action, NativeKeyEvent.KEYCODE_GUIDE, repeat))).also {
            compose.idleFrames()
        }

    @Test
    fun `a held guide button on a film is left to the TV`() {
        val navigator = navigators.new(Screen.VodPlayer(playlistId, 501))
        compose.setContent { MyTvTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
        val vm = ViewModelProvider(navigator.current)[VodPlayerViewModel::class.java]
        compose.waitAdvancingClock { vm.engine?.player?.currentMediaItem != null }
        assertFalse("pressed", guide(NativeKeyEvent.ACTION_DOWN))
        assertFalse("held", guide(NativeKeyEvent.ACTION_DOWN, repeat = 1))
        assertFalse("let go", guide(NativeKeyEvent.ACTION_UP))
    }
}
