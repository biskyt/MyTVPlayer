package com.biskyt.mytvplayer.core.epg

import java.io.InputStream

/**
 * Removes each `<programme>` element whose `channel` [keep] rejects, or that is outside the window [inWindow] gives,
 * before the XML parser reads it.
 *
 * A big guide has a million programmes, and a playlist uses a small part of its channels. The XML parser reads each
 * character of an element, also of an element it skips. This scan only looks for the next `<` and for the closing
 * tag, so a guide reads several times faster.
 *
 * When this scan cannot be sure of a channel id or a time (an entity such as `&amp;`, a non-ASCII byte, a line break,
 * a very long tag), it keeps the element: the parser then decides as before. Comments and CDATA sections pass as they
 * are.
 */
internal class ProgrammeFilter(
    private val input: InputStream,
    private val keep: (String) -> Boolean,
    /** Whether a programme from a start to an end time is in the window. */
    private val inWindow: (startMs: Long, endMs: Long) -> Boolean
) : InputStream() {
    private var buf = ByteArray(BUFFER)

    /** The next byte to look at. */
    private var pos = 0

    /** The end of the bytes read into [buf]. */
    private var limit = 0
    private var eof = false

    /** Inside a programme that is removed. */
    private var dropping = false

    /** Inside a comment or CDATA section: the bytes that end it. */
    private var special: ByteArray? = null

    /** Programmes of one channel come one after the other: the last id, and if it was kept. */
    private var lastId = ByteArray(0)
    private var lastKept = true

    private val one = ByteArray(1)

    override fun read(): Int = if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        val end = passEnd()
        return if (end < 0) -1 else copy(b, off, len, end)
    }

    /**
     * Skips the programmes removed, then returns where the bytes that pass as they are from [pos] end (before the next
     * programme to look at); -1 at the end.
     */
    private fun passEnd(): Int {
        while (true) {
            if ((dropping && !skipProgramme()) || (pos == limit && !fill())) return -1
            val end = special?.let(::specialEnd) ?: nextProgramme(pos).let { if (it == pos) atTag() else it }
            if (end > pos) return end
        }
    }

    /**
     * The next `<` from [from] that starts a programme, a comment or a CDATA section, or that is too near the end of the
     * bytes read to tell; [limit] if there is none. Everything before it passes as it is, in one read.
     */
    private fun nextProgramme(from: Int): Int {
        var i = from
        while (true) {
            val lt = indexOf('<'.code.toByte(), i, limit)
            if (lt < 0) return limit
            if (limit - lt <= OPEN.size || buf[lt + 1] == '!'.code.toByte() || isProgramme(lt)) return lt
            i = lt + 1
        }
    }

    private fun isProgramme(at: Int) = startsWith(OPEN, at) && at + OPEN.size < limit && isSpace(buf[at + OPEN.size])

    /**
     * With `<` at [pos]: where the bytes that pass end. [pos] itself when a programme there is removed (then skipped),
     * or when more bytes are needed.
     */
    private fun atTag(): Int {
        ensure(CDATA_OPEN.size)
        val (open, close) = when {
            startsWith(COMMENT_OPEN, pos) -> COMMENT_OPEN to COMMENT_CLOSE
            startsWith(CDATA_OPEN, pos) -> CDATA_OPEN to CDATA_CLOSE
            else -> null to null
        }
        if (open != null) {
            special = close
            return pos + open.size
        }
        val end = programmeTagEnd()
        return when {
            end == NOT_PROGRAMME -> nextProgramme(pos + 1)
            keepTag(pos, end) -> nextProgramme(end + 1) // a programme kept passes as it is, up to the next one
            else -> {
                dropping = buf[end - 1] != '/'.code.toByte()
                pos = end + 1
                pos
            }
        }
    }

    /** In a comment or CDATA section ending with [close]: where the bytes that pass end ([pos] if more are needed). */
    private fun specialEnd(close: ByteArray): Int {
        val at = indexOf(close, pos, limit)
        val safe = limit - close.size + 1
        val end = when {
            at >= 0 -> at + close.size
            safe > pos -> return safe
            !fill() -> limit // a cut-off file: the parser reports it
            else -> return pos
        }
        special = null
        return end
    }

    override fun close() = input.close()

    private fun copy(b: ByteArray, off: Int, len: Int, end: Int): Int {
        val n = minOf(len, end - pos)
        System.arraycopy(buf, pos, b, off, n)
        pos += n
        return n
    }

    /** With `<` at [pos]: the index of the `>` that closes a `<programme` start tag, or [NOT_PROGRAMME]. */
    private fun programmeTagEnd(): Int {
        if (!ensure(OPEN.size + 1) || !isProgramme(pos)) return NOT_PROGRAMME
        var i = pos + OPEN.size
        var quote: Byte = 0
        while (true) {
            if (i == limit) {
                i -= pos // fill() moves the bytes from pos to the start
                if (!fill()) return NOT_PROGRAMME // a tag longer than the buffer, or a cut-off file
            }
            val c = buf[i]
            when {
                quote != NO_QUOTE -> if (c == quote) quote = NO_QUOTE
                c == '"'.code.toByte() || c == '\''.code.toByte() -> quote = c
                c == '>'.code.toByte() -> return i
            }
            i++
        }
    }

    /** Whether to keep the programme whose start tag is [start]..[end]: its channel is wanted, and it is in the window. */
    private fun keepTag(start: Int, end: Int): Boolean {
        val id = attribute(CHANNEL, start, end)
        return (id < 0 || keepChannel(valueStart(id), valueEnd(id))) && inWindow(start, end)
    }

    private fun keepChannel(from: Int, to: Int): Boolean {
        val length = to - from
        if (length == lastId.size && regionEquals(lastId, from)) return lastKept
        val id = ascii(from, to) ?: return true
        lastId = buf.copyOfRange(from, to)
        lastKept = keep(id)
        return lastKept
    }

    /** As [XmltvParser] finds it; true when a time can't be read here (the parser then decides). */
    private fun inWindow(start: Int, end: Int): Boolean {
        val startMs = time(attribute(START, start, end)) ?: return true
        val stop = attribute(STOP, start, end)
        val stopMs = time(stop)
        return (stop >= 0 && stopMs == null) || inWindow(startMs, XmltvParser.endOf(startMs, stopMs))
    }

    private fun time(value: Long): Long? = if (value < 0) null else XmltvParser.parseTime(ascii(valueStart(value), valueEnd(value)))

    /**
     * The bytes [from]..[to] as text, or null if they aren't plain ASCII without entities and line breaks (the parser
     * reads those differently).
     */
    private fun ascii(from: Int, to: Int): String? {
        for (k in from until to) if (buf[k] < ' '.code.toByte() || buf[k] == '&'.code.toByte()) return null
        return String(buf, from, to - from, Charsets.ISO_8859_1)
    }

    /**
     * Where the value of the attribute [name] is in the tag [start]..[end], packed as start * 2^32 + end (see
     * [valueStart]); -1 if the tag has no such attribute.
     */
    private fun attribute(name: ByteArray, start: Int, end: Int): Long {
        var i = start + OPEN.size
        while (i + name.size < end) {
            if (isSpace(buf[i]) && startsWith(name, i + 1)) {
                var k = i + 1 + name.size
                while (k < end && isSpace(buf[k])) k++
                if (k < end && buf[k] == '='.code.toByte()) {
                    k++
                    while (k < end && isSpace(buf[k])) k++
                    val quote = if (k < end) buf[k] else 0
                    val close = if (quote == '"'.code.toByte() || quote == '\''.code.toByte()) indexOf(quote, k + 1, end) else -1
                    return if (close < 0) -1 else (k + 1).toLong() shl 32 or close.toLong()
                }
            }
            i++
        }
        return -1
    }

    private fun valueStart(packed: Long) = (packed ushr 32).toInt()
    private fun valueEnd(packed: Long) = packed.toInt()

    /** Reads past the end of a removed programme. Returns false at the end of the input. */
    private fun skipProgramme(): Boolean {
        while (true) {
            var close = indexOf(CLOSE, pos, limit)
            // Not a longer name, such as </programme-info>.
            while (close >= 0 && close + CLOSE.size < limit && !isNameEnd(buf[close + CLOSE.size])) {
                close = indexOf(CLOSE, close + 1, limit)
            }
            val gt = if (close < 0) -1 else indexOf('>'.code.toByte(), close + CLOSE.size, limit)
            if (gt >= 0) {
                pos = gt + 1
                dropping = false
                return true
            }
            // Keep what can be a closing tag cut across two reads.
            pos = if (close >= 0) close else maxOf(pos, limit - CLOSE.size + 1)
            if (!fill()) return false
        }
    }

    /** At least [n] bytes from [pos], reading more if needed. */
    private fun ensure(n: Int): Boolean {
        while (limit - pos < n) if (!fill()) return false
        return true
    }

    /** Moves the unread bytes to the start of [buf] and reads more. Returns false when no more could be read. */
    private fun fill(): Boolean {
        if (eof) return false
        if (pos > 0) {
            System.arraycopy(buf, pos, buf, 0, limit - pos)
            limit -= pos
            pos = 0
        }
        if (limit == buf.size) {
            if (buf.size >= MAX_BUFFER) return false
            buf = buf.copyOf(buf.size * 2)
        }
        val n = input.read(buf, limit, buf.size - limit)
        if (n < 0) {
            eof = true
            return false
        }
        limit += n
        return true
    }

    private fun indexOf(b: Byte, from: Int, to: Int): Int {
        for (i in from until to) if (buf[i] == b) return i
        return -1
    }

    private fun indexOf(pattern: ByteArray, from: Int, to: Int): Int {
        var i = indexOf(pattern[0], from, to)
        while (i >= 0 && i + pattern.size <= to) {
            if (startsWith(pattern, i)) return i
            i = indexOf(pattern[0], i + 1, to)
        }
        return -1
    }

    private fun startsWith(pattern: ByteArray, at: Int): Boolean = at + pattern.size <= limit && regionEquals(pattern, at)

    private fun regionEquals(pattern: ByteArray, at: Int): Boolean {
        for (k in pattern.indices) if (buf[at + k] != pattern[k]) return false
        return true
    }

    private fun isNameEnd(b: Byte) = b == '>'.code.toByte() || isSpace(b)

    private fun isSpace(b: Byte) = b == ' '.code.toByte() ||
        b == '\n'.code.toByte() ||
        b == '\t'.code.toByte() ||
        b == '\r'.code.toByte()

    private companion object {
        const val BUFFER = 64 * 1024
        const val MAX_BUFFER = 1024 * 1024
        const val NOT_PROGRAMME = -1
        const val NO_QUOTE: Byte = 0
        val COMMENT_OPEN = "<!--".toByteArray()
        val COMMENT_CLOSE = "-->".toByteArray()
        val CDATA_OPEN = "<![CDATA[".toByteArray()
        val CDATA_CLOSE = "]]>".toByteArray()
        val OPEN = "<programme".toByteArray()
        val CLOSE = "</programme".toByteArray()
        val CHANNEL = "channel".toByteArray()
        val START = "start".toByteArray()
        val STOP = "stop".toByteArray()
    }
}
