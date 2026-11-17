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
 * series 31917. Only those two have suggestions for "More like this". [use] points [AppContainer.tmdb] at it, with a
 * test key (never a real one).
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
            "person/$PERSON" -> CHALAMET
            "movie/438631/recommendations" -> DUNE_LIKE
            "tv/31917/recommendations" -> LINE_OF_DUTY_LIKE
            "tv/$HAPPY_VALLEY" -> HAPPY_VALLEY_PAGE
            else -> return MockResponse().setResponseCode(404).setBody("""{"status_code":34}""")
        }
        return MockResponse().setBody(body)
    }

    companion object {
        /** 32 characters, as a v3 key has: not a real key. */
        const val TEST_KEY = "00000000000000000000000000000000"

        const val TRAILER = "trailer0001"

        /** Line of Duty's trailer. */
        const val SERIES_TRAILER = "lodtrailer1"

        /** The provider's id for Bodyguard, a series that a test can add to the playlist ("More like this"). */
        const val BODYGUARD = 79L

        /** Happy Valley's TMDB id: suggested for Line of Duty, not in the playlist, with a TMDB page. */
        const val HAPPY_VALLEY = 63351

        /** Timothée Chalamet's TMDB id: in Dune's cast, with a page. Rebecca Ferguson has no id there. */
        const val PERSON = 1190668L

        private const val DUNE =
            """{"id":438631,"imdb_id":"tt1160419","overview":"A noble family becomes embroiled in a war.","vote_average":7.8,""" +
                """"vote_count":12000,"genres":[{"name":"Science Fiction"},{"name":"Adventure"}],"original_title":"Dune: Part One",""" +
                """"release_date":"2021-09-15","production_companies":[{"name":"Legendary Pictures"}],""" +
                """"production_countries":[{"name":"United States of America"}],""" +
                """"credits":{"cast":[{"id":$PERSON,"name":"Timothée Chalamet","character":"Paul Atreides"},""" +
                """{"name":"Rebecca Ferguson","character":"Lady Jessica"}],""" +
                """"crew":[{"name":"Denis Villeneuve","job":"Director"},{"name":"Jon Spaihts","job":"Screenplay"}]},""" +
                """"videos":{"results":[{"key":"$TRAILER","site":"YouTube","type":"Trailer","official":true}]}}"""

        private const val LINE_OF_DUTY =
            """{"id":31917,"overview":"From TMDB.","vote_average":8.2,"vote_count":900,"created_by":[{"name":"Jed Mercurio"}],""" +
                """"networks":[{"name":"BBC Two"}],""" +
                """"credits":{"cast":[{"name":"Martin Compston","character":"Steve Arnott"}]},""" +
                """"external_ids":{"imdb_id":"tt2303687"},""" +
                """"videos":{"results":[{"key":"$SERIES_TRAILER","site":"YouTube","type":"Trailer","official":true}]}}"""

        /** In the playlist: Dune (a film) and Line of Duty (a series, a year off). Not in it: Little Women. */
        private const val CHALAMET =
            """{"id":$PERSON,"name":"Timothée Chalamet","biography":"An American actor.","birthday":"1995-12-27",""" +
                """"place_of_birth":"New York City, USA","known_for_department":"Acting","combined_credits":{"cast":[""" +
                """{"id":438631,"media_type":"movie","title":"Dune","character":"Paul Atreides","release_date":"2021-09-15"},""" +
                """{"id":331482,"media_type":"movie","title":"Little Women","character":"Laurie","release_date":"2019-12-25"},""" +
                """{"id":31917,"media_type":"tv","name":"Line of Duty","character":"Guest","first_air_date":"2013-06-26"}],""" +
                """"crew":[]}}"""

        /** More like Dune: Paddington 2 is in the playlist, Arrival is not. */
        private const val DUNE_LIKE =
            """{"page":1,"results":[{"id":346648,"title":"Paddington 2","release_date":"2017-11-09"},""" +
                """{"id":329865,"title":"Arrival","release_date":"2016-11-10","vote_average":7.6,"vote_count":19000}]}"""

        /**
         * More like Line of Duty: Bodyguard (with no date) is in the playlist when a test adds it as series
         * [BODYGUARD]. Happy Valley is not in the playlist.
         */
        private const val LINE_OF_DUTY_LIKE =
            """{"page":1,"results":[{"id":76773,"name":"Bodyguard","vote_average":7.9,"vote_count":1500},""" +
                """{"id":$HAPPY_VALLEY,"name":"Happy Valley","first_air_date":"2014-04-29","vote_average":8.3,"vote_count":500}]}"""

        private const val HAPPY_VALLEY_PAGE =
            """{"id":$HAPPY_VALLEY,"name":"Happy Valley","overview":"A sergeant in West Yorkshire.","first_air_date":"2014-04-29",""" +
                """"vote_average":8.3,"vote_count":500,"genres":[{"name":"Crime"}],"created_by":[{"name":"Sally Wainwright"}],""" +
                """"videos":{"results":[{"key":"hvtrailer01","site":"YouTube","type":"Trailer","official":true}]}}"""

        private const val SEASON_1 =
            """{"episodes":[{"season_number":1,"episode_number":1,"overview":"TMDB's first."},""" +
                """{"season_number":1,"episode_number":2,"overview":"TMDB's second.","air_date":"2012-07-03"}]}"""
    }
}
