package com.biskyt.mytvplayer.data

import com.biskyt.mytvplayer.core.tmdb.PersonCredit
import com.biskyt.mytvplayer.data.db.VodCard
import org.junit.Assert.assertEquals
import org.junit.Test

/** Which of a person's credits are in the playlist: the same title (or original title), a year either way. */
class PersonLibraryTest {
    private fun credit(key: String, title: String, year: Int?, original: String? = null, show: Boolean = false) =
        PersonCredit(key, title, show, year, null, null, PersonCredit.ACTOR, year?.toString().orEmpty(), original)

    private val movies = listOf(
        VodCard(1, "The Green Mile", 1999, null),
        VodCard(2, "Heat", 1995, null),
        VodCard(3, "Dune", 1984, null),
        VodCard(4, "Dune", 2021, null)
    )
    private val series = listOf(VodCard(77, "Line of Duty", 2012, null))

    private fun found(vararg credits: PersonCredit) = libraryOf(credits.toList(), movies, series).map { (key, t) -> key to t.id }

    @Test
    fun `a film with the playlist's title and a year either way is in the library`() {
        assertEquals(listOf("movie/1" to 2L), found(credit("movie/1", "Heat", 1996)))
        assertEquals(emptyList<Pair<String, Long>>(), found(credit("movie/1", "Heat", 1997)))
        // Two films of one name: the one of its year.
        assertEquals(listOf("movie/2" to 4L), found(credit("movie/2", "Dune", 2021)))
    }

    @Test
    fun `a credit in the app's language is found by its original title`() {
        assertEquals(listOf("movie/497" to 1L), found(credit("movie/497", "La Ligne verte", 1999, original = "The Green Mile")))
    }

    @Test
    fun `a series is found among the series only, a year either way`() {
        assertEquals(listOf("tv/31917" to 77L), found(credit("tv/31917", "Line of Duty", 2013, show = true)))
        assertEquals(emptyList<Pair<String, Long>>(), found(credit("movie/9", "Line of Duty", 2012)))
    }
}
