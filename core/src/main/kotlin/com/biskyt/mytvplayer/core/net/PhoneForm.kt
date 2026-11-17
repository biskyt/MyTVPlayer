package com.biskyt.mytvplayer.core.net

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.SecureRandom
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What a [PhoneForm] has seen so far, shown on its panel: when a phone's browser shows no page, it tells whether the
 * phone reached the TV at all. [secure]: connections that asked for HTTPS (the page is HTTP only). [unanswered]:
 * connections that ended with no answer (closed by the browser before a request, too slow, or broken).
 */
data class PhoneFormSeen(val connections: Int = 0, val pages: Int = 0, val secure: Int = 0, val unanswered: Int = 0)

/** The words of a [PhoneForm] page, in the app's language. */
data class PhoneFormText(
    val title: String,
    val hint: String,
    val button: String,
    /** Shown on the phone after a value is sent. */
    val done: String
)

/**
 * A one-field web page that this device serves on the home network, so that a phone can send it a value (such as a
 * TMDB key) instead of typing it with a remote. The TV shows [url] as a QR code. The address has a random part, so
 * only someone who sees the QR code can open it. [received] gets each value sent (trimmed, never blank), on the thread
 * of the connection that sent it: two connections can call it at the same time. It serves until [close]: only while
 * its panel is open.
 */
class PhoneForm(
    address: InetAddress,
    private val text: PhoneFormText,
    private val received: (String) -> Unit
) : Closeable {
    private val socket = ServerSocket(0, BACKLOG, address)
    private val token = ByteArray(TOKEN_BYTES).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }

    val url = "http://${address.hostAddress}:${socket.localPort}/$token"

    /**
     * The connections being served, each on its own thread, in the order they came, with whether their request has
     * started. All of them close with the server, so that no thread stays. Guarded by [lock], as is closing.
     */
    private val clients = LinkedHashMap<Socket, Boolean>()
    private val lock = Any()

    private val _seen = MutableStateFlow(PhoneFormSeen())

    /** What this server has seen since it started. */
    val seen: StateFlow<PhoneFormSeen> = _seen.asStateFlow()

    init {
        thread(name = "PhoneForm", isDaemon = true) {
            // Until closed: the panel is gone.
            while (!socket.isClosed) acceptOrNull()?.let(::admit)
        }
    }

    /** The next connection; null when the server is closed, or when one connection failed before it was accepted. */
    private fun acceptOrNull(): Socket? = try {
        socket.accept()
    } catch (_: IOException) {
        // A connection that the phone dropped, or no file handles for a moment: the server goes on, after a short wait.
        if (!socket.isClosed) Thread.sleep(ACCEPT_RETRY_MS)
        null
    }

    /**
     * A phone's browser opens connections before it uses them, and asks for an icon too. So each connection has its own
     * thread, and a connection that waits does not stop the others. At most [MAX_CLIENTS] at a time: when all are in
     * use, the oldest connection that waits for a request (none yet, or its next one) makes room. A connection that comes as the server closes is closed.
     */
    private fun admit(c: Socket) {
        val admitted = synchronized(lock) {
            if (socket.isClosed) return@synchronized false
            if (clients.size >= MAX_CLIENTS) {
                clients.entries.firstOrNull { !it.value }?.key?.let { idle ->
                    clients.remove(idle)
                    runCatching { idle.close() }
                }
            }
            (clients.size < MAX_CLIENTS).also { if (it) clients[c] = false }
        }
        _seen.update { it.copy(connections = it.connections + 1) }
        if (admitted) thread(name = "PhoneForm request", isDaemon = true) { handle(c) } else runCatching { c.close() }
    }

    /** [c]'s request has started: it is no longer the first to make room. */
    private fun started(c: Socket) = synchronized(lock) { if (c in clients) clients[c] = true }

    /** [c] was answered and waits for its next request: it may make room again. */
    private fun idle(c: Socket) = synchronized(lock) { if (c in clients) clients[c] = false }

    private fun handle(c: Socket) {
        var answered = false
        try {
            answered = c.use(::serve)
        } catch (_: IOException) {
            // A bad or slow request, or the phone went away: only this connection ends.
        } catch (@Suppress("TooGenericExceptionCaught") _: RuntimeException) {
            // Anything else a stranger on the network sends: never a crash of the app.
        } finally {
            if (!answered) _seen.update { it.copy(unanswered = it.unanswered + 1) }
            synchronized(lock) { clients.remove(c) }
        }
    }

    override fun close() = synchronized(lock) {
        socket.close()
        clients.keys.forEach { runCatching { it.close() } }
        clients.clear()
    }

    /**
     * Answers [client]'s requests one after another, as HTTP/1.1 expects: the connection stays open after an answer,
     * for up to [IDLE_MS], unless the browser asks for it to close. Browsers use an open connection again, and send a
     * request on it again when it was closed under them, then give up ("too many retries"). False when the connection
     * ended with no answer.
     */
    private fun serve(client: Socket): Boolean {
        val buffered = BufferedInputStream(client.getInputStream())
        var answered = false
        var open = true
        while (open) {
            // A browser can open a connection well before it sends its request on it: the first byte may take a while.
            client.soTimeout = if (answered) IDLE_MS else FIRST_BYTE_MS
            val first = peek(buffered, answered)
            open = when {
                first < 0 -> false
                // An HTTP request starts with its method's capital letters. Some browsers try HTTPS first: an answer at
                // once tells them that this server has only HTTP, so they open the http address.
                first !in 'A'.code..'Z'.code -> {
                    if (!answered) {
                        _seen.update { it.copy(secure = it.secure + 1) }
                        reply(client, Reply(BAD_REQUEST, ""), keepOpen = false)
                        answered = true
                    }
                    false
                }
                else -> serveOne(client, buffered)?.also {
                    answered = true
                    idle(client)
                } ?: false
            }
        }
        return answered
    }

    /**
     * The next request's first byte, left to be read again; -1 at the end, or when an open connection is not used again
     * in time (it ends, as its Keep-Alive header said).
     */
    private fun peek(buffered: BufferedInputStream, answered: Boolean): Int {
        buffered.mark(1)
        val first = try {
            buffered.read()
        } catch (e: SocketTimeoutException) {
            if (answered) return -1 else throw e
        }
        buffered.reset()
        return first
    }

    /** Reads one request and answers it. Whether the connection stays open; null when the request ended early. */
    private fun serveOne(client: Socket, buffered: BufferedInputStream): Boolean? {
        val input = LimitedInput(buffered, client) { started(client) }
        val request = input.line()?.split(' ') ?: return null
        val (method, path) = request.getOrNull(0) to request.getOrNull(1)
        // All the headers are read first: the body starts after the blank line that ends them.
        val headers = generateSequence { input.line()?.takeIf { it.isNotEmpty() } }.toList()
        fun header(name: String) = headers.firstNotNullOfOrNull { h ->
            h.takeIf { it.startsWith("$name:", ignoreCase = true) }?.substringAfter(':')?.trim()
        }
        val length = header("Content-Length")?.toIntOrNull() ?: 0
        val reply = when {
            path != "/$token" -> Reply(NOT_FOUND, "")
            method == "GET" -> Reply(OK, page(sent = false)).also { _seen.update { it.copy(pages = it.pages + 1) } }
            method == "POST" && length in 1..MAX_BODY -> {
                value(input.text(length))?.let(received)
                Reply(OK, page(sent = true))
            }
            else -> Reply(BAD_REQUEST, "")
        }
        // Closed after a bad request or another body not read (the next request would start inside it), on the
        // browser's asking, or for an HTTP/1.0 browser.
        val keepOpen = reply.status != BAD_REQUEST &&
            (method == "POST" || length == 0) &&
            request.getOrNull(2) == "HTTP/1.1" &&
            !header("Connection").equals("close", ignoreCase = true)
        reply(client, reply, keepOpen)
        return keepOpen
    }

    private fun reply(client: Socket, reply: Reply, keepOpen: Boolean) {
        val bytes = reply.body.toByteArray(Charsets.UTF_8)
        val connection = if (keepOpen) "Connection: keep-alive\r\nKeep-Alive: timeout=${IDLE_MS / MS_PER_S}" else "Connection: close"
        client.getOutputStream().apply {
            write(
                (
                    "HTTP/1.1 ${reply.status}\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n" +
                        "Cache-Control: no-store\r\n$connection\r\n\r\n"
                    ).toByteArray(Charsets.US_ASCII)
            )
            write(bytes)
            flush()
        }
    }

    private data class Reply(val status: String, val body: String)

    /**
     * A request's bytes, with limits: [MAX_HEAD] bytes of request line and headers, and all of it within [REQUEST_MS] of
     * its first byte. So no one on the network can hold a connection or fill memory, with or without the address.
     */
    private class LimitedInput(private val input: InputStream, private val socket: Socket, private val onStart: () -> Unit) {
        private var read = 0
        private var deadline = Long.MAX_VALUE

        /** The next line (without its line end), as ASCII; null at the end. */
        fun line(): String? {
            val out = ByteArrayOutputStream()
            while (true) {
                val b = next()
                if (b < 0) return if (out.size() == 0) null else out.toString(Charsets.US_ASCII.name())
                if (++read > MAX_HEAD) throw IOException("Request too long")
                if (b == '\n'.code) return out.toString(Charsets.US_ASCII.name()).trimEnd('\r')
                out.write(b)
            }
        }

        /** [length] bytes of a body (a form's, URL-encoded: ASCII), or fewer when it ends early. */
        fun text(length: Int): String {
            val out = ByteArrayOutputStream(length)
            while (out.size() < length) {
                val b = next()
                if (b < 0) break
                out.write(b)
            }
            return out.toString(Charsets.US_ASCII.name())
        }

        private fun next(): Int {
            if (System.currentTimeMillis() > deadline) throw IOException("Request too slow")
            val b = input.read()
            if (deadline == Long.MAX_VALUE) {
                // The request has started: the rest must follow soon.
                deadline = System.currentTimeMillis() + REQUEST_MS
                socket.soTimeout = READ_TIMEOUT_MS
                onStart()
            }
            return b
        }
    }

    private fun page(sent: Boolean): String {
        val form = if (sent) {
            "<p>${escape(text.done)}</p>"
        } else {
            """<form method="post"><input name="value" autocomplete="off" autocapitalize="off" spellcheck="false" autofocus>""" +
                """<button>${escape(text.button)}</button></form>"""
        }
        return """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">""" +
            "<title>${escape(text.title)}</title><style>body{font-family:sans-serif;margin:24px;max-width:480px}" +
            "input,button{font-size:18px;width:100%;box-sizing:border-box;padding:12px;margin:8px 0}</style></head>" +
            "<body><h1>${escape(text.title)}</h1><p>${escape(text.hint)}</p>$form</body></html>"
    }

    companion object {
        /**
         * This device's address on the home network (IPv4, private range); null when it has none (no network). Not a
         * VPN's (tun, ppp, WireGuard…), which a phone at home can't reach; Wi-Fi and Ethernet first.
         */
        fun homeAddress(): InetAddress? = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual && VPN_NAMES.none(it.name.lowercase()::startsWith) }
                .sortedBy { i -> if (LAN_NAMES.any(i.name.lowercase()::startsWith)) 0 else 1 }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
        }.getOrNull()

        private val VPN_NAMES = listOf("tun", "tap", "ppp", "ipsec", "wg", "utun")
        private val LAN_NAMES = listOf("wlan", "eth", "en")

        private const val BACKLOG = 4
        private const val TOKEN_BYTES = 12
        private const val FIRST_BYTE_MS = 30_000

        /** How long a connection stays open after an answer, for the browser's next request. */
        private const val IDLE_MS = 30_000
        private const val MS_PER_S = 1_000
        private const val READ_TIMEOUT_MS = 2_000

        /** Far more than a browser opens: one that opens many at once must never lose one it is about to use. */
        internal const val MAX_CLIENTS = 32
        private const val ACCEPT_RETRY_MS = 100L
        private const val REQUEST_MS = 10_000L
        private const val MAX_HEAD = 16 * 1024
        private const val MAX_BODY = 4096
        private const val OK = "200 OK"
        private const val NOT_FOUND = "404 Not Found"
        private const val BAD_REQUEST = "400 Bad Request"

        /** The value in a form's body ("value=..."), decoded and trimmed; null when blank. */
        internal fun value(body: String): String? = body.split('&').firstOrNull { it.startsWith("value=") }
            ?.let { runCatching { URLDecoder.decode(it.substringAfter('='), "UTF-8") }.getOrNull()?.trim() }?.ifEmpty { null }

        internal fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    }
}
