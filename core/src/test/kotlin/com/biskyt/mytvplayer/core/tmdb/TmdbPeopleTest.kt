package com.biskyt.mytvplayer.core.tmdb

import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** A person's page from TMDB: who they are, and their credits as the page lists them (MyPlexPlayer's PlexPeopleTest). */
class TmdbPeopleTest {
    private val server = MockWebServer()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.shutdown()

    private fun client() = TmdbClient(OkHttpClient(), "0123456789abcdef0123456789abcdef", "en-GB", server.url("/3/").toString())

    private fun credit(kind: String, key: String, date: String) =
        PersonCredit(key, key, key.startsWith("tv/"), date.take(4).toIntOrNull(), null, null, kind, date)

    @Test
    fun `a person and their credits, newest first, with parts as themselves and other jobs left out`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"id":6384,"name":"Keanu Reeves","biography":" A Canadian actor. ","birthday":"1964-09-02",
                "place_of_birth":"Beirut, Lebanon","deathday":null,"profile_path":"/k.jpg","known_for_department":"Acting",
                "combined_credits":{"cast":[
                  {"id":603,"media_type":"movie","title":"Matrix","original_title":"The Matrix","character":"Neo","poster_path":"/m.jpg","release_date":"1999-03-31"},
                  {"id":1,"media_type":"tv","name":"A Talk Show","character":"Self","first_air_date":"2005-01-01"},
                  {"id":2,"media_type":"tv","name":"Swedish Dicks","character":"Tex","first_air_date":"2016-08-01"},
                  {"id":3,"media_type":"movie","title":"No Date","character":""}],
                 "crew":[
                  {"id":4,"media_type":"movie","title":"Man of Tai Chi","job":"Director","department":"Directing","release_date":"2013-07-05"},
                  {"id":5,"media_type":"movie","title":"Camera Work","job":"Camera Operator","department":"Camera","release_date":"2010-01-01"},
                  {"id":6,"media_type":"movie","title":"Side by Side","job":"Producer","department":"Production","release_date":"2012-08-19"}]}}"""
            )
        )
        val p = client().person(6384)!!
        assertEquals("/3/person/6384", server.takeRequest().requestUrl!!.encodedPath)
        assertEquals("Keanu Reeves", p.name)
        assertEquals("A Canadian actor.", p.biography)
        assertEquals(LocalDate.of(1964, 9, 2), p.born)
        assertEquals("Beirut, Lebanon", p.birthPlace)
        assertNull(p.died)
        assertEquals("https://image.tmdb.org/t/p/h632/k.jpg", p.photoUrl)
        assertEquals(listOf("tv/2", "movie/4", "movie/6", "movie/603", "movie/3"), p.credits.map { it.ratingKey })
        val matrix = p.credits.first { it.ratingKey == "movie/603" }
        assertEquals(
            PersonCredit(
                "movie/603", "Matrix", false, 1999, "https://image.tmdb.org/t/p/w342/m.jpg", "Neo", "actor", "1999-03-31", "The Matrix"
            ),
            matrix
        )
        // A crew credit has no part: TMDB's job ("Director") is English only, so the page names it by its kind.
        assertNull(p.credits.first { it.ratingKey == "movie/4" }.role)
        assertNull(p.credits.first { it.ratingKey == "movie/4" }.originalTitle)
        assertEquals(true, p.credits.first { it.ratingKey == "tv/2" }.show)
        assertNull(p.credits.first { it.ratingKey == "movie/3" }.year)
        // Acting first: it is what TMDB knows them for, though as many credits are crew.
        assertEquals(listOf(PersonCredit.ACTOR, PersonCredit.DIRECTOR), p.kinds)
    }

    @Test
    fun `a person TMDB doesn't have`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(client().person(1))
    }

    @Test
    fun `also appears in leaves out what is in the playlist and what they produced, each title once`() {
        val credits = listOf(
            credit(PersonCredit.ACTOR, "movie/1", "2021-01-01"),
            credit(PersonCredit.WRITER, "movie/1", "2021-01-01"),
            credit(PersonCredit.PRODUCER, "movie/2", "2020-01-01"),
            credit(PersonCredit.DIRECTOR, "tv/3", "2019-01-01"),
            credit(PersonCredit.ACTOR, "movie/4", "2018-01-01")
        )
        assertEquals(listOf("movie/1", "tv/3"), alsoAppearsIn(credits, setOf("movie/4")).map { it.ratingKey })
        assertEquals(listOf("movie/1"), alsoAppearsIn(credits, emptySet(), max = 1).map { it.ratingKey })
    }

    @Test
    fun `kinds of work are the most credited, after what TMDB knows them for`() {
        val credits = listOf(
            credit(PersonCredit.WRITER, "movie/1", ""),
            credit(PersonCredit.WRITER, "movie/2", ""),
            credit(PersonCredit.PRODUCER, "movie/3", ""),
            credit(PersonCredit.DIRECTOR, "movie/4", "")
        )
        assertEquals(PersonCredit.WRITER, kindsOfWork(credits).first())
        assertEquals(listOf(PersonCredit.DIRECTOR, PersonCredit.WRITER), kindsOfWork(credits, knownFor = "Directing"))
        assertEquals(listOf(PersonCredit.WRITER), kindsOfWork(credits, knownFor = "Acting", max = 1))
    }
}
