package com.biskyt.mytvplayer.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.core.sync.SyncException
import com.biskyt.mytvplayer.core.sync.SyncFile
import com.biskyt.mytvplayer.core.sync.SyncStore
import com.biskyt.mytvplayer.data.db.AppDatabase
import com.biskyt.mytvplayer.data.db.CAT_LIVE
import com.biskyt.mytvplayer.data.db.CAT_MOVIE
import com.biskyt.mytvplayer.data.db.CategoryEntity
import com.biskyt.mytvplayer.data.db.ChannelEntity
import com.biskyt.mytvplayer.data.db.FavouriteEntity
import com.biskyt.mytvplayer.data.db.PlaylistEntity
import com.biskyt.mytvplayer.data.db.VOD_EPISODE
import com.biskyt.mytvplayer.data.db.VOD_MOVIE
import com.biskyt.mytvplayer.data.db.WatchProgressEntity
import com.biskyt.mytvplayer.testutil.PlainApp
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Two TVs with the same playlist (under different local ids) and one shared sync folder. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = PlainApp::class)
class ProgressSyncTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val folder = FakeFolder()
    private lateinit var a: Tv
    private lateinit var b: Tv

    private inner class Tv(name: String, extraPlaylists: Int) {
        val db: AppDatabase = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()

        /** This TV's clock for the user's choices (choices of "none" older than 60 days are dropped). */
        @Volatile var now = NOW

        /** What [sync] asked to download again, for each playlist. */
        val reloaded = ConcurrentHashMap<Long, Reload>()
        private lateinit var choices: SyncedChoices
        val sync = ProgressSync(
            db,
            SyncAccount(PreferenceDataStoreFactory.create(scope = scope) { context.filesDir.resolve("sync-$name.preferences_pb") }),
            null,
            scope,
            { folder },
            choices = { choices },
            onChoices = { pid, reload -> reloaded[pid] = reload }
        )
        private val log = ChoiceLog(db, sync) { now }
        private val categoryStore = CategoryStore(db, log) { Locale.UK }
        private val http = OkHttpClient()
        val epg = EpgRepository(db, http, context.cacheDir.resolve("epg-$name"), scope, log = log)
        private val playlists = PlaylistRepository(
            db,
            http,
            SettingsRepository(context),
            epg,
            context.cacheDir.resolve("playlists-$name"),
            categoryStore
        )
        val channels = ChannelRepository(db, log) { now }
        val vod = VodRepository(db, playlists, categoryStore, sync, log)
        val parental = ParentalRepository(db, SettingsRepository(context), scope, log)

        init {
            choices = SyncedChoices(db, channels, vod, categoryStore, parental, epg)
        }

        val pid: Long = runBlocking {
            repeat(extraPlaylists) {
                db.playlists().insert(PlaylistEntity(name = "Other $it", server = "http://other$it", username = "u", password = "p"))
            }
            db.playlists().insert(PlaylistEntity(name = "Home", server = "http://Example.com/", username = "me", password = "p"))
        }

        suspend fun signIn() {
            sync.account.signIn("refresh", "me@example.com")
            withTimeout(WAIT_MS) { while (!sync.on) delay(10) }
        }

        suspend fun progress(kind: String, id: Long) = db.vod().progress(pid, kind, id)

        /** Two live categories, both ticked. */
        suspend fun loadCategories() = db.categories().upsert(
            listOf(CategoryEntity(pid, "1", "UK | NEWS", "GB", true, 0), CategoryEntity(pid, "2", "UK | SPORT", "GB", true, 1))
        )

        suspend fun included() = db.categories().included(pid, CAT_LIVE).map { it.categoryId }.sorted()

        suspend fun choose(id: String, included: Boolean) = categoryStore.choose(pid, CAT_LIVE, listOf(id), included)

        /** Channels 101 and 102. */
        suspend fun loadChannels() = db.channels().upsert(
            listOf(101L, 102L).map { ChannelEntity(pid, it, it.toInt(), "Channel $it", "1", null, null, 0) }
        )

        /** The favourite channels' ids, whether or not the channels are loaded. */
        suspend fun favourites() = db.favourites().observeIds(pid).first()

        suspend fun sync() = sync.finish(always = true)
    }

    @Before
    fun setUp() {
        context.filesDir.listFiles()?.filter { it.name.startsWith("sync-") }?.forEach { it.delete() }
        a = Tv("a", extraPlaylists = 0)
        b = Tv("b", extraPlaylists = 2)
    }

    @After
    fun tearDown() {
        scope.cancel()
        a.db.close()
        b.db.close()
    }

    private fun film(pid: Long, position: Long, at: Long) = WatchProgressEntity(pid, VOD_MOVIE, 501, position, 3_600_000, at, "Film", null)

    private suspend fun waitForUploads(count: Int) = withTimeout(WAIT_MS) { while (folder.uploads.get() < count) delay(10) }

    @Test
    fun `a place saved on one TV is where the other TV carries on`() = runBlocking {
        a.signIn()
        b.signIn()
        a.vod.saveProgress(film(a.pid, 600_000, 1_000), trim = true)
        waitForUploads(1)

        b.sync.finish(always = true)
        assertEquals(film(b.pid, 600_000, 1_000), b.progress(VOD_MOVIE, 501))
        assertEquals(listOf(501L), b.vod.observeCarryOn(b.pid).first().map { it.itemId })
    }

    @Test
    fun `a film forgotten on one TV is forgotten on the other`() = runBlocking {
        a.signIn()
        b.signIn()
        a.vod.saveProgress(film(a.pid, 600_000, 1_000), trim = true)
        waitForUploads(1)
        b.sync.finish(always = true)

        b.vod.forgetProgress(b.progress(VOD_MOVIE, 501)!!)
        b.sync.finish()
        a.sync.finish(always = true)
        assertNull(a.progress(VOD_MOVIE, 501))
        // And A's own file, sent again later, does not bring it back to B.
        a.sync.finish(always = true)
        b.sync.finish(always = true)
        assertNull(b.progress(VOD_MOVIE, 501))
    }

    @Test
    fun `a forgotten episode is forgotten on the other TV`() = runBlocking {
        a.signIn()
        b.signIn()
        a.vod.saveProgress(WatchProgressEntity(a.pid, VOD_EPISODE, 9, 1_000, 2_000, 1_000, "Show", null, seriesId = 3), trim = true)
        waitForUploads(1)
        b.sync.finish(always = true)
        b.vod.forgetEpisode(b.pid, 9)
        b.sync.finish()
        a.sync.finish(always = true)
        assertNull(a.progress(VOD_EPISODE, 9))
    }

    @Test
    fun `a series forgotten on one TV goes on the other, with episodes only that TV had`() = runBlocking {
        a.signIn()
        b.signIn()
        fun episode(pid: Long, id: Long, at: Long) = WatchProgressEntity(pid, VOD_EPISODE, id, 1_000, 2_000, at, "Show", null, seriesId = 3)
        a.vod.saveProgress(episode(a.pid, 1, 1_000), trim = true)
        a.sync.finish()
        b.sync.finish(always = true)
        // Episode 4 is watched on B only, and A has not synced since.
        b.vod.saveProgress(episode(b.pid, 4, 2_000))
        b.sync.finish()

        a.vod.forgetProgress(a.progress(VOD_EPISODE, 1)!!)
        a.sync.finish()
        b.sync.finish(always = true)
        assertTrue(withContext(Dispatchers.IO) { b.db.vod().seriesProgress(b.pid, 3) }.isEmpty())
        // And B's episode 4 does not come back to A.
        a.sync.finish(always = true)
        assertNull(a.progress(VOD_EPISODE, 4))
    }

    @Test
    fun `a category unticked on one TV is unticked on the other, which downloads its lists again`() = runBlocking {
        a.loadCategories()
        b.loadCategories()
        a.signIn()
        b.signIn()
        a.now = NOW + 5_000
        a.choose("2", false)
        a.sync.finish(always = true)
        b.sync.finish(always = true)
        assertEquals(listOf("1"), b.included())
        assertEquals(setOf(CAT_LIVE), b.reloaded[b.pid]?.types)

        // A newer choice on B goes back to A.
        b.now = NOW + 6_000
        b.choose("2", true)
        b.sync.finish()
        a.sync.finish(always = true)
        assertEquals(listOf("1", "2"), a.included())
        // A's file now has the same choice: B has it already, so nothing downloads again.
        b.reloaded.clear()
        a.sync.changed(stopped = false)
        a.sync.finish()
        b.sync.finish(always = true)
        assertEquals(listOf("1", "2"), b.included())
        assertNull(b.reloaded[b.pid])
    }

    @Test
    fun `a category choice reaches a TV that loads its categories later`() = runBlocking {
        a.loadCategories()
        a.signIn()
        b.signIn()
        a.choose("2", false)
        a.sync.finish(always = true)
        b.sync.finish(always = true)
        b.loadCategories()
        b.sync.finish(always = true)
        assertEquals(listOf("1"), b.included())
    }

    @Test
    fun `favourites, recently watched and hidden channels go to the other TV, and a removal too`() = runBlocking {
        a.loadChannels()
        b.loadChannels()
        a.signIn()
        b.signIn()
        a.channels.setFavourite(a.pid, 101, true)
        a.vod.setFavourite(a.pid, CAT_MOVIE, 501, true)
        a.channels.markWatched(a.pid, 102)
        a.channels.setHidden(a.pid, 102, true)
        a.sync()
        b.sync()
        assertEquals(listOf(101L), b.favourites())
        assertEquals(listOf(501L), b.db.backup().vodFavourites().map { it.itemId })
        assertEquals(listOf(102L), b.db.channels().idsRecent(b.pid))
        assertTrue(b.db.channels().get(b.pid, 102)!!.hidden)

        b.now = NOW + 2_000
        b.channels.setFavourite(b.pid, 101, false)
        b.channels.setHidden(b.pid, 102, false)
        b.sync()
        a.sync()
        assertEquals(emptyList<Long>(), a.favourites())
        assertFalse(a.db.channels().get(a.pid, 102)!!.hidden)
    }

    @Test
    fun `favourites from before this version go to the other TV, and a hidden channel waits for its channel`() = runBlocking {
        a.loadChannels()
        a.db.favourites().add(FavouriteEntity(a.pid, 102, 500, 1))
        a.channels.setHidden(a.pid, 101, true)
        a.signIn()
        b.signIn()
        a.sync()
        b.sync()
        assertEquals(listOf(102L), b.favourites())
        // B loads its channels later: the next sync hides 101 there.
        b.loadChannels()
        b.sync()
        assertTrue(b.db.channels().get(b.pid, 101)!!.hidden)
    }

    @Test
    fun `restricted categories, guides and guide channels go to the other TV`() = runBlocking {
        a.loadCategories()
        b.loadCategories()
        a.loadChannels()
        b.loadChannels()
        a.signIn()
        b.signIn()
        a.parental.setRestricted(a.pid, CAT_LIVE, "2", true)
        val guide = a.epg.addSource("My guide", "http://guide.example/tv.xml", a.pid)
        a.epg.setManual(a.pid, 101, guide, "one.uk", offsetMinutes = 60)
        a.sync()
        b.sync()
        assertTrue(b.db.categories().get(b.pid, CAT_LIVE, "2")!!.restricted)
        val source = b.db.epg().sourceByUrl("http://guide.example/tv.xml")!!
        assertEquals("My guide", source.name)
        assertEquals(listOf(source.id), b.db.epg().sourcesFor(listOf(b.pid)).map { it.id })
        val mapping = b.db.epg().mapping(b.pid, 101)!!
        assertEquals(listOf(source.id, "one.uk", 60, true), listOf(mapping.sourceId, mapping.xmlId, mapping.offsetMinutes, mapping.manual))
        assertTrue(b.reloaded[b.pid]!!.guides)
    }

    @Test
    fun `with a PIN set, another TV cannot lift a restriction`() = runBlocking {
        a.loadCategories()
        b.loadCategories()
        a.signIn()
        b.signIn()
        a.parental.setRestricted(a.pid, CAT_LIVE, "2", true)
        a.sync()
        b.sync()
        // The TVs share the settings here, so A has the PIN too.
        SettingsRepository(context).update { it.copy(pinHash = "pin") }
        b.now = NOW + 1_000
        b.parental.setRestricted(b.pid, CAT_LIVE, "2", false)
        b.sync()
        a.sync()
        assertTrue(a.db.categories().get(a.pid, CAT_LIVE, "2")!!.restricted)
    }

    @Test
    fun `a favourite from another TV goes at the end of this TV's order`() = runBlocking {
        a.signIn()
        b.signIn()
        a.channels.setFavourite(a.pid, 101, true)
        a.channels.setFavourite(a.pid, 102, true)
        b.channels.setFavourite(b.pid, 103, true)
        b.sync()
        a.sync()
        assertEquals(
            listOf(1L, 2L, 3L),
            listOf(101L, 102L, 103L).map { id ->
                a.db.backup().favourites().first { it.streamId == id }.position
            }
        )
    }

    @Test
    fun `changes not sent before the app closed are sent after it opens again`() = runBlocking {
        a.signIn()
        folder.failing = true
        a.vod.saveProgress(film(a.pid, 600_000, 1_000))
        folder.failing = false
        withTimeout(WAIT_MS) { while (!a.sync.account.state.first().pending) delay(10) }
        // A new process: the same saved account, a new sync.
        val again = ProgressSync(a.db, a.sync.account, null, scope, { folder })
        withTimeout(WAIT_MS) { while (!again.on) delay(10) }
        again.finish()
        assertEquals(1, folder.uploads.get())
        assertFalse(a.sync.account.state.first().pending)
    }

    @Test
    fun `after an update to the playlist keys, each TV sends its file again once`() = runBlocking {
        a.signIn()
        a.sync.finish(always = true)
        assertFalse(a.sync.account.state.first().pending)
        // As before 1.10.2: no key format is kept.
        PreferenceDataStoreFactory.create(scope = scope) { context.filesDir.resolve("sync-old.preferences_pb") }.let { old ->
            val account = SyncAccount(old)
            account.signIn("refresh", null)
            assertTrue(account.state.first().pending)
            val again = ProgressSync(a.db, account, null, scope, { folder })
            withTimeout(WAIT_MS) { while (!again.on) delay(10) }
            val uploads = folder.uploads.get()
            again.finish()
            assertEquals(uploads + 1, folder.uploads.get())
            assertFalse(account.state.first().pending)
        }
    }

    @Test
    fun `an older place does not replace a newer one`() = runBlocking {
        a.signIn()
        b.signIn()
        b.vod.saveProgress(film(b.pid, 900_000, 5_000))
        a.vod.saveProgress(film(a.pid, 600_000, 1_000), trim = true)
        waitForUploads(1)
        b.sync.finish(always = true)
        assertEquals(900_000L, b.progress(VOD_MOVIE, 501)?.positionMs)
    }

    @Test
    fun `progress of a playlist the other TV does not have is left out`() = runBlocking {
        a.signIn()
        b.signIn()
        b.db.playlists().delete(b.pid)
        a.vod.saveProgress(film(a.pid, 600_000, 1_000), trim = true)
        waitForUploads(1)
        b.sync.finish(always = true)
        assertTrue(withContext(Dispatchers.IO) { b.db.backup().progress() }.isEmpty())
    }

    @Test
    fun `a failed sync keeps the changes for the next one`() = runBlocking {
        a.signIn()
        folder.failing = true
        a.vod.saveProgress(film(a.pid, 600_000, 1_000), trim = true)
        withTimeout(WAIT_MS) { while (a.sync.failure.value == null) delay(10) }
        assertTrue(a.sync.hasChanges)
        folder.failing = false
        a.sync.finish()
        assertEquals(1, folder.uploads.get())
        assertFalse(a.sync.hasChanges)
        assertNull(a.sync.failure.value)
    }

    @Test
    fun `saves during playback only mark a change, and stopping syncs`() = runBlocking {
        a.signIn()
        a.vod.saveProgress(film(a.pid, 60_000, 1_000))
        delay(100)
        assertEquals(0, folder.calls.get())
        assertTrue(a.sync.hasChanges)
        a.vod.saveProgress(film(a.pid, 70_000, 2_000), trim = true)
        waitForUploads(1)
    }

    @Test
    fun `a sync asked for while one runs is dropped, not queued`() = runBlocking {
        a.signIn()
        folder.gate = CompletableDeferred()
        a.sync.syncSoon(force = true)
        withTimeout(WAIT_MS) { while (folder.calls.get() == 0) delay(10) }
        a.sync.syncSoon(force = true)
        a.vod.saveProgress(film(a.pid, 600_000, 1_000), trim = true)
        folder.gate!!.complete(Unit)
        // The running sync sends the change; no second sync starts.
        waitForUploads(1)
        delay(200)
        assertEquals(1, folder.lists.get())
    }

    @Test
    fun `stop syncing deletes this TV's file and sends nothing more`() = runBlocking {
        a.signIn()
        a.vod.saveProgress(film(a.pid, 600_000, 1_000), trim = true)
        waitForUploads(1)
        a.sync.stop().join()
        assertTrue(folder.files.isEmpty())
        assertNull(a.sync.account.state.first().refreshToken)
        val calls = folder.calls.get()
        a.vod.saveProgress(film(a.pid, 700_000, 2_000), trim = true)
        a.sync.finish(always = true)
        delay(100)
        assertEquals(calls, folder.calls.get())
    }

    @Test
    fun `while off, nothing is sent, read or kept for it`() = runBlocking {
        a.vod.saveProgress(film(a.pid, 600_000, 1_000), trim = true)
        a.vod.forgetProgress(film(a.pid, 600_000, 1_000))
        a.sync.syncSoon(force = true)
        a.sync.finish(always = true)
        delay(100)
        assertEquals(0, folder.calls.get())
        assertFalse(a.sync.hasChanges)
        assertTrue(withContext(Dispatchers.IO) { a.db.vod().tombstones() }.isEmpty())
    }

    /** The sync folder: files by id. */
    private class FakeFolder : SyncStore {
        class File(val name: String, val content: ByteArray, val version: Int)

        val files = ConcurrentHashMap<String, File>()
        val uploads = AtomicInteger()
        val calls = AtomicInteger()
        val lists = AtomicInteger()
        private val ids = AtomicInteger()

        @Volatile var failing = false

        /** While set, list() waits for it. */
        @Volatile var gate: CompletableDeferred<Unit>? = null

        private fun call() {
            calls.incrementAndGet()
            if (failing) throw SyncException("offline")
        }

        override suspend fun list(): List<SyncFile> {
            call()
            lists.incrementAndGet()
            gate?.await()
            return files.map { (id, f) -> SyncFile(id, f.name, f.version.toString()) }
        }

        override suspend fun download(id: String) = call().let { files.getValue(id).content }

        override suspend fun create(name: String, content: ByteArray): String {
            call()
            val id = ids.incrementAndGet().toString()
            files[id] = File(name, content, 1)
            uploads.incrementAndGet()
            return id
        }

        override suspend fun update(id: String, content: ByteArray) {
            call()
            files[id] = File(files.getValue(id).name, content, files.getValue(id).version + 1)
            uploads.incrementAndGet()
        }

        override suspend fun delete(id: String) {
            call()
            files.remove(id)
        }
    }

    private companion object {
        const val WAIT_MS = 5_000L
        val NOW = System.currentTimeMillis()
    }
}
