package com.biskyt.mytvplayer.core.net

import java.io.IOException
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class IpLookupTest {
    private val server = MockWebServer()
    private var now = 0L

    @Before
    fun setUp() {
        server.start()
        IpLookup.forgetResting()
    }

    @After
    fun tearDown() {
        server.shutdown()
        IpLookup.forgetResting()
    }

    /** The app's services, each at its own path on [server]. */
    private fun lookup() = IpLookup(
        OkHttpClient(),
        IpLookup.SERVICES.mapIndexed { i, s -> IpLookup.Service(s.name, server.url("/$i").toString(), s.read) },
        clock = { now }
    )

    private fun answer(code: Int, body: String = "") = server.enqueue(MockResponse().setResponseCode(code).setBody(body))

    @Test
    fun `reads the address and the country`() = runTest {
        answer(200, """{"ip":"185.12.34.56","city":"Amsterdam","country":"NL","org":"AS1 Example"}""")
        assertEquals(PublicIp("185.12.34.56", "NL"), lookup().lookup())
    }

    @Test
    fun `an address without a country is still read`() = runTest {
        answer(200, """{"ip":"2001:db8::1"}""")
        assertEquals(PublicIp("2001:db8::1"), lookup().lookup())
    }

    @Test
    fun `when ipinfo is busy, the next service answers, and ipinfo is left alone for a while`() = runTest {
        answer(429)
        answer(200, """{"ip":"185.12.34.56","success":true,"country":"Netherlands","country_code":"NL"}""")
        assertEquals(PublicIp("185.12.34.56", "NL"), lookup().lookup())
        assertEquals("/0", server.takeRequest().path)
        assertEquals("/1", server.takeRequest().path)

        // Asked again soon: straight to the next one.
        answer(200, """{"ip":"185.12.34.57","success":true,"country_code":"NL"}""")
        assertEquals("185.12.34.57", lookup().lookup().ip)
        assertEquals("/1", server.takeRequest().path)

        // An hour later, ipinfo is asked first again.
        now += 61 * 60_000L
        answer(200, """{"ip":"185.12.34.58","country":"NL"}""")
        assertEquals("185.12.34.58", lookup().lookup().ip)
        assertEquals("/0", server.takeRequest().path)
    }

    @Test
    fun `with every service failing after ipinfo said 429, it says so, not that the network failed`() = runTest {
        answer(429)
        answer(500)
        answer(503)
        val e = runCatching { lookup().lookup() }.exceptionOrNull()
        assertTrue(e is IpLookupLimited)
        assertEquals("ipinfo.io answered 429 (too many requests)", e!!.message)
    }

    @Test(expected = IOException::class)
    fun `answers that are not JSON fail`() = runTest {
        repeat(3) { answer(200, "<html>") }
        lookup().lookup()
    }
}
