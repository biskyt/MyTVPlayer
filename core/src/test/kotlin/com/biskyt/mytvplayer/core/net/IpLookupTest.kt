package com.biskyt.mytvplayer.core.net

import java.io.IOException
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class IpLookupTest {
    private val server = MockWebServer()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.shutdown()

    private fun lookup() = IpLookup(OkHttpClient(), server.url("/json").toString())

    @Test
    fun `reads the address and the country`() = runTest {
        server.enqueue(MockResponse().setBody("""{"ip":"185.12.34.56","city":"Amsterdam","country":"NL","org":"AS1 Example"}"""))
        assertEquals(PublicIp("185.12.34.56", "NL"), lookup().lookup())
    }

    @Test
    fun `an address without a country is still read`() = runTest {
        server.enqueue(MockResponse().setBody("""{"ip":"2001:db8::1"}"""))
        assertEquals(PublicIp("2001:db8::1"), lookup().lookup())
    }

    @Test(expected = IOException::class)
    fun `a refusal fails`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429))
        lookup().lookup()
    }

    @Test(expected = IOException::class)
    fun `an answer that is not JSON fails`() = runTest {
        server.enqueue(MockResponse().setBody("<html>"))
        lookup().lookup()
    }
}
