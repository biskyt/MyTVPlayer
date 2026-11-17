package com.biskyt.mytvplayer.core.xtream

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A provider's TMDB id and trailer, as panels send them. */
class TmdbIdsTest {
    private fun movie(info: String) = Json.parseToJsonElement("""{"info":$info}""").toMovieDetails()!!

    @Test
    fun `a film's TMDB id and trailer`() {
        val d = movie("""{"tmdb_id":"603","youtube_trailer":"vKQi3bBA1y8"}""")
        assertEquals(603, d.tmdbId)
        assertEquals("vKQi3bBA1y8", d.trailer)
    }

    @Test
    fun `a series' TMDB id comes as a number under tmdb`() {
        val d = Json.parseToJsonElement("""{"info":{"tmdb":1399}}""").toSeriesDetails()!!
        assertEquals(1399, d.info.tmdbId)
    }

    @Test
    fun `no id, or zero, is none`() {
        assertNull(movie("""{"tmdb_id":""}""").tmdbId)
        assertNull(movie("""{"tmdb_id":"0"}""").tmdbId)
        assertNull(movie("[]".let { "{}" }).tmdbId)
    }

    @Test
    fun `a trailer is read from a bare id or a YouTube address`() {
        assertEquals("vKQi3bBA1y8", youTubeId("https://www.youtube.com/watch?v=vKQi3bBA1y8&t=1"))
        assertEquals("vKQi3bBA1y8", youTubeId("https://youtu.be/vKQi3bBA1y8"))
        assertEquals("vKQi3bBA1y8", youTubeId("https://www.youtube.com/embed/vKQi3bBA1y8"))
        assertNull(youTubeId(""))
        assertNull(youTubeId("not a trailer"))
        assertNull(youTubeId("https://example.com/video.mp4"))
    }
}
