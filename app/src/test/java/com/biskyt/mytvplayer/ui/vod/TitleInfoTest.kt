package com.biskyt.mytvplayer.ui.vod

import com.biskyt.mytvplayer.core.tmdb.TmdbTitle
import com.biskyt.mytvplayer.core.vod.CastMember
import com.biskyt.mytvplayer.core.xtream.VodDetails
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which of the provider's details and TMDB's a page shows: the provider's first (see [merge]). */
class TitleInfoTest {
    private val provider = VodDetails(
        plot = "The provider's plot.",
        cast = "Tom Hale, Ines Moreau",
        director = "Ana Ruiz",
        genre = "Drama",
        year = 2021,
        durationSec = 6720,
        backdropUrl = "http://provider/back.jpg",
        posterUrl = "http://provider/poster.jpg",
        rating = 7.4,
        trailer = "provider01"
    )

    private val tmdb = TmdbTitle(
        id = 1,
        series = false,
        imdbId = null,
        overview = "TMDB's plot.",
        score = 7.8,
        cast = listOf(CastMember("Tom Hale", "Rose", null)),
        directors = listOf("A. Ruiz"),
        writers = listOf("Paul Okafor"),
        trailer = "tmdb000001",
        posterUrl = "http://tmdb/poster.jpg",
        backdropUrl = "http://tmdb/back.jpg",
        genres = listOf("Mystery", "Drama", "Thriller"),
        originalTitle = null,
        released = LocalDate.of(2020, 5, 1),
        studios = emptyList(),
        countries = emptyList()
    )

    @Test
    fun `the provider's data comes first`() {
        val info = merge(provider, tmdb)
        assertEquals("The provider's plot.", info.plot)
        assertEquals("Drama", info.genre)
        assertEquals(2021, info.year)
        assertEquals("http://provider/poster.jpg", info.posterUrl)
        assertEquals("http://provider/back.jpg", info.backdropUrl)
        assertEquals(7.4, info.rating!!, 0.0)
        assertEquals(7.8, info.score!!, 0.0)
    }

    @Test
    fun `TMDB fills what the provider leaves out`() {
        val info = merge(VodDetails(null, null, null, null, null, null, null, null, null), tmdb)
        assertEquals("TMDB's plot.", info.plot)
        assertEquals("Mystery, Drama", info.genre) // two at most
        assertEquals(2020, info.year)
        assertEquals("http://tmdb/poster.jpg", info.posterUrl)
        assertEquals("http://tmdb/back.jpg", info.backdropUrl)
        assertEquals(merge(null, tmdb), info)
    }

    @Test
    fun `TMDB's people, cast and trailer come first`() {
        val info = merge(provider, tmdb)
        assertEquals(listOf("A. Ruiz"), info.directors)
        assertEquals(listOf("Paul Okafor"), info.writers)
        assertEquals(tmdb.cast, info.cast)
        assertNull(info.castNames) // TMDB's cast, with photos, in place of the provider's names
        assertEquals("tmdb000001", info.trailer)
    }

    @Test
    fun `without TMDB, the provider's director, cast and trailer`() {
        val info = merge(provider, null)
        assertEquals(listOf("Ana Ruiz"), info.directors)
        assertEquals(emptyList<String>(), info.writers)
        assertEquals("Tom Hale, Ines Moreau", info.castNames)
        assertEquals("provider01", info.trailer)
        assertNull(info.score)
        assertNull(info.tmdb)
        assertEquals(TitleInfo(), merge(null, null))
    }
}
