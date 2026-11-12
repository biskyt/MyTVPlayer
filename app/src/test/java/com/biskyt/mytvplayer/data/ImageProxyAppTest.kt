package com.biskyt.mytvplayer.data

import androidx.test.core.app.ApplicationProvider
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.biskyt.mytvplayer.core.net.ProxySettings
import com.biskyt.mytvplayer.core.net.ProxyType
import com.biskyt.mytvplayer.data.db.withProxy
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.addHomeTv
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The app's image loader fetches logos and posters through the active playlist's proxy (the owner, 2026-11-12). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class)
class ImageProxyAppTest {
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private val provider = FakeProvider()

    /** Stands in for an HTTP proxy: it gets the image's whole address. */
    private val proxyServer = MockWebServer().apply { start() }

    @After
    fun tearDown() {
        provider.close()
        proxyServer.shutdown()
    }

    @Test
    fun `a logo is fetched through the active playlist's proxy`() = runBlocking<Unit> {
        val id = app.container.addHomeTv(provider)
        val playlist = app.container.playlists.get(id)!!
        app.container.db.playlists().upsert(playlist.withProxy(ProxySettings(ProxyType.HTTP, proxyServer.hostName, proxyServer.port)))
        proxyServer.enqueue(MockResponse().setResponseCode(404))
        val url = "http://logos.example/one.png"
        val request = ImageRequest.Builder(app).data(url)
            .memoryCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.DISABLED).build()
        SingletonImageLoader.get(app).execute(request)
        assertEquals("GET $url HTTP/1.1", proxyServer.takeRequest(5, TimeUnit.SECONDS)?.requestLine)
    }
}
