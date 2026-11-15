package com.biskyt.mytvplayer.data

import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.data.db.CAT_LIVE
import com.biskyt.mytvplayer.data.db.CAT_MOVIE
import com.biskyt.mytvplayer.data.db.CAT_SERIES
import com.biskyt.mytvplayer.data.db.VOD_EPISODE
import com.biskyt.mytvplayer.data.db.VOD_MOVIE
import com.biskyt.mytvplayer.data.db.WatchProgressEntity
import com.biskyt.mytvplayer.launcher.LauncherRows
import com.biskyt.mytvplayer.launcher.LauncherWriter
import com.biskyt.mytvplayer.launcher.Published
import com.biskyt.mytvplayer.launcher.RowCard
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
 * Continue watching and the favourite channels, movies and series on the TV's home screen (the owner, 2026-11-14): off by default, with
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

        /** The rows on the launcher now, by key, as the TV provider keeps them. */
        private val present = mutableSetOf<String>()

        override fun publish(rows: LauncherRows): Published {
            on = true
            published += rows
            val keys = rows.channels.map { it.key }
            val made = keys - present
            present.retainAll(keys.toSet())
            present += keys
            return Published(keys.map(ID::getValue).toSet(), made.map(ID::getValue).toSet())
        }

        override fun clear() {
            present.clear()
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
        val channel = rows.channels.single { it.key == "favourites" }
        assertEquals(listOf("$pid:102"), channel.cards.map { it.key })
        assertEquals("Favourite channels", channel.name)
        assertEquals("no favourite movies or series: no rows for them", 1, rows.channels.size)
        eventually("the row to show") { sync.madeChannels.value == setOf(CHANNELS) }

        // A card opens the app on it, if it still may.
        val target = LauncherSync.target(app, rows.playNext.first().open)
        assertEquals(LauncherTarget(VOD_MOVIE, pid, 601), target)
        assertTrue(sync.allows(target!!))
        assertFalse("a restricted film does not open", sync.allows(LauncherTarget(VOD_MOVIE, pid, 501)))
        assertFalse("a restricted channel does not open", sync.allows(LauncherTarget(LauncherTarget.CHANNEL, pid, 101)))
        assertTrue(sync.allows(LauncherTarget(LauncherTarget.CHANNEL, pid, 102)))
        assertNotNull(LauncherSync.target(app, channel.cards.single().open))

        c.settings.update { it.copy(launcherRows = false) }
        eventually("cards taken off") { writer.cleared == 1 }
    }

    @Test
    fun `with no favourite channels there is no row, not an empty one`() = runBlocking {
        c.channels.setFavourite(pid, 102, false) // 101 is restricted: none left to show
        LauncherSync(app, scope, c.settings, c.db, writer)
        c.settings.update { it.copy(launcherRows = true) }
        eventually("cards written") { writer.published.isNotEmpty() }
        assertEquals(emptyList<Any>(), writer.published.last().channels)
        assertEquals(2, writer.published.last().playNext.size)
    }

    @Test
    fun `a row made and then taken away is not asked to show`() = runBlocking {
        val sync = LauncherSync(app, scope, c.settings, c.db, writer)
        c.settings.update { it.copy(launcherRows = true) }
        eventually("the row to show") { sync.madeChannels.value == setOf(CHANNELS) }
        // Written again with the row still there (none made now): it is still to be shown.
        sync.republish()
        eventually("written again") { writer.published.size == 2 }
        assertEquals(setOf(CHANNELS), sync.madeChannels.value)
        // The last favourite gone before Settings showed the row: the cards are written again, with no row.
        c.channels.setFavourite(pid, 102, false)
        sync.republish()
        eventually("no row to show") { sync.madeChannels.value.isEmpty() }
        assertEquals(emptyList<Any>(), writer.published.last().channels)
    }

    @Test
    fun `favourite movies and series are rows of posters that open their pages, never a restricted one`() = runBlocking {
        // Dune (501) is restricted; Heat (701) is not.
        listOf(501L, 701L).forEach { c.vod.setFavourite(pid, CAT_MOVIE, it, true) }
        c.vod.setFavourite(pid, CAT_SERIES, 77, true)
        val sync = LauncherSync(app, scope, c.settings, c.db, writer)
        c.settings.update { it.copy(launcherRows = true) }
        eventually("all three rows") { writer.published.lastOrNull()?.channels?.size == 3 }
        val rows = writer.published.last().channels
        assertEquals(listOf("favourites", "favourite-movies", "favourite-series"), rows.map { it.key })
        val (_, movies, series) = rows
        assertEquals("Favourite movies", movies.name)
        assertEquals(listOf("$pid:${LauncherTarget.MOVIE_PAGE}:701"), movies.cards.map { it.key })
        assertEquals(RowCard.Kind.MOVIE, movies.cards.single().kind)
        assertEquals("Favourite series", series.name)
        assertEquals(listOf(RowCard.Kind.SERIES), series.cards.map { it.kind })
        assertNotNull("a poster", series.cards.single().imageUrl)
        eventually("all three rows to show") { sync.madeChannels.value == setOf(CHANNELS, MOVIES, SERIES) }

        // A card opens its page, and a row's own link its list, if they still may.
        val page = LauncherSync.target(app, movies.cards.single().open)
        assertEquals(LauncherTarget(LauncherTarget.MOVIE_PAGE, pid, 701), page)
        assertTrue(sync.allows(page!!))
        assertEquals(LauncherTarget(LauncherTarget.SERIES_PAGE, pid, 77), LauncherSync.target(app, series.cards.single().open))
        assertEquals(LauncherTarget(LauncherTarget.FAVOURITE_SERIES, pid), LauncherSync.target(app, series.open))
        assertFalse("a restricted film does not open", sync.allows(LauncherTarget(LauncherTarget.MOVIE_PAGE, pid, 501)))
        c.vod.setFavourite(pid, CAT_MOVIE, 701, false)
        assertFalse("no longer a favourite", sync.allows(page))
        // The last favourite movie gone: its row comes off, and the others stay. (Written again at once: changes are
        // otherwise written once they stop.)
        sync.republish()
        eventually("the movies' row taken off") {
            writer.published.last().channels.map { it.key } == listOf("favourites", "favourite-series")
        }

        // Line of Duty (UK | SERIES) restricted now: it does not open, and its row comes off too.
        c.parental.setRestricted(pid, CAT_SERIES, "20", true)
        assertFalse("a restricted series does not open", sync.allows(LauncherTarget(LauncherTarget.SERIES_PAGE, pid, 77)))
        sync.republish()
        eventually("the series' row taken off") { writer.published.last().channels.map { it.key } == listOf("favourites") }
    }

    private companion object {
        const val CHANNELS = 7L
        const val MOVIES = 8L
        const val SERIES = 9L
        val ID = mapOf("favourites" to CHANNELS, "favourite-movies" to MOVIES, "favourite-series" to SERIES)
    }
}
