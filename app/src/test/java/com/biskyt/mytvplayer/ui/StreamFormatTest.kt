package com.biskyt.mytvplayer.ui

import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.data.ChannelFilter
import com.biskyt.mytvplayer.data.StreamFormat
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.addHomeTv
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.waitAdvancingClock
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

/** Provider channels that won't start in one stream format are tried in the other, which is then remembered. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class)
class StreamFormatTest {
    @get:Rule val compose = createUiComposeRule()

    private val provider = FakeProvider()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var playlistId = 0L

    @Before
    fun setUp() = runBlocking {
        playlistId = app.container.addHomeTv(provider)
    }

    @After
    fun tearDown() = provider.close()

    @Test
    fun `a provider channel that won't start in the format set is tried in the other`() {
        val vm = com.biskyt.mytvplayer.ui.player.PlayerViewModel(app.container, app, Screen.Player(playlistId, 101, ChannelFilter.All))
        // The fake provider has no streams: MPEG-TS (the default) fails, so HLS is tried, once.
        // The player retries a failed load a few times first: let its (simulated) time pass.
        compose.waitAdvancingClock(500) { provider.requests.any { it.startsWith("/live/me/secret/101.m3u8") } }
        assertTrue(provider.requests.any { it.startsWith("/live/me/secret/101.ts") })
        vm.engine?.release()
    }

    @Test
    fun `a stream that trickles in, never enough to play, ends in an error rather than loading for ever`() {
        provider.streamsTrickle = true
        val vm = com.biskyt.mytvplayer.ui.player.PlayerViewModel(app.container, app, Screen.Player(playlistId, 101, ChannelFilter.All))
        // The stall check and the retries run on (simulated) time; the trickling server's real time hardly passes.
        compose.waitAdvancingClock(1_000) { vm.error != null }
        // Each stall was handled like any failure: retried, then the other format tried (whose playlist, the same junk,
        // fails as unreadable).
        assertTrue(provider.requests.toString(), provider.requests.count { it.startsWith("/live/me/secret/101.ts") } > 1)
        assertTrue(provider.requests.toString(), provider.requests.any { it.startsWith("/live/me/secret/101.m3u8") })
        // The error shows the address of the stream that failed last, so that the viewer can report it.
        assertEquals(provider.url + "live/me/secret/101.m3u8", vm.error?.address)
        vm.engine?.release()
    }

    @Test
    fun `the format a provider fell back to is kept until the setting is changed or the playlist removed`() = runBlocking {
        val settings = app.container.settings
        assertEquals(null, settings.streamFormatFor(playlistId))
        settings.rememberStreamFormat(playlistId, StreamFormat.HLS)
        settings.update { it.copy(lastChannelId = 5) }
        assertEquals(StreamFormat.HLS, settings.streamFormatFor(playlistId))
        settings.setStreamFormat(StreamFormat.HLS)
        assertEquals(null, settings.streamFormatFor(playlistId))
        assertEquals(StreamFormat.HLS, settings.current().streamFormat)
        settings.rememberStreamFormat(playlistId, StreamFormat.TS)
        app.container.playlists.delete(playlistId)
        assertEquals(null, settings.streamFormatFor(playlistId))
    }
}
