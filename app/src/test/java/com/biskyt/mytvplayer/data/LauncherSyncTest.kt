package com.biskyt.mytvplayer.data

import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.data.db.CAT_LIVE
import com.biskyt.mytvplayer.data.db.CAT_MOVIE
import com.biskyt.mytvplayer.data.db.VOD_EPISODE
import com.biskyt.mytvplayer.data.db.VOD_MOVIE
import com.biskyt.mytvplayer.data.db.WatchProgressEntity
import com.biskyt.mytvplayer.launcher.LauncherRows
import com.biskyt.mytvplayer.launcher.LauncherWriter
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.addHomeTv
import com.biskyt.mytvplayer.testutil.eventually
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Continue watching and the favourite channels on the TV's home screen (the owner, 2026-11-14): off by default, with
 * nothing written while off; on, the active playlist's cards go to the launcher, never a restricted one; off again, they
 * are taken off.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class)
class LauncherSyncTest {
    private class Writer : LauncherWriter {
        val published = CopyOnWriteArrayList<LauncherRows>()

        @Volatile var cleared = 0

        @Volatile var on = false

        override fun isOn() = on

        override fun publish(rows: LauncherRows): Long? {
            on = true
            published += rows
            return if (published.size == 1) CHANNEL else null
        }

        override fun clear() {
            on = false
            cleared++
        }
    }

    private val provider = FakeProvider()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private val c get() = app.container
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val writer = Writer()
    private var pid = 0L

    @Before
    fun setUp() = runBlocking<Unit> {
        pid = c.addHomeTv(provider, vod = true)
        val now = System.currentTimeMillis()
        // Dune (UK | MOVIES, restricted below) and Amélie (FR | FILMS).
        listOf(501L, 601L).forEachIndexed { i, id ->
            c.vod.saveProgress(WatchProgressEntity(pid, VOD_MOVIE, id, 600_000, 6_000_000, now + i, "Film $id", "http://img/$id.jpg"))
        }
        // Episode 2 of Line of Duty watched to its end, the next one not known yet: up next.
        c.vod.saveProgress(
            WatchProgressEntity(
                pid, VOD_EPISODE, 9002, 1_440_000, 1_440_000, now - 1, "Line of Duty", "http://img/s77.jpg",
                seriesId = 77, season = 1, episode = 2, episodeTitle = "Second", hasNext = true
            )
        )
        // Channels 101 (UK | ENTERTAINMENT, restricted below) and 102 (UK | SPORT).
        listOf(101L, 102L).forEach { c.channels.setFavourite(pid, it, true) }
        c.parental.setRestricted(pid, CAT_MOVIE, "10", true)
        c.parental.setRestricted(pid, CAT_LIVE, "1", true)
    }

    @After
    fun tearDown() {
        scope.cancel()
        provider.close()
    }

    @Test
    fun `off by default, nothing is written`() = runBlocking {
        LauncherSync(app, scope, c.settings, c.db, writer)
        delay(1_000)
        assertEquals(0, writer.published.size)
        assertEquals("nothing to take off", 0, writer.cleared)
    }

    @Test
    fun `on, the active playlist's cards go to the launcher, never a restricted one, and off they come off`() = runBlocking {
        val sync = LauncherSync(app, scope, c.settings, c.db, writer)
        c.settings.update { it.copy(launcherRows = true) }
        eventually("cards written") { writer.published.isNotEmpty() }
        val rows = writer.published.last()
        assertEquals(listOf("$pid:$VOD_MOVIE:601", "$pid:$VOD_EPISODE:9002"), rows.playNext.map { it.key })
        assertEquals("http://img/601.jpg", rows.playNext.first().posterUrl)
        val upNext = rows.playNext.last()
        assertTrue("a finished episode with another after it is up next", upNext.upNext && upNext.episode == null)
        assertEquals(listOf("$pid:102"), rows.channel.map { it.key })
        assertEquals("Favourite channels", rows.channelName)
        eventually("the channel to show") { sync.madeChannel.value == CHANNEL }

        // A card opens the app on it, if it still may.
        val target = LauncherSync.target(app, rows.playNext.first().open)
        assertEquals(LauncherTarget(VOD_MOVIE, pid, 601), target)
        assertTrue(sync.allows(target!!))
        assertFalse("a restricted film does not open", sync.allows(LauncherTarget(VOD_MOVIE, pid, 501)))
        assertFalse("a restricted channel does not open", sync.allows(LauncherTarget(LauncherTarget.CHANNEL, pid, 101)))
        assertTrue(sync.allows(LauncherTarget(LauncherTarget.CHANNEL, pid, 102)))
        assertNotNull(LauncherSync.target(app, rows.channel.single().open))

        c.settings.update { it.copy(launcherRows = false) }
        eventually("cards taken off") { writer.cleared == 1 }
    }

    private companion object {
        const val CHANNEL = 7L
    }
}
