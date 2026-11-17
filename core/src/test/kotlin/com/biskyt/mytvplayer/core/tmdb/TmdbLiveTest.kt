package com.biskyt.mytvplayer.core.tmdb

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * A check against the real TMDB, with the key in the TMDB_API_KEY environment variable (a CI secret). Without the key
 * (local runs, pull requests from forks) it is not run. It finds out early when TMDB changes what the app reads.
 */
class TmdbLiveTest {
    private val key = System.getenv("TMDB_API_KEY").orEmpty()

    @Test
    fun `TMDB still answers as the app reads it`() = runBlocking {
        assumeTrue("No TMDB_API_KEY", key.isNotBlank())
        val tmdb = TmdbClient(OkHttpClient(), key, "en-GB")
        assertTrue(tmdb.keyWorks())
        assertEquals(603, tmdb.findMovie("The Matrix", 1999))
        val film = tmdb.movie(603)!!
        assertEquals("tt0133093", film.imdbId)
        assertTrue(film.cast.any { it.name == "Keanu Reeves" })
        assertTrue(film.directors.isNotEmpty())
        val series = tmdb.series(1399)!!
        assertEquals("tt0944947", series.imdbId)
        assertTrue(tmdb.season(1399, 1).isNotEmpty())
        assertEquals(6384L, film.cast.first { it.name == "Keanu Reeves" }.id)
        val keanu = tmdb.person(6384)!!
        assertEquals("Keanu Reeves", keanu.name)
        assertEquals(PersonCredit.ACTOR, keanu.kinds.first())
        assertTrue(keanu.credits.any { it.ratingKey == "movie/603" && it.kind == PersonCredit.ACTOR })
        val more = tmdb.recommendations(series = false, 603)
        assertTrue(more.isNotEmpty() && more.none { it.id == 603 || it.series })
    }
}
