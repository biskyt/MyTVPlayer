package com.biskyt.mytvplayer.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.core.net.ProxySettings
import com.biskyt.mytvplayer.core.net.ProxyType
import com.biskyt.mytvplayer.data.db.AppDatabase
import com.biskyt.mytvplayer.data.db.PlaylistEntity
import com.biskyt.mytvplayer.data.db.proxy
import com.biskyt.mytvplayer.data.db.withProxy
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.PlainApp
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A playlist with a proxy: its requests go to the proxy, which gets the provider's whole address. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = PlainApp::class)
class PlaylistProxyTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The fake provider stands in as the proxy: it answers the provider's requests that the proxy passes on. */
    private val proxy = FakeProvider()
    private lateinit var db: AppDatabase
    private lateinit var playlists: PlaylistRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val http = OkHttpClient()
        val epg = EpgRepository(db, http, context.cacheDir.resolve("proxy-epg"), scope)
        playlists = PlaylistRepository(
            db,
            http,
            SettingsRepository(context),
            epg,
            context.cacheDir.resolve("proxy-playlists"),
            CategoryStore(db) { Locale.UK }
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        proxy.close()
    }

    @Test
    fun `a playlist's login goes to its proxy, with the provider's whole address`() = runBlocking {
        val settings = ProxySettings(ProxyType.HTTP, proxy.server.hostName, proxy.server.port)
        // No such server: only the proxy can be asked. (The fake provider cannot read the login through a proxy.)
        runCatching { playlists.testLogin("http://provider.invalid:8080", "me", "secret", settings) }
        val first = checkNotNull(proxy.server.takeRequest(5, TimeUnit.SECONDS)) { "the proxy was not asked" }
        assertTrue(first.requestLine, first.requestLine.startsWith("GET http://provider.invalid:8080/player_api.php"))
    }

    @Test
    fun `a playlist keeps its proxy, with its login`() = runBlocking {
        val settings = ProxySettings(ProxyType.SOCKS, "proxy.example", 1080, "me", "secret")
        val id = db.playlists().insert(
            PlaylistEntity(name = "Home", server = "http://x", username = "u", password = "p").withProxy(settings)
        )
        assertEquals(settings, playlists.get(id)?.proxy)
        assertNull(playlists.get(id)?.withProxy(null)?.proxy)
    }

    @Test
    fun `an incomplete proxy counts as none`() = runBlocking {
        val id = playlists.add("Home", proxy.url, "me", "secret", ProxySettings(ProxyType.SOCKS, "", 1080))
        assertNull(playlists.get(id)?.proxy)
    }
}
