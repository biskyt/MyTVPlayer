package com.biskyt.mytvplayer.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.data.db.AppDatabase
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.PlainApp
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A guide's programmes are read and written at the same time, in batches: every programme is stored once, also when
 * read again, and how long each step took is kept for the guide sources screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = PlainApp::class)
class GuideImportTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val provider = FakeProvider().apply { laterHours = 40 }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()

    // Small batches, so that many pass from the reading to the writing.
    private val epg = EpgRepository(db, OkHttpClient(), context.cacheDir.resolve("import-epg"), scope, programmeBatch = 7)
    private val playlists = PlaylistRepository(
        db,
        OkHttpClient(),
        SettingsRepository(context),
        epg,
        context.cacheDir.resolve("import-playlists"),
        CategoryStore(db) { Locale.UK }
    )

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        provider.close()
    }

    @Test
    fun `every programme is stored once, in batches, also when the guide is read again`() = runBlocking {
        val id = playlists.add("Home", provider.url, "me", "secret")
        playlists.syncChannels(id)
        epg.importAll(listOf(id))
        val now = System.currentTimeMillis()
        // Now, next and 40 later ones.
        val first = epg.programmes(id, listOf(101), now - 3_600_000, now + 50 * 3_600_000).getValue(101)
        assertEquals(42, first.size)
        assertEquals(first.size, first.distinctBy { it.startMs }.size)

        epg.importAll(listOf(id))
        val again = epg.programmes(id, listOf(101), now - 3_600_000, now + 50 * 3_600_000).getValue(101)
        assertEquals(first.map { it.startMs to it.title }, again.map { it.startMs to it.title })
    }

    @Test
    fun `an update keeps how long each of its steps took`() = runBlocking {
        val id = playlists.add("Home", provider.url, "me", "secret")
        playlists.syncChannels(id)
        epg.importAll(listOf(id))
        val source = db.epg().sourcesFor(listOf(id)).single()
        val t = epg.times.value.getValue(source.id)
        assertNotNull(t.download)
        assertNotNull(t.channels)
        assertNotNull(t.matching)
        assertNotNull(t.programmes)
        assertNotNull(t.saving)
    }
}
