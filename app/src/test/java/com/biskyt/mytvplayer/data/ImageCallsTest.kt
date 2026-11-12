package com.biskyt.mytvplayer.data

import com.biskyt.mytvplayer.core.net.ProxyClients
import com.biskyt.mytvplayer.core.net.ProxySettings
import com.biskyt.mytvplayer.core.net.ProxyType
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Images go through the active playlist's proxy, and directly without one (the owner, 2026-11-12). */
class ImageCallsTest {
    // Unconfined: a change of the active playlist reaches ImageCalls before the next line runs.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    /** Stands in for the image host when reached directly. */
    private val host = MockWebServer().apply { start() }

    /** Stands in for an HTTP proxy: it gets the image's whole address. */
    private val proxyServer = MockWebServer().apply { start() }
    private val proxy = ProxySettings(ProxyType.HTTP, proxyServer.hostName, proxyServer.port)
    private val active = MutableSharedFlow<ProxySettings?>(replay = 1)
    private val calls = ImageCalls(scope, active, ProxyClients(OkHttpClient()))

    @After
    fun tearDown() {
        scope.cancel()
        host.shutdown()
        proxyServer.shutdown()
    }

    private fun fetch() {
        host.enqueue(MockResponse().setBody("direct"))
        proxyServer.enqueue(MockResponse().setBody("proxied"))
        calls.newCall(Request.Builder().url(host.url("/logo.png")).build()).execute().close()
    }

    @Test
    fun `without a proxy an image comes directly, and through the proxy once the active playlist has one`() {
        active.tryEmit(null)
        fetch()
        assertEquals("/logo.png", host.takeRequest(1, TimeUnit.SECONDS)?.path)
        assertNull(proxyServer.takeRequest(100, TimeUnit.MILLISECONDS))

        active.tryEmit(proxy)
        fetch()
        val viaProxy = proxyServer.takeRequest(1, TimeUnit.SECONDS)
        assertEquals("GET ${host.url("/logo.png")} HTTP/1.1", viaProxy?.requestLine)
        assertNull("not to the host directly", host.takeRequest(100, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `an image asked for before the active playlist is known waits for it, so it does not go out directly`() {
        // Nothing sent yet: the playlists are not read.
        host.enqueue(MockResponse().setBody("direct"))
        proxyServer.enqueue(MockResponse().setBody("proxied"))
        var failure: Throwable? = null
        val fetch = thread {
            runCatching { calls.newCall(Request.Builder().url(host.url("/logo.png")).build()).execute().close() }
                .onFailure { failure = it }
        }
        assertNull("it waits: nothing to the host directly", host.takeRequest(200, TimeUnit.MILLISECONDS))
        assertNull("it waits: nothing to the proxy yet", proxyServer.takeRequest(10, TimeUnit.MILLISECONDS))
        active.tryEmit(proxy)
        fetch.join(5_000)
        assertFalse("the fetch finished", fetch.isAlive)
        assertNull(failure)
        assertEquals("GET ${host.url("/logo.png")} HTTP/1.1", proxyServer.takeRequest(1, TimeUnit.SECONDS)?.requestLine)
        assertNull("not to the host directly", host.takeRequest(100, TimeUnit.MILLISECONDS))
    }
}
