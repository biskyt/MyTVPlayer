package com.biskyt.mytvplayer.data

import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.TestApp
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Only the playlist in use is refreshed; switching to another stops the old one's refresh and refreshes the new one. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class)
class SyncInUseTest {
    private val inUse = FakeProvider()
    private val other = FakeProvider()
    private val c = ApplicationProvider.getApplicationContext<TestApp>().container
    private var inUseId = 0L
    private var otherId = 0L

    @Before
    fun setUp() = runBlocking {
        otherId = c.playlists.add("Other", other.url, "me", "secret")
        inUseId = c.playlists.add("Home", inUse.url, "me", "secret") // added last, so in use
        inUse.requests.clear()
        other.requests.clear()
    }

    @After
    fun tearDown() {
        inUse.close()
        other.close()
    }

    private fun FakeProvider.loadedChannels() = requests.any { it.endsWith("get_live_streams") }

    @Test
    fun `refreshing everything leaves a playlist not in use alone`() = runBlocking {
        c.sync.refreshAllNow()
        assertTrue("the playlist in use is refreshed", inUse.loadedChannels())
        assertTrue("the other isn't: ${other.requests}", other.requests.isEmpty())
    }

    @Test
    fun `a refresh asked for a playlist not in use does nothing`() = runBlocking {
        c.sync.refreshNow(otherId)
        c.sync.refreshVod(otherId).join()
        c.sync.updateOutOfDateGuides(otherId)
        assertTrue(other.requests.toString(), other.requests.isEmpty())
    }

    @Test
    fun `switching stops the old playlist's refresh and refreshes the new one`() = runBlocking {
        inUse.streamsDelayMs = 2_000
        val old = c.sync.refresh(inUseId)
        withTimeout(10_000) { while (!inUse.loadedChannels()) kotlinx.coroutines.delay(10) }
        c.settings.update { it.copy(activePlaylistId = otherId) }
        c.sync.switchTo(otherId).join()
        old.join() // stopped, not left running
        assertTrue("the new playlist is refreshed", other.loadedChannels())
        // Nothing of the old one after its channels: no movies or series.
        assertTrue(inUse.requests.toString(), inUse.requests.none { it.endsWith("get_vod_categories") || it.endsWith("get_vod_streams") })
    }
}
