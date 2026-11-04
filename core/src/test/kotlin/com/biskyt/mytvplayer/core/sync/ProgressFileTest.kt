package com.biskyt.mytvplayer.core.sync

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ProgressFileTest {
    private fun row(id: Long, at: Long, position: Long = 10) = SyncedProgress("p", "movie", id, position, 100, at, "Film $id")

    private fun delete(id: Long, at: Long) = SyncedDelete("p", "movie", id, at)

    @Test
    fun `a file reads back as written`() {
        val file =
            ProgressFile(
                rows = listOf(row(1, 5).copy(seriesId = 7, season = 1, episode = 2, hasNext = true)),
                deleted = listOf(delete(2, 6))
            )
        assertEquals(file, ProgressFile.decode(file.encode()))
    }

    @Test
    fun `fields from a newer version are ignored`() {
        val row = """{"playlist":"p","kind":"movie","id":1,"position":10,"duration":100,"updatedAt":5,"title":"Film 1","tmdb":42}"""
        val text = """{"version":2,"rows":[$row],"later":true}"""
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray()) }
        assertEquals(listOf(row(1, 5)), ProgressFile.decode(out.toByteArray()).rows)
    }

    @Test(expected = SyncException::class)
    fun `an unreadable file fails`() {
        ProgressFile.decode(byteArrayOf(1, 2, 3))
    }

    @Test
    fun `the newest change to each item wins, and a delete wins a tie`() {
        val a = ProgressFile(rows = listOf(row(1, 10, position = 1), row(2, 10), row(3, 10)))
        val b = ProgressFile(rows = listOf(row(1, 20, position = 2)), deleted = listOf(delete(2, 5), delete(3, 10)))
        val newest = ProgressFile.newest(listOf(a, b))
        assertEquals(ProgressFile.Saved(row(1, 20, position = 2)), newest[SyncKey("p", "movie", 1)])
        assertEquals(ProgressFile.Saved(row(2, 10)), newest[SyncKey("p", "movie", 2)])
        assertEquals(ProgressFile.Deleted(delete(3, 10)), newest[SyncKey("p", "movie", 3)])
    }

    @Test
    fun `the playlist key ignores case, a closing slash and a missing http, and hides the server`() {
        val key = ProgressFile.playlistKey("xtream", "http://Example.com:8080/", "user")
        assertEquals(key, ProgressFile.playlistKey("xtream", " http://example.com:8080", "user"))
        assertEquals(key, ProgressFile.playlistKey("xtream", "example.com:8080", "user"))
        assertNotEquals(key, ProgressFile.playlistKey("xtream", "http://example.com:8080", "other"))
        assertEquals(64, key.length)
        assertEquals(false, key.contains("example"))
    }
}
