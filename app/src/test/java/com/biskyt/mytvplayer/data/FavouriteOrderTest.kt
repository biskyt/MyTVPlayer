package com.biskyt.mytvplayer.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.data.db.AppDatabase
import com.biskyt.mytvplayer.data.db.CAT_LIVE
import com.biskyt.mytvplayer.data.db.CAT_MOVIE
import com.biskyt.mytvplayer.data.db.FavouriteEntity
import com.biskyt.mytvplayer.data.db.VodFavouriteEntity
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.PlainApp
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The user's order of favourites: kept from before 1.2.0, new ones at the end, moved, and in backups. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = PlainApp::class)
class FavouriteOrderTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val provider = FakeProvider()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var epg: EpgRepository
    private lateinit var playlists: PlaylistRepository
    private lateinit var channels: ChannelRepository
    private lateinit var vod: VodRepository
    private lateinit var favourites: Favourites
    private var pid = 0L

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val http = OkHttpClient()
        settings = SettingsRepository(context)
        epg = EpgRepository(db, http, context.cacheDir.resolve("test-epg"), scope)
        val categoryStore = CategoryStore(db) { Locale.UK }
        playlists = PlaylistRepository(db, http, settings, epg, context.cacheDir.resolve("test-playlists"), categoryStore)
        channels = ChannelRepository(db)
        vod = VodRepository(db, playlists, categoryStore)
        favourites = Favourites(scope, settings, channels, vod)
        pid = playlists.add("Home", provider.url, "me", "secret")
        playlists.syncChannels(pid)
        vod.sync(playlists.get(pid)!!)
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        provider.close()
    }

    @Test
    fun `channels saved before the order keep channel-number order, and new favourites go at the end`() = runBlocking {
        // As 1.1 saved them (position 0), added in the opposite order.
        db.favourites().add(FavouriteEntity(pid, 103, addedAt = 1))
        db.favourites().add(FavouriteEntity(pid, 101, addedAt = 2))
        assertEquals(listOf(101L, 103L), favourites.order(pid, CAT_LIVE))
        channels.setFavourite(pid, 102, true)
        assertEquals(listOf(101L, 103L, 102L), favourites.order(pid, CAT_LIVE))
        // Every list of favourite channels follows it: Home's row, the paged list and zapping.
        assertEquals(listOf(101L, 103L, 102L), channels.observeFavourites(pid).first().map { it.streamId })
        assertEquals(listOf(101L, 103L, 102L), channels.ids(pid, ChannelFilter.Favourites))
    }

    @Test
    fun `movies saved before the order keep newest first, and new favourites go at the end`() = runBlocking {
        db.vod().addFavourite(VodFavouriteEntity(pid, CAT_MOVIE, 501, addedAt = 1))
        db.vod().addFavourite(VodFavouriteEntity(pid, CAT_MOVIE, 502, addedAt = 2))
        assertEquals(listOf(502L, 501L), favourites.order(pid, CAT_MOVIE))
        vod.setFavourite(pid, CAT_MOVIE, 701, true)
        assertEquals(listOf(502L, 501L, 701L), favourites.order(pid, CAT_MOVIE))
        assertEquals(listOf(502L, 501L, 701L), vod.observeFavouriteCards(pid, CAT_MOVIE, 10).first().map { it.id })
    }

    @Test
    fun `moving one stores the order, as far as the ends`() = runBlocking {
        listOf(101L, 102L, 103L).forEach { channels.setFavourite(pid, it, true) }
        val order = favourites.order(pid, CAT_LIVE)
        favourites.setOrder(pid, CAT_LIVE, Favourites.moved(order, 103, -1))
        assertEquals(listOf(101L, 103L, 102L), favourites.order(pid, CAT_LIVE))
        favourites.setOrder(pid, CAT_LIVE, Favourites.moved(favourites.order(pid, CAT_LIVE), 101, 5))
        assertEquals(listOf(103L, 102L, 101L), favourites.order(pid, CAT_LIVE))
        assertEquals(listOf(103L, 102L, 101L), Favourites.moved(listOf(103L, 102L, 101L), 103, -1))
        assertEquals(listOf(1L, 2L), Favourites.moved(listOf(1L, 2L), 9, 1)) // not in the list: left as it is
        // Home shows the first few: one moved along its row stays in it.
        assertEquals(listOf(1L, 3L, 2L, 4L), Favourites.moved(listOf(1L, 2L, 3L, 4L), 2, 5, shown = 3))
        assertEquals(listOf(1L, 2L, 3L, 4L), Favourites.moved(listOf(1L, 2L, 3L, 4L), 3, 1, shown = 3))
    }

    @Test
    fun `a backup keeps the order, and one from before the order restores in the old one`() = runBlocking {
        val backup = BackupRepository(db, settings, epg, SyncManager(scope, playlists, vod, epg, settings))
        listOf(101L, 102L, 103L).forEach { channels.setFavourite(pid, it, true) }
        favourites.setOrder(pid, CAT_LIVE, listOf(103L, 101L, 102L))
        val file = backup.export().decodeToString()

        playlists.delete(pid)
        assertTrue(backup.apply(backup.read(file.byteInputStream())))
        playlists.syncChannels(pid)
        assertEquals(listOf(103L, 101L, 102L), favourites.order(pid, CAT_LIVE))

        // A file from 1.1 has no positions: channel-number order.
        val old = Regex(""","position":\d+""").replace(file, "")
        assertTrue(old != file)
        playlists.delete(pid)
        assertTrue(backup.apply(backup.read(old.byteInputStream())))
        playlists.syncChannels(pid)
        assertEquals(listOf(101L, 102L, 103L), favourites.order(pid, CAT_LIVE))
    }
}
