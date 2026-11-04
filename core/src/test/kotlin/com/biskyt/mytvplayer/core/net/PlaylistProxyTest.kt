package com.biskyt.mytvplayer.core.net

import java.io.DataInputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PlaylistProxyTest {
    private val proxy = MockWebServer()
    private val base = OkHttpClient()

    @Before
    fun setUp() = proxy.start()

    @After
    fun tearDown() = proxy.shutdown()

    private fun get(client: OkHttpClient, url: String) =
        client.newCall(Request.Builder().url(url).build()).execute().use { it.body!!.string() }

    @Test
    fun `an HTTP proxy gets each request, with its login after it asks for one`() {
        proxy.enqueue(MockResponse().setResponseCode(407).setHeader("Proxy-Authenticate", "Basic realm=\"p\""))
        proxy.enqueue(MockResponse().setBody("through the proxy"))
        val settings = ProxySettings(ProxyType.HTTP, proxy.hostName, proxy.port, "me", "secret")
        assertEquals("through the proxy", get(ProxyClients(base).forProxy(settings), "http://provider.example/player_api.php"))
        val first = proxy.takeRequest()
        // A proxy gets the whole address of the provider.
        assertEquals("GET http://provider.example/player_api.php HTTP/1.1", first.requestLine)
        assertEquals("Basic bWU6c2VjcmV0", proxy.takeRequest().getHeader("Proxy-Authorization"))
    }

    @Test
    fun `no proxy, or one with no address or a bad port, is the plain client, and each proxy has one client`() {
        val clients = ProxyClients(base)
        assertSame(base, clients.forProxy(null))
        assertSame(base, clients.forProxy(ProxySettings(ProxyType.HTTP, " ", 8080)))
        assertSame(base, clients.forProxy(ProxySettings(ProxyType.HTTP, "proxy", 0)))
        val p = ProxySettings(ProxyType.SOCKS, "proxy", 1080)
        assertSame(clients.forProxy(p), clients.forProxy(p.copy()))
        assertFalse(ProxySettings(ProxyType.HTTP, "proxy", 70000).valid)
    }

    @Test
    fun `a SOCKS5 proxy gets the connection, with its user name and password`() {
        val target = MockWebServer()
        target.enqueue(MockResponse().setBody("hello"))
        target.start()
        val socks = Socks5Server("me", "secret")
        try {
            val client = ProxyClients(base).forProxy(ProxySettings(ProxyType.SOCKS, "127.0.0.1", socks.port, "me", "secret"))
            assertEquals("hello", get(client, target.url("/").toString()))
            assertTrue("the SOCKS server was used, and checked the password", socks.authenticated)
        } finally {
            socks.close()
            target.shutdown()
        }
    }

    @Test
    fun `a typed proxy loses its scheme and path, and host colon port fills in the port`() {
        assertEquals(
            ProxySettings(ProxyType.HTTP, "10.0.0.5", 3128),
            ProxySettings.typed(ProxyType.HTTP, " http://10.0.0.5/ ", "3128", "", "")
        )
        assertEquals(
            ProxySettings(ProxyType.SOCKS, "proxy.example", 1080, "me"),
            ProxySettings.typed(ProxyType.SOCKS, "socks5://proxy.example:1080", "", " me ", "")
        )
        assertFalse(ProxySettings.typed(ProxyType.HTTP, "proxy example", "8080", "", "").valid)
        assertFalse(ProxySettings.typed(ProxyType.HTTP, "proxy.example", "", "", "").valid)
    }

    @Test
    fun `the check tells a working proxy, one that changes nothing, and one that fails`() = runTest {
        val home = PublicIp("1.1.1.1", "GB")
        val away = PublicIp("185.12.34.56", "NL")
        assertEquals(ProxyCheck.Working(away, home), ProxyCheck.run({ home }, { away }))
        assertEquals(ProxyCheck.SameAddress(home), ProxyCheck.run({ home }, { home }))
        assertEquals(ProxyCheck.Failed("refused"), ProxyCheck.run({ home }, { throw IOException("refused") }))
        // Without a direct answer, the proxy's address still counts.
        assertEquals(ProxyCheck.Working(away, null), ProxyCheck.run({ throw IOException("offline") }, { away }))
    }

    /** A SOCKS5 server for one connection that needs a user name and password (RFC 1928, RFC 1929). */
    private class Socks5Server(private val user: String, private val pass: String) {
        private val server = ServerSocket(0)
        val port: Int get() = server.localPort

        @Volatile var authenticated = false

        init {
            thread(isDaemon = true) {
                runCatching { server.accept().use(::serve) }
            }
        }

        private fun serve(client: Socket) {
            val input = DataInputStream(client.getInputStream())
            val output = client.getOutputStream()
            check(input.readByte().toInt() == 5)
            val methods = ByteArray(input.readByte().toInt()).also(input::readFully)
            if (2.toByte() !in methods) return output.write(byteArrayOf(5, 0xFF.toByte()))
            output.write(byteArrayOf(5, 2))
            check(input.readByte().toInt() == 1)
            val u = String(ByteArray(input.readUnsignedByte()).also(input::readFully))
            val p = String(ByteArray(input.readUnsignedByte()).also(input::readFully))
            authenticated = u == user && p == pass
            output.write(byteArrayOf(1, if (authenticated) 0 else 1))
            if (!authenticated) return
            // CONNECT: version, command, reserved, address type, address, port.
            input.readFully(ByteArray(3))
            val host = when (input.readByte().toInt()) {
                1 -> ByteArray(4).also(input::readFully).joinToString(".") { (it.toInt() and 0xFF).toString() }
                3 -> String(ByteArray(input.readUnsignedByte()).also(input::readFully))
                else -> error("address type")
            }
            val targetPort = input.readUnsignedShort()
            Socket(host, targetPort).use { target ->
                output.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
                val up = thread(isDaemon = true) { runCatching { input.copyTo(target.getOutputStream()) } }
                runCatching { target.getInputStream().copyTo(output) }
                up.join(1000)
            }
        }

        fun close() = server.close()
    }
}
