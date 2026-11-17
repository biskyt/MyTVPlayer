package com.biskyt.mytvplayer.core.tmdb

import com.biskyt.mytvplayer.core.vod.CastMember
import com.biskyt.mytvplayer.core.vod.TitleLink
import com.biskyt.mytvplayer.core.xtream.VodDetails
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TmdbClientTest {
    private val server = MockWebServer()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.shutdown()

    private fun client(key: String = "0123456789abcdef0123456789abcdef") =
        TmdbClient(OkHttpClient(), key, "en-GB", server.url("/3/").toString())

    private fun reply(body: String, code: Int = 200) = server.enqueue(MockResponse().setResponseCode(code).setBody(body))

    @Test
    fun `a film's cast, crew, trailer, links and score`() = runBlocking {
        reply(
            """{"id":603,"imdb_id":"tt0133093","overview":"A hacker learns the truth.","vote_average":8.2,"vote_count":26000,
            "poster_path":"/p.jpg","backdrop_path":"/b.jpg","genres":[{"id":1,"name":"Action"}],"original_title":"The Matrix",
            "release_date":"1999-03-31","production_companies":[{"name":"Village Roadshow"}],"production_countries":[{"name":"United States"}],
            "credits":{"cast":[{"name":"Keanu Reeves","character":"Neo","profile_path":"/k.jpg"},{"name":"Carrie-Anne Moss","character":""}],
            "crew":[{"name":"Lana Wachowski","job":"Director"},{"name":"Lana Wachowski","job":"Writer"},{"name":"Joel Silver","job":"Producer"}]},
            "videos":{"results":[{"key":"teaser00001","site":"YouTube","type":"Teaser","official":true},
            {"key":"trailer0001","site":"YouTube","type":"Trailer","official":false},
            {"key":"trailer0002","site":"YouTube","type":"Trailer","official":true},{"key":"vimeo","site":"Vimeo","type":"Trailer","official":true}]}}"""
        )
        val t = client().movie(603)!!
        assertEquals("tt0133093", t.imdbId)
        assertEquals("https://www.imdb.com/title/tt0133093/", t.imdbUrl)
        assertEquals("https://www.themoviedb.org/movie/603", t.tmdbUrl)
        assertEquals(listOf("IMDb", "TMDB"), t.links.map { it.name })
        assertEquals(8.2, t.score!!, 0.0)
        assertEquals(
            listOf(CastMember("Keanu Reeves", "Neo", "https://image.tmdb.org/t/p/w185/k.jpg"), CastMember("Carrie-Anne Moss", null, null)),
            t.cast
        )
        assertEquals(listOf("Lana Wachowski"), t.directors.map { it.name })
        assertEquals(listOf("Lana Wachowski"), t.writers.map { it.name })
        assertEquals("trailer0002", t.trailer) // the official trailer
        assertEquals(LocalDate.of(1999, 3, 31), t.released)
        assertEquals(listOf("Action"), t.genres)
        val request = server.takeRequest()
        assertEquals("/3/movie/603", request.requestUrl!!.encodedPath)
        assertEquals("credits,videos,external_ids", request.requestUrl!!.queryParameter("append_to_response"))
        assertEquals("en-GB", request.requestUrl!!.queryParameter("language"))
        assertEquals("en,en,null", request.requestUrl!!.queryParameter("include_video_language"))
    }

    @Test
    fun `a series' creators are its writers, and its IMDb id comes from its external ids`() = runBlocking {
        reply(
            """{"id":1399,"vote_average":8.4,"vote_count":5,"created_by":[{"name":"David Benioff"}],"external_ids":{"imdb_id":"tt0944947"}}"""
        )
        val t = client().series(1399)!!
        assertEquals(listOf("David Benioff"), t.writers.map { it.name })
        assertEquals("tt0944947", t.imdbId)
        assertEquals("https://www.themoviedb.org/tv/1399", t.tmdbUrl)
        assertNull(t.score) // too few votes to mean much
        assertEquals(listOf(TitleLink("IMDb", "https://www.imdb.com/title/tt0944947/"), TitleLink("TMDB", t.tmdbUrl)), t.links)
        assertNull(t.trailer)
    }

    @Test
    fun `a season's episodes`() = runBlocking {
        reply(
            """{"episodes":[{"season_number":1,"episode_number":1,"name":"Winter Is Coming","overview":"Lord Stark...","still_path":"/s.jpg",
            "air_date":"2011-04-17"},{"season_number":1,"episode_number":2,"overview":""}]}"""
        )
        assertEquals(
            listOf(
                TmdbEpisode(1, 1, "Winter Is Coming", "Lord Stark...", "https://image.tmdb.org/t/p/w300/s.jpg", LocalDate.of(2011, 4, 17)),
                TmdbEpisode(1, 2, null, null, null, null)
            ),
            client().season(1399, 1)
        )
        assertEquals("/3/tv/1399/season/1", server.takeRequest().requestUrl!!.encodedPath)
    }

    @Test
    fun `a search prefers a hit from the title's year`() = runBlocking {
        reply("""{"results":[{"id":1,"release_date":"2021-10-22"},{"id":2,"release_date":"1984-12-14"}]}""")
        assertEquals(2, client().findMovie("Dune", 1984))
        assertEquals("1984", server.takeRequest().requestUrl!!.queryParameter("year"))
        reply("""{"results":[{"id":1,"release_date":"2021-10-22"}]}""")
        assertEquals(1, client().findMovie("Dune", null))
        reply("""{"results":[]}""")
        reply("""{"results":[]}""") // and again without the year
        assertNull(client().findSeries("Nothing", 2000))
    }

    private fun details(year: Int? = null, tmdbId: Int? = null) =
        VodDetails(null, null, null, null, year, null, null, null, null, tmdbId = tmdbId)

    @Test
    fun `a title is matched by the provider's TMDB id, without a search`() = runBlocking {
        reply("""{"id":42}""")
        assertEquals(42, client().match(series = false, "EN - Dune (2021)", details(tmdbId = 42))!!.id)
        assertEquals("/3/movie/42", server.takeRequest().requestUrl!!.encodedPath)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `without the provider's id, a film is found by its name without tags, and the year in its name`() = runBlocking {
        reply("""{"results":[{"id":7,"release_date":"2021-09-15"}]}""")
        reply("""{"id":7}""")
        assertEquals(7, client().match(series = false, "EN - Dune (2021)", details(year = 1984))!!.id)
        val search = server.takeRequest().requestUrl!!
        assertEquals("/3/search/movie", search.encodedPath)
        assertEquals("Dune", search.queryParameter("query"))
        assertEquals("2021", search.queryParameter("year"))
        assertEquals("/3/movie/7", server.takeRequest().requestUrl!!.encodedPath)
    }

    @Test
    fun `a series is found with the provider's year when its name has none, and not at all when TMDB has none`() = runBlocking {
        reply("""{"results":[{"id":9,"first_air_date":"2012-06-26"}]}""")
        reply("""{"id":9}""")
        assertEquals(9, client().match(series = true, "|UK| Line of Duty", details(year = 2012))!!.id)
        val search = server.takeRequest().requestUrl!!
        assertEquals("/3/search/tv", search.encodedPath)
        assertEquals("Line of Duty", search.queryParameter("query"))
        assertEquals("2012", search.queryParameter("first_air_date_year"))
        assertEquals("/3/tv/9", server.takeRequest().requestUrl!!.encodedPath)
        reply("""{"results":[]}""")
        assertNull(client().match(series = true, "Nothing", null))
    }

    @Test
    fun `a v3 key goes in the address, a v4 token in the Authorization header`() = runBlocking {
        reply("{}")
        client().keyWorks()
        val v3 = server.takeRequest()
        assertEquals("0123456789abcdef0123456789abcdef", v3.requestUrl!!.queryParameter("api_key"))
        assertNull(v3.getHeader("Authorization"))
        reply("{}")
        val token = "eyJhbGciOiJIUzI1NiJ9." + "a".repeat(60)
        client(token).keyWorks()
        val v4 = server.takeRequest()
        assertNull(v4.requestUrl!!.queryParameter("api_key"))
        assertEquals("Bearer $token", v4.getHeader("Authorization"))
    }

    @Test
    fun `a refused key, a missing title and a broken answer`() = runBlocking {
        reply("""{"status_code":7}""", 401)
        assertFalse(client().keyWorks())
        reply("{}", 404)
        assertNull(client().movie(1))
        reply("not json")
        val e = runCatching { client().movie(1) }.exceptionOrNull()
        assertTrue(e is TmdbException && !e.invalidKey)
        reply("", 500)
        assertTrue(runCatching { client().keyWorks() }.exceptionOrNull() is TmdbException)
    }

    @Test
    fun `a provider's TMDB id that TMDB does not have falls back to a search by name`() = runBlocking {
        reply("{}", 404)
        reply("""{"results":[{"id":8}]}""")
        reply("""{"id":8}""")
        assertEquals(8, client().match(series = true, "Dune", details(tmdbId = 77))!!.id)
        assertEquals("/3/tv/77", server.takeRequest().requestUrl!!.encodedPath)
        assertEquals("/3/search/tv", server.takeRequest().requestUrl!!.encodedPath)
    }

    @Test
    fun `a search with a year that finds nothing is made again without the year`() = runBlocking {
        reply("""{"results":[]}""")
        reply("""{"results":[{"id":5,"release_date":"2021-09-15"}]}""")
        assertEquals(5, client().findMovie("Dune", 2020))
        assertEquals("2020", server.takeRequest().requestUrl!!.queryParameter("year"))
        assertNull(server.takeRequest().requestUrl!!.queryParameter("year"))
    }

    @Test
    fun `the key check is never answered from a cache`() = runBlocking {
        reply("{}")
        client().keyWorks()
        assertEquals("no-cache", server.takeRequest().getHeader("Cache-Control"))
    }

    @Test
    fun `a series' recommendations, in TMDB's order, without the series itself`() = runBlocking {
        reply(
            """{"page":1,"results":[{"id":2316,"name":"The Office","original_name":"The Office","first_air_date":"2005-03-24",
            "poster_path":"/o.jpg","vote_average":8.6,"vote_count":4000},{"id":1399,"name":"Itself"},
            {"id":4614,"name":"Engrenages","original_name":"Engrenages","first_air_date":"","vote_average":7.5,"vote_count":3},
            {"id":5,"name":""},{"id":71446,"name":"La casa de papel","original_name":"La casa de papel","first_air_date":"2017-05-02"},
            {"id":2316,"name":"The Office"}]}"""
        )
        val s = client().recommendations(series = true, 1399)
        assertEquals(
            listOf(
                TmdbSuggestion(2316, true, "The Office", null, 2005, "https://image.tmdb.org/t/p/w342/o.jpg", 8.6),
                TmdbSuggestion(4614, true, "Engrenages", null, null, null, null), // too few votes for a score
                TmdbSuggestion(71446, true, "La casa de papel", null, 2017, null, null),
            ),
            s
        )
        assertEquals("/3/tv/1399/recommendations", server.takeRequest().requestUrl!!.encodedPath)
        assertEquals(1, server.requestCount) // recommendations were found: no need for similar titles
    }

    @Test
    fun `a film with no recommendations gets its similar films`() = runBlocking {
        reply("""{"page":1,"results":[]}""")
        reply("""{"results":[{"id":9,"title":"Le Fabuleux Destin","original_title":"Amélie","release_date":"2001-04-25"}]}""")
        val s = client().recommendations(series = false, 194)
        assertEquals(listOf(TmdbSuggestion(9, false, "Le Fabuleux Destin", "Amélie", 2001, null, null)), s)
        assertEquals("/3/movie/194/recommendations", server.takeRequest().requestUrl!!.encodedPath)
        assertEquals("/3/movie/194/similar", server.takeRequest().requestUrl!!.encodedPath)
    }

    @Test
    fun `a title TMDB does not have has no suggestions`() = runBlocking {
        reply("{}", 404)
        reply("{}", 404)
        assertEquals(emptyList<TmdbSuggestion>(), client().recommendations(series = true, 1))
        assertEquals(2, server.requestCount)
    }
}
