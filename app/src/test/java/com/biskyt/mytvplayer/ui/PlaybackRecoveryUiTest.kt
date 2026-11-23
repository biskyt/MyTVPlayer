package com.biskyt.mytvplayer.ui

import android.os.SystemClock
import androidx.compose.runtime.remember
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.data.ChannelFilter
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.Navigators
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.addHomeTv
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.waitAdvancingClock
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** How the player gets a stream going when its first tries fail. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class PlaybackRecoveryUiTest {
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

    private fun open(screen: Screen) {
        val navigator = navigators.new(screen)
        compose.setContent { MyTvTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
    }

    private fun requested(path: (String) -> Boolean) = provider.requests.count { path(it.substringBefore('?')) }

    @Test
    fun `a live channel that fails twice in MPEG-TS is tried in HLS`() {
        provider.liveTsFails = true
        open(Screen.Player(playlistId, 101, ChannelFilter.All))
        compose.waitAdvancingClock(stepMs = 100) { requested { it.startsWith("/live/") && it.endsWith("/101.m3u8") } > 0 }
        assertTrue(requested { it.startsWith("/live/") && it.endsWith("/101.ts") } >= 2) // a retry first, then HLS
    }

    @Test
    fun `a film whose server does not answer at first is tried again after about 20 seconds`() {
        // The owner, 2026-11-23: an old episode on slow storage plays at the second or third try. Waiting 60 seconds
        // for each try made it sit for minutes, then fail.
        provider.silentFilms = true
        open(Screen.VodPlayer(playlistId, 501))
        compose.waitAdvancingClock(stepMs = 0) { requested { it.startsWith("/movie/") } > 0 }
        val start = SystemClock.uptimeMillis()
        compose.waitAdvancingClock(stepMs = 500) { requested { it.startsWith("/movie/") } > 1 }
        val waited = SystemClock.uptimeMillis() - start
        assertTrue("tried again after $waited ms", waited < 30_000)
    }
}
