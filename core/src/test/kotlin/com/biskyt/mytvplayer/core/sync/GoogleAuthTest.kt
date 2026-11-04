package com.biskyt.mytvplayer.core.sync

import java.util.Base64
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class) // testScheduler.currentTime: the virtual clock
class GoogleAuthTest {
    private val server = MockWebServer()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.shutdown()

    private fun auth(clock: () -> Long = { 0L }) =
        GoogleAuth(OkHttpClient(), "client", "secret", server.url("").toString().trimEnd('/'), clock)

    private fun error(code: String, status: Int = 428) = MockResponse().setResponseCode(status).setBody("""{"error":"$code"}""")

    private val code = DeviceCode("dev", "ABC-DEF", "https://www.google.com/device", 1800, 5)

    @Test
    fun `start asks for the Drive app folder and the email address`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"device_code":"dev","user_code":"ABC-DEF","verification_url":"https://www.google.com/device","expires_in":1800,"interval":5}"""
            )
        )
        assertEquals(code, auth().start())
        val body = server.takeRequest().body.readUtf8()
        // Google refuses drive.appdata for a TV sign-in (invalid_scope): only drive.file.
        assertTrue(body, body.contains("scope=openid%20email%20https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fdrive.file"))
        assertTrue(body, body.contains("client_id=client"))
    }

    @Test
    fun `a refused start says Google's reason`() = runTest {
        server.enqueue(error("invalid_scope", 400))
        try {
            auth().start()
            fail("No exception")
        } catch (e: SyncException) {
            assertEquals("invalid_scope", e.code)
        }
    }

    @Test
    fun `await waits while pending, slows down when told, and reads the tokens and email`() = runTest {
        val idToken = "x." + Base64.getUrlEncoder().withoutPadding().encodeToString("""{"email":"a@example.com"}""".toByteArray()) + ".y"
        server.enqueue(error("authorization_pending"))
        server.enqueue(error("slow_down", 403))
        server.enqueue(MockResponse().setBody("""{"access_token":"at","expires_in":3600,"refresh_token":"rt","id_token":"$idToken"}"""))
        val tokens = auth { testScheduler.currentTime }.await(code)
        assertEquals(GoogleTokens("at", 5000L + 5000 + 10000 + 3_600_000, "rt", "a@example.com"), tokens)
        // 5 s, 5 s, then 10 s after the slow down.
        assertEquals(20_000L, testScheduler.currentTime)
        assertTrue(server.takeRequest().body.readUtf8().contains("device_code=dev"))
    }

    @Test
    fun `await reports a refusal and an expired code`() = runTest {
        server.enqueue(error("access_denied", 403))
        assertReason(SyncException.Reason.DENIED) { auth { testScheduler.currentTime }.await(code) }
        server.enqueue(error("expired_token", 403))
        assertReason(SyncException.Reason.EXPIRED) { auth { testScheduler.currentTime }.await(code) }
    }

    @Test
    fun `await stops when the code runs out of time`() = runTest {
        repeat(4) { server.enqueue(error("authorization_pending")) }
        assertReason(SyncException.Reason.EXPIRED) {
            auth { testScheduler.currentTime }.await(code.copy(expiresInSec = 12))
        }
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `refresh keeps the refresh token, and a removed access signs out`() = runTest {
        server.enqueue(MockResponse().setBody("""{"access_token":"at2","expires_in":60}"""))
        val tokens = auth { 1000L }.refresh("rt")
        assertEquals(GoogleTokens("at2", 61_000L, "rt", null), tokens)
        assertTrue(server.takeRequest().body.readUtf8().contains("grant_type=refresh_token"))
        server.enqueue(error("invalid_grant", 400))
        assertReason(SyncException.Reason.SIGNED_OUT) { auth().refresh("rt") }
    }

    @Test
    fun `the QR address has the code filled in`() {
        assertEquals("https://www.google.com/device?user_code=ABC-DEF", code.urlWithCode)
        // An address that is not one stays as it is: never a crash.
        assertEquals("google.com/device", code.copy(verificationUrl = "google.com/device").urlWithCode)
    }

    @Test
    fun `email is null for a token without one`() {
        assertNull(GoogleAuth.email("nonsense"))
        assertNull(GoogleAuth.email("a.e30.b"))
    }

    private suspend fun assertReason(reason: SyncException.Reason, block: suspend () -> Unit) {
        try {
            block()
            fail("No exception")
        } catch (e: SyncException) {
            assertEquals(reason, e.reason)
        }
    }
}
