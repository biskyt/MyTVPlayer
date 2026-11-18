package com.biskyt.mytvplayer.core.epg

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Reads [source] on a thread of its own, at most [pieces] pieces of [pieceSize] bytes ahead of the reader. Unpacking a
 * guide's file then runs on another core while the XML is read, on a TV with more than one. The pieces are used again,
 * so memory stays at about [pieces] times [pieceSize]. A failure on the thread is thrown to the reader; [close] stops
 * the thread and closes [source].
 */
class ReadAhead(
    private val source: InputStream,
    private val pieceSize: Int = 64 * 1024,
    pieces: Int = 16,
    name: String = "read-ahead",
) : InputStream() {
    private class Piece(val bytes: ByteArray, var length: Int = 0)

    /** Pieces read, in order; then [END], or what went wrong. */
    private val ready = ArrayBlockingQueue<Any>(pieces)

    /** Pieces the reader is done with, for the thread to fill again. */
    private val spare = ArrayBlockingQueue<Piece>(pieces + 2)

    @Volatile private var closed = false
    private var current: Piece? = null
    private var at = 0
    private var ended = false

    private val reader = thread(name = name, isDaemon = true) {
        try {
            while (!closed) {
                val piece = spare.poll() ?: Piece(ByteArray(pieceSize))
                piece.length = fill(piece.bytes)
                if (piece.length > 0) hand(piece)
                if (piece.length < pieceSize) break
            }
            hand(END)
        } catch (e: IOException) {
            hand(e)
        } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
            // Passed on, not thrown here: on Android, an exception left on this thread would close the app.
            hand(e)
        }
    }

    /** Fills [bytes] from [source]: fewer only at its end. */
    private fun fill(bytes: ByteArray): Int {
        var n = 0
        while (n < bytes.size) {
            val read = source.read(bytes, n, bytes.size - n)
            if (read < 0) break
            n += read
        }
        return n
    }

    /** Passes [item] to the reader, waiting while it is [pieces] behind; given up once closed. */
    private fun hand(item: Any) {
        while (!closed) {
            if (ready.offer(item, WAIT_MS, TimeUnit.MILLISECONDS)) return
        }
    }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and BYTE
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        val piece = current?.takeIf { at < it.length } ?: next() ?: return -1
        val n = minOf(len, piece.length - at)
        System.arraycopy(piece.bytes, at, b, off, n)
        at += n
        return n
    }

    /** The next piece, waiting for it; null at the end. */
    private fun next(): Piece? {
        current?.let { spare.offer(it) }
        current = null
        if (ended) return null
        return when (val item = ready.take()) {
            END -> {
                ended = true
                null
            }
            is IOException -> throw IOException(item.message, item).also { ended = true }
            is RuntimeException -> throw item.also { ended = true }
            else -> (item as Piece).also {
                current = it
                at = 0
            }
        }
    }

    override fun close() {
        closed = true
        ready.clear()
        source.close()
        reader.join(WAIT_MS)
    }

    private companion object {
        val END = Any()
        const val WAIT_MS = 100L
        const val BYTE = 0xff
    }
}
