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

    /** While set, a season's episodes fail (TMDB out of reach for a moment). */
    @Volatile var seasonsFail = false

    /** How long a season's episodes take to arrive (a slow TMDB), in real time. */
    @Volatile var seasonDelayMs = 0L

    /** While set, a search by name finds nothing: only the provider's TMDB id finds a title. */
    @Volatile var searchFindsNothing = false

    /** Points [c]'s TMDB at this server; with [key], TMDB is on, as with a key in Settings. */
    fun use(c: AppContainer, key: String? = TEST_KEY) = runBlocking {
        c.tmdb.base = server.url("/3/").toString()
        if (key != null) c.settings.update { it.copy(tmdbKey = key) }
    }

    fun close() = server.shutdown()

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.requestUrl!!.encodedPath.removePrefix("/3/")
        requests += path
        if (seasonsFail && "/season/" in path) return MockResponse().setResponseCode(503)
        if ("/season/" in path && seasonDelayMs > 0) Thread.sleep(seasonDelayMs)
        if (searchFindsNothing && path.startsWith("search/")) return MockResponse().setBody("""{"results":[]}""")
        val body = when (path) {
            "search/movie" -> """{"results":[{"id":438631,"release_date":"2021-09-15"}]}"""
            "movie/438631" -> DUNE
            "search/tv" -> """{"results":[{"id":31917,"first_air_date":"2012-06-26"}]}"""
            "tv/31917" -> LINE_OF_DUTY
            "tv/31917/season/1" -> SEASON_1
            "tv/31917/season/1/episode/1/credits" -> EPISODE_1_CREDITS
            "person/$PERSON" -> CHALAMET
            "movie/438631/recommendations" -> DUNE_LIKE
            "tv/31917/recommendations" -> LINE_OF_DUTY_LIKE
            "authentication" -> """{"success":true}"""
            "tv/$HAPPY_VALLEY" -> HAPPY_VALLEY_PAGE
            "tv/$HAPPY_VALLEY/season/3" -> HAPPY_VALLEY_3
            "tv/$HAPPY_VALLEY/season/1" -> """{"episodes":[{"season_number":1,"episode_number":1,"name":"Episode One"}]}"""
            else -> return MockResponse().setResponseCode(404).setBody("""{"status_code":34}""")
        }
        return MockResponse().setBody(body)
    }

    companion object {
        /** Line of Duty's first episode's own cast and crew. */
        private const val EPISODE_1_CREDITS =
            """{"cast":[{"id":504,"name":"Vicky McClure","character":"Kate Fleming"}],""" +
                """"guest_stars":[{"id":503,"name":"Neil Morrissey","character":"Morton"}],""" +
                """"crew":[{"id":501,"name":"David Caffrey","job":"Director"},{"id":502,"name":"Jed Mercurio","job":"Writer"}]}"""

        /** 32 characters, as a v3 key has: not a real key. */
        const val TEST_KEY = "00000000000000000000000000000000"

        const val TRAILER = "trailer0001"

        /** Dune's teaser, for the UK: second in Other trailers. */
        const val TEASER = "teaser00001"

        /** Line of Duty's trailer. */
        const val SERIES_TRAILER = "lodtrailer1"

        /** The provider's id for Bodyguard, a series that a test can add to the playlist ("More like this"). */
        const val BODYGUARD = 79L

        /** When Line of Duty's next episode is shown (series 7, episode 1). */
        const val NEXT_EPISODE = "2027-01-10"

        /** Happy Valley's TMDB id: suggested for Line of Duty, not in the playlist, with a TMDB page. */
        const val HAPPY_VALLEY = 63351

        /** Timothée Chalamet's TMDB id: in Dune's cast, with a page. Rebecca Ferguson has no id there. */
        const val PERSON = 1190668L

        private const val DUNE =
            """{"id":438631,"title":"Dune","imdb_id":"tt1160419",""" +
                """"overview":"A noble family becomes embroiled in a war.","vote_average":7.8,""" +
                """"vote_count":12000,"genres":[{"name":"Science Fiction"},{"name":"Adventure"}],"original_title":"Dune: Part One",""" +
                """"release_date":"2021-09-15","production_companies":[{"name":"Legendary Pictures"}],""" +
                """"production_countries":[{"name":"United States of America"}],""" +
                """"credits":{"cast":[{"id":$PERSON,"name":"Timothée Chalamet","character":"Paul Atreides"},""" +
                """{"name":"Rebecca Ferguson","character":"Lady Jessica"}],""" +
                """"crew":[{"name":"Denis Villeneuve","job":"Director"},{"name":"Jon Spaihts","job":"Screenplay"}]},""" +
                """"videos":{"results":[{"key":"$TRAILER","site":"YouTube","type":"Trailer","official":true},""" +
                """{"key":"$TEASER","site":"YouTube","type":"Teaser","name":"Teaser","iso_3166_1":"GB"}]}}"""

        private const val LINE_OF_DUTY =
            """{"id":31917,"overview":"From TMDB.","vote_average":8.2,"vote_count":900,"created_by":[{"name":"Jed Mercurio"}],""" +
                """"status":"Returning Series","number_of_seasons":6,"number_of_episodes":36,""" +
                """"next_episode_to_air":{"season_number":7,"episode_number":1,"air_date":"$NEXT_EPISODE"},""" +
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
            """{"id":$HAPPY_VALLEY,"name":"Happy Valley","original_name":"Vallée Heureuse",""" +
                """"overview":"A sergeant in West Yorkshire.","first_air_date":"2014-04-29",""" +
                """"vote_average":8.3,"vote_count":500,"genres":[{"name":"Crime"}],"created_by":[{"name":"Sally Wainwright"}],""" +
                """"status":"Ended","number_of_seasons":3,"number_of_episodes":18,""" +
                """"seasons":[{"season_number":0,"name":"Specials","episode_count":1},{"season_number":1,"episode_count":6},""" +
                """{"season_number":3,"name":"Series 3","episode_count":6}],""" +
                """"videos":{"results":[{"key":"hvtrailer01","site":"YouTube","type":"Trailer","official":true}]}}"""

        private const val HAPPY_VALLEY_3 =
            """{"episodes":[{"season_number":3,"episode_number":1,"name":"The Last One","air_date":"2023-01-01"},""" +
                """{"season_number":3,"episode_number":2,"name":"Afterwards"}]}"""

        private const val SEASON_1 =
            """{"episodes":[{"season_number":1,"episode_number":1,"overview":"TMDB's first.","vote_average":8.1,"vote_count":50,""" +
                """"crew":[{"id":501,"name":"David Caffrey","job":"Director"},{"id":502,"name":"Jed Mercurio","job":"Writer"}],""" +
                """"guest_stars":[{"id":503,"name":"Neil Morrissey","character":"Morton"}]},""" +
                """{"season_number":1,"episode_number":2,"overview":"TMDB's second.","air_date":"2012-07-03"}]}"""
    }
}
