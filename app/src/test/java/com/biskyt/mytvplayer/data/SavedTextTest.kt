package com.biskyt.mytvplayer.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.AppText
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
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Only the app's own text is translated, and never saved: saved names stay as they are whatever the language, and the
 * app's words about saved things (a guide's last error) follow the language when shown.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = PlainApp::class)
class SavedTextTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val provider = FakeProvider()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    private val epg = EpgRepository(db, OkHttpClient(), context.cacheDir.resolve("test-epg"), scope)
    private val playlists = PlaylistRepository(
        db,
        OkHttpClient(),
        SettingsRepository(context),
        epg,
        context.cacheDir.resolve("test-playlists"),
        CategoryStore(db) { Locale.UK }
    )

    @After
    fun tearDown() {
        AppText.setLanguage(context, "")
        scope.cancel()
        db.close()
        provider.close()
    }

    private suspend fun names() =
        db.playlists().all().map { it.name } + db.epg().sourcesFor(db.playlists().all().map { it.id }).map { it.name }

    @Test
    fun `saved playlist and guide names stay as they are when the language changes`() = runBlocking {
        val id = playlists.add("", provider.url, "me", "secret")
        playlists.syncChannels(id)
        assertEquals(listOf("My TV", "My TV guide"), names())

        AppText.setLanguage(context, "es")
        playlists.syncChannels(id)
        epg.importAll(listOf(id))
        assertEquals(listOf("My TV", "My TV guide"), names())
    }

    @Test
    fun `a guide's last error is saved as what failed, and read in the language shown`() = runBlocking {
        provider.guideFails = true
        val id = playlists.add("Home", provider.url, "me", "secret")
        playlists.syncChannels(id)
        epg.importAll(listOf(id))
        val saved = db.epg().sourcesFor(listOf(id)).single().lastError!!
        assertFalse(saved.contains("error"))
        assertEquals("Server returned error 500", GuideError.text(saved))
        AppText.setLanguage(context, "es")
        assertEquals("El servidor devolvió el error 500", GuideError.text(saved))
        // Anything else (a server's or a library's words) is kept and shown as it came.
        assertEquals("Connection reset", GuideError.text("Connection reset"))
    }

    @Test
    fun `nameless things are named by the app when shown, in its language`() {
        AppText.setLanguage(context, "fr")
        assertEquals("Sans titre", programmeTitle(""))
        assertEquals("Line of Duty", programmeTitle("Line of Duty"))
        assertEquals("Sans catégorie", categoryLabel(""))
        assertEquals("News", categoryLabel("News"))
        assertEquals(" ", categoryLabel(" ")) // a playlist's own group of spaces: not the one with none
    }
}
