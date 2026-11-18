package com.biskyt.mytvplayer.core.epg

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadAheadTest {
    private fun bytes(n: Int) = Random(n).nextBytes(n)

    @Test
    fun `gives the same bytes as its source, whatever the size`() {
        for (n in listOf(0, 1, 7, 16, 17, 64, 1000, 4096 + 3)) {
            val data = bytes(n)
            val read = ReadAhead(ByteArrayInputStream(data), pieceSize = 16, pieces = 2).use { it.readBytes() }
            assertArrayEquals("size $n", data, read)
        }
    }

    @Test
    fun `byte by byte too`() {
        val data = bytes(100)
        ReadAhead(ByteArrayInputStream(data), pieceSize = 8, pieces = 3).use { input ->
            data.forEach { assertEquals(it.toInt() and 0xff, input.read()) }
            assertEquals(-1, input.read())
            assertEquals(-1, input.read())
        }
    }

    @Test
    fun `a failure while reading ahead reaches the reader, after what was read before it`() {
        val failing = object : InputStream() {
            var left = 40

            override fun read(): Int = if (left-- > 0) 1 else throw IOException("cut off")
        }
        ReadAhead(failing, pieceSize = 16, pieces = 2).use { input ->
            val buf = ByteArray(16)
            var got = 0
            val e = runCatching {
                while (true) {
                    val n = input.read(buf, 0, buf.size)
                    if (n < 0) break
                    got += n
                }
            }.exceptionOrNull()
            assertTrue(e is IOException)
            assertEquals("cut off", e!!.message)
            assertEquals(32, got) // the whole pieces read before it
        }
    }

    @Test
    fun `closing it stops reading the source`() {
        var reads = 0
        val endless = object : InputStream() {
            override fun read(): Int {
                reads++
                return 1
            }
        }
        val input = ReadAhead(endless, pieceSize = 16, pieces = 2, name = "test-read-ahead")
        input.read()
        input.close()
        Thread.sleep(300)
        val after = reads
        Thread.sleep(300)
        assertEquals(after, reads)
        assertTrue(Thread.getAllStackTraces().keys.none { it.name == "test-read-ahead" && it.isAlive })
    }
}
