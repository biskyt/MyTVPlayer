package com.biskyt.mytvplayer.core.net

import java.net.InetAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneFormTest {
    private val received = LinkedBlockingQueue<String>()
    private val form =
        PhoneForm(InetAddress.getLoopbackAddress(), PhoneFormText("TMDB key", "Paste <your> key", "Send", "Sent to the TV")) {
            received.add(it)
        }
    private val http = OkHttpClient()

    @After
    fun tearDown() = form.close()

    private fun get(url: String) = http.newCall(Request.Builder().url(url).build()).execute()

    @Test
    fun `the page shows a form, and a value sent from it reaches the TV`() {
        get(form.url).use { r ->
            assertEquals(200, r.code)
            val page = r.body!!.string()
            assertTrue(page.contains("<form method=\"post\">"))
            assertTrue(page.contains("Paste &lt;your&gt; key")) // the app's words, escaped
        }
        val post = Request.Builder().url(form.url).post(FormBody.Builder().add("value", "  abc 123  ").build()).build()
        http.newCall(post).execute().use { r ->
            assertEquals(200, r.code)
            assertTrue(r.body!!.string().contains("Sent to the TV"))
        }
        assertEquals("abc 123", received.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun `only the address with the random part is served`() {
        val wrong = form.url.substringBeforeLast('/') + "/guess"
        get(wrong).use { assertEquals(404, it.code) }
        val post = Request.Builder().url(wrong).post(FormBody.Builder().add("value", "x").build()).build()
        http.newCall(post).execute().use { assertEquals(404, it.code) }
        assertNull(received.poll(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `a blank value is not passed on`() {
        val post = Request.Builder().url(form.url).post(FormBody.Builder().add("value", "   ").build()).build()
        http.newCall(post).execute().use { assertEquals(200, it.code) }
        assertNull(received.poll(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `after close, nothing is served`() {
        form.close()
        assertTrue(runCatching { get(form.url).close() }.isFailure)
    }

    @Test
    fun `a broken value, or a request too long, does not stop the server`() {
        val bad = Request.Builder().url(form.url).post(FormBody.Builder().addEncoded("value", "%zz").build()).build()
        http.newCall(bad).execute().use { assertEquals(200, it.code) }
        assertNull(received.poll(200, TimeUnit.MILLISECONDS))
        val huge = Request.Builder().url(form.url).header("X-Long", "a".repeat(20_000)).build()
        runCatching { http.newCall(huge).execute().close() }
        // Still serving.
        get(form.url).use { assertEquals(200, it.code) }
    }
}
