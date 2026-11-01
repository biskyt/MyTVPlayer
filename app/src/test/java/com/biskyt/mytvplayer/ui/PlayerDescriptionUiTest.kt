package com.biskyt.mytvplayer.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.appString
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.Navigators
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.addHomeTv
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.waitAdvancingClock
import com.biskyt.mytvplayer.testutil.waitForText
import com.biskyt.mytvplayer.ui.common.formatDate
import com.biskyt.mytvplayer.ui.player.PlaybackViewModel
import com.biskyt.mytvplayer.ui.player.VodPlayerViewModel
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** An episode's description, from the player's More menu, over the video; only for episodes with a description or a date. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class PlayerDescriptionUiTest {
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

    private fun play(episodeId: Long): VodPlayerViewModel {
        val navigator = navigators.new(Screen.VodPlayer(playlistId, episodeId, seriesId = 77))
        compose.setContent { MyTvTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
        val vm = ViewModelProvider(navigator.current)[VodPlayerViewModel::class.java]
        compose.waitAdvancingClock { vm.engine?.player?.currentMediaItem != null }
        return vm
    }

    @Test
    fun `More opens the episode's description, with when it was first shown`() {
        val vm = play(9001)
        vm.sheet = PlaybackViewModel.Sheet.MORE
        compose.waitForText("Episode description")
        compose.onAllNodes(hasText("Episode description"))[0].performClick()
        compose.waitForText("First.")
        compose.waitForText("First shown ${formatDate(LocalDate.of(2014, 6, 26))}")
        compose.waitForText("Line of Duty")
        assertTrue("still playing", vm.engine?.player?.playWhenReady == true)
    }

    @Test
    fun `an episode with only a date has the item too`() {
        val vm = play(9003)
        assertTrue(vm.menuActions().any { it.label == "Episode description" })
    }

    @Test
    fun `an episode with no description or date has no Episode description item`() {
        val vm = play(9002)
        assertTrue(vm.menuActions().none { it.label == appString(R.string.player_description) })
    }
}
