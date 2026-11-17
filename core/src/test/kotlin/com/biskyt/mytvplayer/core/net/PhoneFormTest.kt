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
    fun `a request for HTTPS gets an answer at once, so that the browser opens the http address`() {
        java.net.Socket(InetAddress.getLoopbackAddress(), java.net.URI(form.url).port).use { s ->
            s.soTimeout = 1_000
            // The start of a TLS ClientHello, with no line end in it.
            s.getOutputStream().apply {
                write(byteArrayOf(0x16, 0x03, 0x01, 0x02, 0x00, 0x01, 0x00, 0x01, 0xfc.toByte(), 0x03, 0x03))
                flush()
            }
            val answer = s.getInputStream().bufferedReader(Charsets.US_ASCII).readLine()
            assertEquals("HTTP/1.1 400 Bad Request", answer)
        }
        get(form.url).use { assertEquals(200, it.code) }
    }

    @Test
    fun `the server counts connections, pages, HTTPS tries and connections with no answer`() {
        get(form.url).use { assertEquals(200, it.code) }
        val port = java.net.URI(form.url).port
        java.net.Socket(InetAddress.getLoopbackAddress(), port).use { s ->
            s.getOutputStream().apply {
                write(byteArrayOf(0x16, 0x03, 0x01))
                flush()
            }
            s.getInputStream().read()
        }
        // A connection the browser closes before it asks anything.
        java.net.Socket(InetAddress.getLoopbackAddress(), port).close()
        val deadline = System.currentTimeMillis() + 5_000
        while (form.seen.value.unanswered < 1 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(PhoneFormSeen(connections = 3, pages = 1, secure = 1, unanswered = 1), form.seen.value)
    }

    /** The status line and headers of the next answer on [input], and its body (by Content-Length). */
    private fun answer(input: java.io.BufferedInputStream): Pair<List<String>, String> {
        fun line(): String {
            val out = java.io.ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                check(b >= 0) { "closed" }
                if (b == '\n'.code) return out.toString(Charsets.US_ASCII.name()).trimEnd('\r')
                out.write(b)
            }
        }
        val head = generateSequence { line().takeIf { it.isNotEmpty() } }.toList()
        val length = head.first { it.startsWith("Content-Length:") }.substringAfter(':').trim().toInt()
        return head to String(ByteArray(length).also { input.readNBytes(it, 0, length) }, Charsets.UTF_8)
    }

    @Test
    fun `a connection stays open for the browser's next request, as HTTP 1·1 expects`() {
        val path = java.net.URI(form.url).path
        java.net.Socket(InetAddress.getLoopbackAddress(), java.net.URI(form.url).port).use { s ->
            s.soTimeout = 5_000
            val input = java.io.BufferedInputStream(s.getInputStream())
            s.getOutputStream().write("GET $path HTTP/1.1\r\nHost: tv\r\n\r\n".toByteArray())
            val (head, page) = answer(input)
            assertEquals("HTTP/1.1 200 OK", head.first())
            assertTrue(head.contains("Connection: keep-alive"))
            assertTrue(page.contains("<form"))
            // A while later (as a browser after the user types the key), the form is sent on the same connection.
            Thread.sleep(2_500)
            val body = "value=abc"
            s.getOutputStream().write(
                "POST $path HTTP/1.1\r\nHost: tv\r\nContent-Length: ${body.length}\r\n\r\n$body".toByteArray()
            )
            assertEquals("HTTP/1.1 200 OK", answer(input).first.first())
            assertEquals("abc", received.poll(5, TimeUnit.SECONDS))
            // The icon, the third request on it.
            s.getOutputStream().write("GET /favicon.ico HTTP/1.1\r\nHost: tv\r\n\r\n".toByteArray())
            assertEquals("HTTP/1.1 404 Not Found", answer(input).first.first())
        }
        assertEquals(0, form.seen.value.unanswered)
    }

    @Test
    fun `a browser that asks for the connection to close gets that`() {
        val path = java.net.URI(form.url).path
        java.net.Socket(InetAddress.getLoopbackAddress(), java.net.URI(form.url).port).use { s ->
            s.soTimeout = 5_000
            val input = java.io.BufferedInputStream(s.getInputStream())
            s.getOutputStream().write("GET $path HTTP/1.1\r\nHost: tv\r\nConnection: close\r\n\r\n".toByteArray())
            assertTrue(answer(input).first.contains("Connection: close"))
            assertEquals(-1, input.read())
        }
    }

    @Test
    fun `OkHttp sends its requests on one connection`() {
        val pool = okhttp3.ConnectionPool()
        val client = OkHttpClient.Builder().connectionPool(pool).build()
        repeat(3) { client.newCall(Request.Builder().url(form.url).build()).execute().use { r -> assertEquals(200, r.code) } }
        assertEquals(1, pool.connectionCount())
        assertEquals(1, form.seen.value.connections)
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

    @Test
    fun `a connection opened early gets its answer, and does not hold up another request`() {
        // As a phone's browser does: it opens a connection, and sends its request on it a few seconds later.
        val early = java.net.Socket(InetAddress.getLoopbackAddress(), java.net.URI(form.url).port)
        early.use {
            // Not held up by the silent one: well under the old server's 2 s wait for it.
            val start = System.currentTimeMillis()
            get(form.url).use { r -> assertEquals(200, r.code) }
            assertTrue(System.currentTimeMillis() - start < 1_000)
            Thread.sleep(3_000)
            val path = java.net.URI(form.url).path
            it.getOutputStream().write("GET $path HTTP/1.1\r\nHost: tv\r\n\r\n".toByteArray())
            val reply = it.getInputStream().bufferedReader().readLine()
            assertEquals("HTTP/1.1 200 OK", reply)
        }
    }

    @Test
    fun `silent connections that fill every place make room for a request`() {
        val port = java.net.URI(form.url).port
        val silent = List(PhoneForm.MAX_CLIENTS) { java.net.Socket(InetAddress.getLoopbackAddress(), port) }
        try {
            Thread.sleep(200) // all accepted
            get(form.url).use { r -> assertEquals(200, r.code) }
        } finally {
            silent.forEach { it.close() }
        }
    }
}
