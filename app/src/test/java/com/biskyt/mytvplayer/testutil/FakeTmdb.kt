package com.biskyt.mytvplayer.testutil

import com.biskyt.mytvplayer.AppContainer
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/**
 * TMDB as the app reads it, for the films and series of [FakeProvider]: Dune (2021) is film 438631, Line of Duty is
 * series 31917. [use] points [AppContainer.tmdb] at it, with a test key (never a real one).
 */
class FakeTmdb : Dispatcher() {
    private val server = MockWebServer().also {
        it.dispatcher = this
        it.start()
    }

    /** The paths asked for, in order. */
    val requests: MutableList<String> = CopyOnWriteArrayList()

    /** Points [c]'s TMDB at this server; with [key], TMDB is on, as with a key in Settings. */
    fun use(c: AppContainer, key: String? = TEST_KEY) = runBlocking {
        c.tmdb.base = server.url("/3/").toString()
        if (key != null) c.settings.update { it.copy(tmdbKey = key) }
    }

    fun close() = server.shutdown()

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.requestUrl!!.encodedPath.removePrefix("/3/")
        requests += path
        val body = when (path) {
            "search/movie" -> """{"results":[{"id":438631,"release_date":"2021-09-15"}]}"""
            "movie/438631" -> DUNE
            "search/tv" -> """{"results":[{"id":31917,"first_air_date":"2012-06-26"}]}"""
            "tv/31917" -> LINE_OF_DUTY
            "tv/31917/season/1" -> SEASON_1
            else -> return MockResponse().setResponseCode(404).setBody("""{"status_code":34}""")
        }
        return MockResponse().setBody(body)
    }

    companion object {
        /** 32 characters, as a v3 key has: not a real key. */
        const val TEST_KEY = "00000000000000000000000000000000"

        const val TRAILER = "trailer0001"

        private const val DUNE =
            """{"id":438631,"imdb_id":"tt1160419","overview":"A noble family becomes embroiled in a war.","vote_average":7.8,""" +
                """"vote_count":12000,"genres":[{"name":"Science Fiction"},{"name":"Adventure"}],"original_title":"Dune: Part One",""" +
                """"release_date":"2021-09-15","production_companies":[{"name":"Legendary Pictures"}],""" +
                """"production_countries":[{"name":"United States of America"}],""" +
                """"credits":{"cast":[{"name":"Timothée Chalamet","character":"Paul Atreides"},""" +
                """{"name":"Rebecca Ferguson","character":"Lady Jessica"}],""" +
                """"crew":[{"name":"Denis Villeneuve","job":"Director"},{"name":"Jon Spaihts","job":"Screenplay"}]},""" +
                """"videos":{"results":[{"key":"$TRAILER","site":"YouTube","type":"Trailer","official":true}]}}"""

        private const val LINE_OF_DUTY =
            """{"id":31917,"overview":"From TMDB.","vote_average":8.2,"vote_count":900,"created_by":[{"name":"Jed Mercurio"}],""" +
                """"networks":[{"name":"BBC Two"}],""" +
                """"credits":{"cast":[{"name":"Martin Compston","character":"Steve Arnott"}]},""" +
                """"external_ids":{"imdb_id":"tt2303687"}}"""

        private const val SEASON_1 =
            """{"episodes":[{"season_number":1,"episode_number":1,"overview":"TMDB's first."},""" +
                """{"season_number":1,"episode_number":2,"overview":"TMDB's second.","air_date":"2012-07-03"}]}"""
    }
}
