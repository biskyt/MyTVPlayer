package com.biskyt.mytvplayer.ui

import android.view.KeyEvent
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.core.player.OverlayState
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.Navigators
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.addHomeTv
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.focusedText
import com.biskyt.mytvplayer.testutil.sendKey
import com.biskyt.mytvplayer.testutil.waitAdvancingClock
import com.biskyt.mytvplayer.ui.player.VodPlayerViewModel
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The remote's Back with the controls up hides them (it once moved focus to the play bar instead), once per press. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class PlayerBackUiTest {
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

    /** The remote's Back, pressed (held a moment: [repeats]) and let go. */
    private fun pressBack(repeats: Int = 0) {
        compose.sendKey(KeyEvent.KEYCODE_BACK, KeyEvent.ACTION_DOWN)
        repeat(repeats) { compose.sendKey(KeyEvent.KEYCODE_BACK, KeyEvent.ACTION_DOWN, repeat = it + 1) }
        compose.sendKey(KeyEvent.KEYCODE_BACK, KeyEvent.ACTION_UP)
    }

    @Test
    fun `back with the controls up hides them, held too, and back again leaves`() {
        val screen = Screen.VodPlayer(playlistId, 501)
        val navigator = navigators.new(screen)
        compose.setContent { MyTvTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
        val vm = ViewModelProvider(navigator.current)[VodPlayerViewModel::class.java]
        // The controls come up as playback starts, on Play/Pause.
        compose.waitAdvancingClock { vm.overlay == OverlayState.CONTROLS && compose.focusedText().isNotEmpty() }
        pressBack(repeats = 3)
        assertEquals(OverlayState.NONE, vm.overlay)
        assertEquals(screen, navigator.current.screen)
        pressBack()
        compose.waitAdvancingClock { vm.exit }
    }
}
