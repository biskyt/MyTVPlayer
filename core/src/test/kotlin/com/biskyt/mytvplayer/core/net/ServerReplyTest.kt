package com.biskyt.mytvplayer.core.net

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What an error page says, as a browser shows it (the owner, 2026-11-14). */
class ServerReplyTest {
    private fun first(body: String) = ServerReply.firstLine(body)

    @Test
    fun `inline tags run on in the same line, block tags start a new one`() {
        assertEquals("Error 1020", first("<h1><span>Error</span> <span>1020</span></h1><p>Access denied</p>"))
        assertEquals("Forbidden: you do not have access", first("<p><b>Forbidden</b>: you do not have access</p>"))
    }

    @Test
    fun `the head, scripts and styles are not text, and a head may have no closing tag`() {
        assertEquals("Blocked", first("<head><title>x</title><style>p { color: red }</style><body><header>Blocked</header>"))
        assertEquals("Shown", first("<HEAD><script>var a = '<p>no</p>';</script></HEAD>\n<p>Shown</p>"))
        assertNull("an unclosed script hides the rest", first("<script>var a = 1;"))
    }

    @Test
    fun `entities are shown as the page shows them, and a login it repeats is hidden`() {
        assertEquals("You don't have <access> & \"rights\"", first("You don&#39;t have &lt;access&gt; &amp; &quot;rights&quot;"))
        assertEquals("&lt;", first("&amp;lt;"))
        assertEquals(
            "The URL /player_api.php?username=…&password=… was not found",
            first("<p>The URL /player_api.php?username=me&amp;password=secret was not found</p>")
        )
    }

    @Test
    fun `a long line is cut, and a page with no text says nothing`() {
        assertEquals(120, first("x".repeat(500))!!.length)
        assertNull(first("<html><body> \n </body></html>"))
    }

    @Test
    fun `a binary reply is not read as text`() {
        val response = Response.Builder()
            .request(Request.Builder().url("http://panel.example/player_api.php").build())
            .protocol(Protocol.HTTP_1_1)
            .code(403)
            .message("Forbidden")
            .header("Content-Type", "application/octet-stream")
            .body(byteArrayOf(0, 1, 2, 3).toResponseBody())
            .build()
        assertNull(ServerReply.of(response, "http://panel.example/player_api.php".toHttpUrl()))
    }
}
