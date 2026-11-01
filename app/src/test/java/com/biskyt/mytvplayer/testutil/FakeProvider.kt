package com.biskyt.mytvplayer.testutil

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPOutputStream
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer

/** A fake Xtream Codes provider (with an XMLTV guide) for tests. Channel lists can be changed between requests. */
class FakeProvider(private val now: Long = System.currentTimeMillis()) : Dispatcher() {
    val server = MockWebServer().apply { dispatcher = this@FakeProvider }
    val url: String get() = server.url("/").toString()

    var categories = listOf("1" to "UK | ENTERTAINMENT", "2" to "UK | SPORT", "3" to "FR: CINEMA", "4" to "|AR| NEWS")

    /** stream id, number, name, category id, provider epg id */
    var streams = listOf(
        Stream(101, 1, "UK: CHANNEL ONE HD", "1", "one.uk", archiveDays = 3),
        Stream(102, 2, "UK: ARENA SPRTS 1 FHD", "2", null),
        Stream(103, 3, "UK: KITE KIDZ", "1", null),
        Stream(201, 201, "FR: CINE PLUS", "3", null),
        Stream(301, 301, "AR: NEWS 24", "4", null),
    )

    /** How long catch-up listings take to arrive (a slow provider). */
    var catchupDelayMs = 0L

    /** How long a series' details (get_series_info) take to arrive. */
    var seriesInfoDelayMs = 0L

    /** How long the channel list (get_live_streams) takes to arrive. Under 5 s: [close] waits that long at most. */
    var streamsDelayMs = 0L

    data class Stream(
        val id: Long,
        val num: Int,
        val name: String,
        val cat: String,
        val epgId: String?,
        val archiveDays: Int = 0,
        val logo: String = ""
    )

    /** Images served at /art/<name> (logos and posters for the store screenshots), by name. */
    var images: (String) -> ByteArray? = { null }

    /** M3U movie and series playlists, served at /vod.m3u and /series.m3u. */
    var vodM3u = ""
    var seriesM3u = ""

    /** Live streams that connect but then send next to nothing (a few bytes a second), never enough to play. */
    var streamsTrickle = false

    /** Films whose server answers "OK" with an empty file, as a provider does for a copy it no longer has. */
    var emptyFilms = false

    /** The guide (xmltv.php) fails with a server error. */
    var guideFails = false

    /** A panel that serves M3U playlists but refuses Xtream logins. */
    var loginsRefused = false

    /** The address of an image served at /art/<name>. */
    fun art(name: String) = "${url}art/$name"

    /** A movie's poster address. */
    var moviePoster: (Long) -> String = { "http://img/$it.jpg" }

    /** A series' cover address. */
    var seriesCover: (Long) -> String = { "http://img/s$it.jpg" }

    /** Every series' release date. */
    var seriesReleaseDate = "2012-06-26"

    /** A movie's details (get_vod_info), by its id. */
    var movieInfo: (String) -> String = { id ->
        """{"info":{"plot":"A story.","cast":"Someone","genre":"Drama","releasedate":"2021-09-15","duration_secs":6000,""" +
            """"backdrop_path":["http://img/back.jpg"]},"movie_data":{"stream_id":$id,"container_extension":"mkv"}}"""
    }

    /** A series' details, seasons and episodes (get_series_info), by its id. */
    var seriesInfo: (String) -> String = {
        """{"seasons":[{"season_number":1,"name":"Series 1"},{"season_number":2,"name":"Series 2"}],
           "info":{"plot":"Anti-corruption.","genre":"Crime","episode_run_time":"60"},
           "episodes":{"1":[{"id":"9001","episode_num":1,"title":"Episode 1","container_extension":"mkv","season":1,"info":{"plot":"First.","air_date":"2014-06-26","duration_secs":3600}},
                            {"id":"9002","episode_num":2,"title":"Episode 2","container_extension":"mkv","season":1,"info":{"duration_secs":3500}}],
                       "2":[{"id":"9003","episode_num":1,"title":"Episode 1","container_extension":"mp4","season":2,"info":{"releasedate":"2014-07-03"}}]}}"""
    }

    /**
     * Catch-up listing for any channel (times from now, in hours): one from yesterday, one earlier today, one on now,
     * one too old to replay and one the provider didn't keep. Server time is UTC.
     */
    fun catchupListing(): String {
        val hour = 3_600_000L
        fun entry(title: String, fromHours: Double, toHours: Double, archive: Int): String {
            val start = now + (fromHours * hour).toLong()
            val stop = now + (toHours * hour).toLong()
            val local = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date(start))
            val b64 = { s: String -> java.util.Base64.getEncoder().encodeToString(s.toByteArray()) }
            return """{"title":"${b64(title)}","description":"${b64("About $title")}","start":"$local",""" +
                """"start_timestamp":"${start / 1000}","stop_timestamp":"${stop / 1000}","has_archive":$archive}"""
        }
        return listOf(
            entry("Long Ago", -24.0 * 10, -24.0 * 10 + 1, 1),
            entry("Yesterday Show", -26.0, -25.0, 1),
            entry("Not Kept", -4.0, -3.0, 0),
            entry("Earlier Today", -2.0, -1.0, 1),
            entry("On Now", -0.5, 0.5, 1),
            entry("Later", 0.5, 1.5, 0)
        ).joinToString(",", """{"epg_listings":[""", "]}")
    }

    var movieCategories = listOf("10" to "UK | MOVIES", "11" to "FR | FILMS", "12" to "ACTION")

    /** Movies: id to (raw name, category id, added epoch seconds). */
    var movies = listOf(
        Triple(501L, "EN - Dune (2021)", "10"),
        Triple(502L, "|UK| Paddington 2 (2017)", "10"),
        Triple(601L, "FR - Amélie (2001)", "11"),
        Triple(701L, "Heat (1995)", "12"),
    )

    var seriesCategories = listOf("20" to "UK | DRAMA", "21" to "FR | SERIES")

    var series = listOf(Triple(77L, "UK - Line of Duty", "20"), Triple(78L, "FR - Lupin", "21"))

    val requests: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()

    /** An iptv-org style playlist, grouped by country. */
    var m3u = """
        #EXTM3U
        #EXTINF:-1 tvg-id="ChannelOne.uk@SD" tvg-logo="http://logo/one.png" group-title="United Kingdom",Channel One
        http://stream.example/one.m3u8
        #EXTINF:-1 tvg-id="KiteKids.uk@HD" group-title="United Kingdom" http-user-agent="Agent/2",Kite Kids (720p)
        http://stream.example/kite.m3u8
        #EXTINF:-1 tvg-id="CinePlus.fr@SD" group-title="France",Ciné+
        http://stream.example/cine.m3u8
        #EXTINF:-1 group-title="International",World News
        http://stream.example/world.m3u8
    """.trimIndent()

    override fun dispatch(request: RecordedRequest): MockResponse {
        val url = request.requestUrl!!
        requests += url.encodedPath + "?" + (url.queryParameter("action") ?: "")
        val early = when {
            url.encodedPath == "/free.m3u" -> MockResponse().setBody(m3u)
            url.encodedPath == "/not-a-playlist" -> MockResponse().setBody("<html>hello</html>")
            url.encodedPath == "/vod.m3u" -> MockResponse().setBody(vodM3u)
            url.encodedPath == "/series.m3u" -> MockResponse().setBody(seriesM3u)
            loginsRefused && url.encodedPath.endsWith("player_api.php") -> MockResponse().setResponseCode(403)
            url.encodedPath.startsWith("/art/") ->
                images(url.encodedPath.removePrefix("/art/"))?.let { MockResponse().setBody(Buffer().write(it)) }
                    ?: MockResponse().setResponseCode(404)
            streamsTrickle && url.encodedPath.startsWith("/live/") ->
                // MPEG-TS sync bytes: the player takes it for a transport stream and waits for more, never getting a picture.
                MockResponse().setBody(Buffer().write(ByteArray(10_000) { 0x47 }))
                    .throttleBody(1, 200, java.util.concurrent.TimeUnit.MILLISECONDS)
            emptyFilms && url.encodedPath.startsWith("/movie/") -> MockResponse().setBody("")
            url.queryParameter("password") != "secret" -> MockResponse().setBody("""{"user_info":{"auth":0}}""")
            else -> null
        }
        if (early != null) return early
        return when {
            guideFails && url.encodedPath.endsWith("xmltv.php") -> MockResponse().setResponseCode(500)
            url.encodedPath.endsWith("xmltv.php") -> {
                // Like real servers: a version tag, and "not modified" when the guide asked for hasn't changed.
                val guide = xmltv()
                val tag = "\"${guide.hashCode()}\""
                if (request.getHeader("If-None-Match") == tag) {
                    MockResponse().setResponseCode(304)
                } else {
                    MockResponse().setHeader("ETag", tag).setBody(Buffer().write(gzip(guide)))
                }
            }
            url.encodedPath.endsWith("get.php") -> MockResponse().setBody(m3u)
            url.queryParameter("action") == null -> MockResponse().setBody(
                """{"user_info":{"auth":1,"status":"Active","exp_date":"1900000000","max_connections":"1"}}""",
            )
            url.queryParameter("action") == "get_live_categories" ->
                MockResponse().setBody(
                    categories.joinToString(",", "[", "]") { (id, name) ->
                        """{"category_id":"$id","category_name":"$name"}"""
                    }
                )
            url.queryParameter("action") == "get_live_streams" -> {
                val cat = url.queryParameter("category_id")
                val list = streams.filter { cat == null || it.cat == cat }
                MockResponse().setBody(
                    list.joinToString(",", "[", "]") {
                        """{"num":${it.num},"name":"${it.name}","stream_id":${it.id},"category_id":"${it.cat}",""" +
                            """"stream_icon":"${it.logo}","epg_channel_id":${it.epgId?.let { e -> "\"$e\"" } ?: "null"},""" +
                            """"tv_archive":${if (it.archiveDays > 0) 1 else 0},"tv_archive_duration":"${it.archiveDays}"}"""
                    },
                ).setBodyDelay(streamsDelayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
            url.queryParameter("action") == "get_simple_data_table" ->
                MockResponse().setBody(catchupListing()).setBodyDelay(catchupDelayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            else -> vodResponse(url) ?: MockResponse().setResponseCode(404)
        }
    }

    /** Movie and series endpoints. */
    private fun vodResponse(url: okhttp3.HttpUrl): MockResponse? = when (url.queryParameter("action")) {
        "get_vod_categories" -> categoryList(movieCategories)
        "get_series_categories" -> categoryList(seriesCategories)
        "get_vod_streams" -> {
            val cat = url.queryParameter("category_id")
            MockResponse().setBody(
                movies.filter { cat == null || it.third == cat }.withIndex().joinToString(",", "[", "]") { (i, m) ->
                    """{"num":${i + 1},"name":"${m.second}","stream_id":${m.first},"category_id":"${m.third}",""" +
                        """"stream_icon":"${moviePoster(
                            m.first
                        )}","rating":"7.5","added":"${1_700_000_000 + m.first}","container_extension":"mkv"}"""
                }
            )
        }
        "get_series" -> {
            val cat = url.queryParameter("category_id")
            MockResponse().setBody(
                series.filter { cat == null || it.third == cat }.withIndex().joinToString(",", "[", "]") { (i, s) ->
                    """{"num":${i + 1},"name":"${s.second}","series_id":${s.first},"category_id":"${s.third}",""" +
                        """"cover":"${seriesCover(
                            s.first
                        )}","releaseDate":"$seriesReleaseDate","last_modified":"${1_690_000_000 + s.first}"}"""
                }
            )
        }
        "get_vod_info" -> MockResponse().setBody(movieInfo(url.queryParameter("vod_id")!!))
        "get_series_info" -> MockResponse().setBody(seriesInfo(url.queryParameter("series_id")!!))
            .setBodyDelay(seriesInfoDelayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        else -> null
    }

    private fun categoryList(list: List<Pair<String, String>>) =
        MockResponse().setBody(list.joinToString(",", "[", "]") { (id, name) -> """{"category_id":"$id","category_name":"$name"}""" })

    private val fmt = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    private fun t(offsetMin: Int) = fmt.format(Date(now + offsetMin * 60_000L))

    /** Guide channels (id to name); each has a programme on now and one next. */
    var guideChannels = listOf(
        "one.uk" to "Channel One",
        "arena1.uk" to "Arena Sports 1 HD",
        "kite.uk" to "Kite Kids",
        "cine.fr" to "Ciné+"
    )

    /** Guide channel id to the inside of its "now" programme, replacing "Now on <id>" (e.g. a series episode). */
    var nowProgramme = mapOf<String, String>()

    /** Guide channel id to the title of its "next" programme, replacing "Next on <id>". */
    var nextTitle = mapOf<String, String>()

    /** Guide channel id to minutes its programmes are moved by (else every channel's start at the same times). */
    var guideShiftMin = mapOf<String, Int>()

    /** Hours of programmes after the next one: "Later 1 on <id>", "Later 2 on <id>"…, an hour each (a full guide). */
    var laterHours = 0

    /** Each channel followed by its programmes, as some joined-up guides are (against the XMLTV format). */
    var interleavedGuide = false

    fun xmltv(): String = buildString {
        append("<?xml version=\"1.0\"?><tv>")
        fun channel(id: String, name: String) = append("<channel id=\"$id\"><display-name>$name</display-name></channel>")
        fun programmes(id: String) {
            val shift = guideShiftMin[id] ?: 0
            fun at(offsetMin: Int) = t(offsetMin + shift)
            append(
                "<programme start=\"${at(-30)}\" stop=\"${at(30)}\" channel=\"$id\">" +
                    "${nowProgramme[id] ?: "<title>Now on $id</title><desc>About $id</desc>"}</programme>"
            )
            append(
                "<programme start=\"${at(
                    30
                )}\" stop=\"${at(90)}\" channel=\"$id\"><title>${nextTitle[id] ?: "Next on $id"}</title></programme>"
            )
            for (k in 1..laterHours) {
                append(
                    "<programme start=\"${at(
                        30 + 60 * k
                    )}\" stop=\"${at(90 + 60 * k)}\" channel=\"$id\"><title>Later $k on $id</title></programme>"
                )
            }
        }
        if (interleavedGuide) {
            for ((id, name) in guideChannels) {
                channel(id, name)
                programmes(id)
            }
        } else {
            for ((id, name) in guideChannels) channel(id, name)
            for ((id, _) in guideChannels) programmes(id)
        }
        append("</tv>")
    }

    private fun gzip(text: String): ByteArray = java.io.ByteArrayOutputStream().also { out ->
        GZIPOutputStream(out).use { it.write(text.toByteArray()) }
    }.toByteArray()

    fun close() = server.shutdown()
}
