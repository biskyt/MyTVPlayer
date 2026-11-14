package com.biskyt.mytvplayer.core.net

import okhttp3.HttpUrl
import okhttp3.Response

/**
 * What a server said with an error status: enough to tell who refused a request, and perhaps why (the owner,
 * 2026-11-14). A firewall in front of the server, a redirect to another host and the server itself each say it
 * differently.
 */
data class ServerReply(
    /** Its Server header, such as "cloudflare" or "nginx". */
    val server: String? = null,
    /** The host that answered, when a redirect took the request to another one. */
    val host: String? = null,
    /** The first line of its text, without HTML tags or a login, such as "403 Forbidden" or "Access denied". */
    val text: String? = null,
) {
    /** In English, for logs. */
    override fun toString() = listOfNotNull(text?.let { "\"$it\"" }, server?.let { "server $it" }, host?.let { "from $it" })
        .joinToString(", ")

    companion object {
        /** What [response] says, for a request to [asked]; null if it says nothing of use. */
        fun of(response: Response, asked: HttpUrl): ServerReply? = ServerReply(
            server = response.header("Server")?.let(::shortLine),
            host = response.request.url.host.takeIf { it != asked.host },
            text = if (isText(response.header("Content-Type"))) {
                runCatching { response.peekBody(TEXT_BYTES).string() }.getOrNull()?.let(::firstLine)
            } else {
                null
            },
        ).takeIf { it != ServerReply() }

        /** No type (often a plain error page), text, HTML, JSON or XML; not a binary body. */
        private fun isText(type: String?): Boolean =
            type == null || type.startsWith("text/", ignoreCase = true) || TEXT_TYPES.any { type.contains(it, ignoreCase = true) }

        /** The first line of [body] that has text, as a browser shows it: without tags, the head, scripts and styles. */
        internal fun firstLine(body: String): String? = withoutHidden(body)
            .replace(BLOCK, "\n")
            .replace(TAG, "")
            .lineSequence()
            .firstNotNullOfOrNull(::shortLine)

        private fun shortLine(s: String): String? = ENTITIES.entries
            .fold(s) { line, (entity, char) -> line.replace(entity, char) }
            .replace(LOGIN, "$1=…")
            .replace(SPACES, " ")
            .trim()
            .take(MAX_CHARS)
            .ifEmpty { null }

        /** [body] without what a page does not show as text: its head, scripts and styles (an unclosed one to the end). */
        private fun withoutHidden(body: String): String {
            val shown = StringBuilder()
            var from = 0
            while (true) {
                val (start, tag) = HIDDEN.mapNotNull { tag -> tagAt(body, "<$tag", from)?.let { it to tag } }.minByOrNull { it.first }
                    ?: break
                shown.append(body, from, start)
                // A head may have no closing tag (HTML lets it out): it ends where the body starts.
                val ends = listOfNotNull(tagAt(body, "</$tag", start), if (tag == "head") tagAt(body, "<body", start) else null)
                val end = ends.minOrNull()
                from = when {
                    end == null -> body.length
                    body.startsWith("<body", end, ignoreCase = true) -> end
                    else -> body.indexOf('>', end).let { if (it < 0) body.length else it + 1 }
                }
            }
            return shown.append(body, from, body.length).toString()
        }

        /** Where [tag] (such as "<head") is in [body] from [from], not as part of a longer name (such as "<header"). */
        private fun tagAt(body: String, tag: String, from: Int): Int? {
            var at = body.indexOf(tag, from, ignoreCase = true)
            while (at >= 0 && body.getOrNull(at + tag.length)?.isLetterOrDigit() == true) at = body.indexOf(tag, at + 1, ignoreCase = true)
            return at.takeIf { it >= 0 }
        }

        /** Enough for a firewall's or a server's error page. */
        private const val TEXT_BYTES = 8_192L
        private const val MAX_CHARS = 120

        private val TEXT_TYPES = listOf("html", "json", "xml")
        private val HIDDEN = listOf("head", "script", "style")

        /** Tags that start a new line on a page; the others (such as span and b) run on in the same line. */
        private val BLOCK =
            Regex(
                "</?(p|div|br|hr|h[1-6]|li|ul|ol|tr|td|th|table|title|section|header|footer|main|article|center|pre|body)\\b[^>]*>",
                RegexOption.IGNORE_CASE
            )
        private val TAG = Regex("<[^>]*>")
        private val SPACES = Regex("[ \\t\\r]+")

        /** A login that the page repeats from the request (its address): not shown, nor logged. */
        private val LOGIN = Regex("(username|password)=[^&\\s\"'<]*", RegexOption.IGNORE_CASE)

        /** The entities an error page uses most; &amp; last, so that "&amp;lt;" stays "&lt;" as the page shows it. */
        private val ENTITIES = linkedMapOf(
            "&nbsp;" to " ",
            "&quot;" to "\"",
            "&#39;" to "'",
            "&lt;" to "<",
            "&gt;" to ">",
            "&amp;" to "&",
        )
    }
}
