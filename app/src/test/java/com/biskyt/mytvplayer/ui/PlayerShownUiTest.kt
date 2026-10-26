package com.biskyt.mytvplayer.ui

import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.Navigators
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.addHomeTv
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.idleFrames
import com.biskyt.mytvplayer.testutil.waitAdvancingClock
import com.biskyt.mytvplayer.ui.player.VodPlayerViewModel
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

/**
 * An episode whose details are slow to arrive: the player screen is told the app is shown as soon as it's added (and
 * again on coming back), before there's anything in the player. That mustn't close it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class PlayerShownUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val provider = FakeProvider()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var playlistId = 0L

    @Before
    fun setUp() = runBlocking {
        playlistId = app.container.addHomeTv(provider, vod = true)
        provider.seriesInfoDelayMs = 1_500
    }

    @After
    fun tearDown() = provider.close()

    /**
     * The episode's player opened (its details still on their way). Its player is made before the screen is shown,
     * as can happen on a device (the settings it waits for are already in memory there).
     */
    private fun episode(): VodPlayerViewModel {
        val screen = Screen.VodPlayer(playlistId, 9001, seriesId = 77)
        val navigator = navigators.new(screen)
        // The one the screen then finds (by the same default key).
        val factory = viewModelFactory { initializer { VodPlayerViewModel(app.container, app, screen) } }
        val vm = ViewModelProvider(navigator.current, factory)[VodPlayerViewModel::class.java]
        compose.waitAdvancingClock { vm.engine != null }
        compose.setContent { MyTvTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
        compose.idleFrames()
        return vm
    }

    /** Waits (in real time) for the episode's details: its stream in the player, unless the player closed first. */
    private fun VodPlayerViewModel.awaitStream() {
        compose.waitAdvancingClock { exit || engine?.player?.mediaItemCount == 1 }
        assertFalse("closed itself", exit)
        assertTrue("not playing", compose.runOnIdle { engine!!.player.playWhenReady })
    }

    @Test
    fun `opening an episode whose details are slow doesn't close the player`() {
        episode().awaitStream()
    }

    @Test
    fun `back in the app before the episode's details arrived, it plays them when they do`() {
        val vm = episode()
        compose.runOnIdle {
            vm.onAppHidden()
            vm.onAppShown()
        }
        vm.awaitStream()
    }
}
