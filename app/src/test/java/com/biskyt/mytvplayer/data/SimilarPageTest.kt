package com.biskyt.mytvplayer.data

import com.biskyt.mytvplayer.core.tmdb.TmdbSuggestion
import com.biskyt.mytvplayer.data.db.VodCard
import org.junit.Assert.assertEquals
import org.junit.Test

/** "More like this": TMDB's suggestions in the playlist and not in it, matched as a person's titles are. */
class SimilarPageTest {
    private fun suggestion(id: Int, title: String, year: Int?, original: String? = null) =
        TmdbSuggestion(id, series = false, title, original, year, null, null)

    private val films = listOf(VodCard(1, "Heat", 1995, null), VodCard(2, "The Green Mile", 1999, null), VodCard(3, "Heat", 1995, null))

    @Test
    fun `suggestions in the playlist are found by title or original title, the others are kept in TMDB's order`() {
        val page = similarPage(
            listOf(
                suggestion(10, "Arrival", 2016),
                suggestion(11, "La Ligne verte", 1999, original = "The Green Mile"),
                suggestion(12, "Heat", 1996),
                suggestion(13, "Sicario", 2015),
            ),
            films
        )
        assertEquals(listOf(2L to 11, 1L to 12), page.inPlaylist.map { (card, s) -> card.id to s.id })
        assertEquals(listOf(10, 13), page.elsewhere.map { it.id })
    }

    @Test
    fun `a title from another year is not in the playlist`() {
        val page = similarPage(listOf(suggestion(14, "Heat", 1986)), films)
        assertEquals(emptyList<Pair<VodCard, TmdbSuggestion>>(), page.inPlaylist)
        assertEquals(listOf(14), page.elsewhere.map { it.id })
    }
}
