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
import com.biskyt.mytvplayer.ui.player.PlaybackViewModel
import com.biskyt.mytvplayer.ui.player.VodPlayerViewModel
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

/** What the player tells about a stream: the errors and their address, and the Stream info panel. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class StreamDiagnosticsUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val provider = FakeProvider()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var playlistId = 0L
    private val filmAddress get() = provider.url + "movie/me/secret/501.mkv"

    @Before
    fun setUp() = runBlocking {
        playlistId = app.container.addHomeTv(provider, vod = true)
    }

    @After
    fun tearDown() = provider.close()

    private fun playUntilError(): VodPlayerViewModel {
        val navigator = navigators.new(Screen.VodPlayer(playlistId, 501))
        compose.setContent { MyTvTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
        val vm = ViewModelProvider(navigator.current)[VodPlayerViewModel::class.java]
        // The player retries a failed load a few times first: let its (simulated) time pass.
        compose.waitAdvancingClock(500) { vm.error != null }
        return vm
    }

    @Test
    fun `an empty film says the server sent an empty file, and shows its address`() {
        provider.emptyFilms = true
        val vm = playUntilError()
        assertTrue(vm.error.toString(), vm.error?.reason.orEmpty().startsWith(appString(R.string.player_error_empty)))
        assertEquals(filmAddress, vm.error?.address)
        // Shown without the login: the user name and password in the path are hidden.
        compose.waitForText(provider.url + "movie/•••/•••/501.mkv")
    }

    @Test
    fun `a film that sends data the player cannot read still says so`() {
        // The fake server answers a film's address with its account details: data, but not a film.
        val vm = playUntilError()
        assertTrue(vm.error.toString(), vm.error?.reason.orEmpty().startsWith(appString(R.string.player_error_parsing)))
    }

    @Test
    fun `Stream info, in the More panel, shows the stream's address`() {
        val vm = playUntilError()
        vm.sheet = PlaybackViewModel.Sheet.MORE
        compose.waitForText(appString(R.string.player_stream_info))
        compose.onAllNodes(hasText(appString(R.string.player_stream_info)))[0].performClick()
        compose.waitForText(appString(R.string.player_info_address, provider.url + "movie/•••/•••/501.mkv"))
    }
}
