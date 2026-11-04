package com.biskyt.mytvplayer.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.core.sync.SyncException
import com.biskyt.mytvplayer.core.sync.SyncFile
import com.biskyt.mytvplayer.core.sync.SyncStore
import com.biskyt.mytvplayer.data.db.AppDatabase
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
        val sync = ProgressSync(
            db,
            SyncAccount(PreferenceDataStoreFactory.create(scope = scope) { context.filesDir.resolve("sync-$name.preferences_pb") }),
            null,
            scope,
            { folder }
        )
        private val categoryStore = CategoryStore(db) { Locale.UK }
        private val http = OkHttpClient()
        private val playlists = PlaylistRepository(
            db,
            http,
            SettingsRepository(context),
            EpgRepository(db, http, context.cacheDir.resolve("epg-$name"), scope),
            context.cacheDir.resolve("playlists-$name"),
            categoryStore
        )
        val vod = VodRepository(db, playlists, categoryStore, sync)
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
    }
}
