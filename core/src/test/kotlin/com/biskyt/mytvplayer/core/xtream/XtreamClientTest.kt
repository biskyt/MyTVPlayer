package com.biskyt.mytvplayer.core.xtream

import com.biskyt.mytvplayer.core.net.ServerReply
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class XtreamClientTest {
    private val server = MockWebServer()
    private lateinit var client: XtreamClient

    @Before
    fun setUp() {
        server.start()
        client = XtreamClient(OkHttpClient(), XtreamCredentials(server.url("/").toString(), "user one", "p@ss"))
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `account parses user info and sends credentials`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"user_info":{"auth":1,"status":"Active","exp_date":"1800000000","max_connections":"2","active_cons":0},"server_info":{}}"""
            )
        )
        val account = client.account()
        assertTrue(account.active)
        assertEquals(1_800_000_000L, account.expiresAtEpochSec)
        assertEquals(2, account.maxConnections)
        val url = server.takeRequest().requestUrl!!
        assertEquals("user one", url.queryParameter("username"))
        assertEquals("p@ss", url.queryParameter("password"))
        assertNull(url.queryParameter("action"))
    }

    @Test
    fun `a refusal keeps what the server said, so that the user can tell who refused`() = runTest {
        val page = """
            <!DOCTYPE html><html><head><title>Attention Required!</title><style>body { margin: 0 }</style></head>
            <body><script>var a = 1;</script>
              <h1>Sorry, you have been blocked</h1><p>You are unable to access this site.</p>
            </body></html>
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(403).setHeader("Server", "cloudflare").setBody(page))
        val e = expectXtreamError(XtreamException.Reason.SERVER_ERROR) { client.account() }
        assertEquals(403, e.code)
        assertEquals(ServerReply(server = "cloudflare", text = "Sorry, you have been blocked"), e.reply)
        assertTrue(e.message!!, "Sorry, you have been blocked" in e.message!!)

        // A short plain answer, with no Server header.
        server.enqueue(MockResponse().setResponseCode(403).setBody("\n  Access denied  \n"))
        assertEquals(ServerReply(text = "Access denied"), expectXtreamError(XtreamException.Reason.SERVER_ERROR) { client.account() }.reply)
    }

    @Test
    fun `a refusal from another host after a redirect names that host`() {
        val asked = "http://panel.example:8080/player_api.php".toHttpUrl()
        val response = Response.Builder()
            .request(Request.Builder().url("https://other.example/blocked").build())
            .protocol(Protocol.HTTP_1_1)
            .code(403)
            .message("Forbidden")
            .body("".toResponseBody())
            .build()
        assertEquals(ServerReply(host = "other.example"), ServerReply.of(response, asked))
    }

    @Test
    fun `failed login gives a friendly error`() = runTest {
        server.enqueue(MockResponse().setBody("""{"user_info":{"auth":0}}"""))
        assertFalse(client.account().active)
        server.enqueue(MockResponse().setBody("[]"))
        expectXtreamError(XtreamException.Reason.LOGIN_FAILED) { client.account() }
    }

    @Test
    fun `http error and garbage are reported as XtreamException`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        val plain = expectXtreamError(XtreamException.Reason.SERVER_ERROR) { client.liveCategories() }
        assertEquals(503, plain.code)
        assertNull("nothing said", plain.reply)
        server.enqueue(MockResponse().setBody("<html>not json</html>"))
        expectXtreamError(XtreamException.Reason.UNEXPECTED_RESPONSE) { client.liveCategories() }
    }

    @Test
    fun `categories and streams tolerate mixed types`() = runTest {
        server.enqueue(MockResponse().setBody("""[{"category_id":"5","category_name":"UK | SPORT","parent_id":0},{"category_id":6}]"""))
        val cats = client.liveCategories()
        assertEquals(listOf(XtreamCategory("5", "UK | SPORT"), XtreamCategory("6", "6")), cats)

        server.enqueue(
            MockResponse().setBody(
                """[
                  {"num":1,"name":"UK: ARENA 1 HD","stream_id":"101","stream_icon":"","epg_channel_id":null,"category_id":"5","tv_archive":1,"tv_archive_duration":"7"},
                  {"num":"2","name":" Kite Kids ","stream_id":102,"stream_icon":"http://x/logo.png","epg_channel_id":"kite.uk","category_id":5,"tv_archive":"0"},
                  {"num":3,"name":"US: NBC","stream_id":103,"category_id":5,"tv_archive":1,"tv_archive_duration":""},
                  {"num":4,"name":"  ","stream_id":104,"category_id":5},
                  {"name":"broken, no id"}
                ]"""
            )
        )
        val streams = mutableListOf<XtreamLiveStream>()
        val count = client.liveStreams("5") { streams += it }
        assertEquals(4, count)
        assertEquals(XtreamLiveStream(101, 1, "UK: ARENA 1 HD", "5", null, null, 7), streams[0])
        assertEquals(XtreamLiveStream(102, 2, "Kite Kids", "5", "http://x/logo.png", "kite.uk", 0), streams[1])
        // Catch-up without a length: taken as a week.
        assertEquals(7, streams[2].archiveDays)
        // No name: its number, which needs no translating (no English "Channel 4" saved).
        assertEquals("#4", streams[3].name)
        server.takeRequest()
        assertEquals("5", server.takeRequest().requestUrl!!.queryParameter("category_id"))
    }

    @Test
    fun `large stream list is decoded incrementally`() = runTest {
        val body = Buffer().writeUtf8("[")
        repeat(5000) { i ->
            if (i > 0) body.writeUtf8(",")
            body.writeUtf8("""{"num":$i,"name":"Channel $i","stream_id":$i,"category_id":"${i % 400}","stream_icon":"","tv_archive":0}""")
        }
        body.writeUtf8("]")
        server.enqueue(MockResponse().setBody(body.copy()))
        var seen = 0
        var last = -1L
        client.liveStreams {
            seen++
            last = it.streamId
        }
        assertEquals(5000, seen)
        assertEquals(4999L, last)

        // Cut off halfway: the channels before the cut have already arrived, so the list wasn't read whole first.
        server.enqueue(MockResponse().setBody(body).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        seen = 0
        runCatching { client.liveStreams { seen++ } }
        assertTrue("$seen", seen in 1 until 5000)
    }

    @Test
    fun `stream and xmltv urls are built from credentials`() {
        val c = XtreamClient(OkHttpClient(), XtreamCredentials("example.tv:8080/", "a b", "x/y"))
        assertEquals("http://example.tv:8080/live/a%20b/x%2Fy/42.ts", c.liveStreamUrl(42))
        assertEquals("http://example.tv:8080/xmltv.php?username=a%20b&password=x%2Fy", c.xmltvUrl())
    }

    /** Runs [block], which must fail with an XtreamException for [reason]; returns it. */
    private suspend fun expectXtreamError(reason: XtreamException.Reason, block: suspend () -> Unit): XtreamException {
        try {
            block()
        } catch (e: XtreamException) {
            assertEquals(reason, e.reason)
            return e
        }
        throw AssertionError("Expected XtreamException")
    }

    @Test
    fun `movies and series lists stream with tolerant types`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """[{"num":1,"name":"EN - Dune (2021)","stream_id":"501","stream_icon":"http://p/d.jpg","rating":"8.1",
                   "added":"1700000000","category_id":"10","container_extension":"mkv"},
                   {"name":"broken, no id"},
                   {"num":2,"name":"Up","stream_id":502,"rating":"","added":null,"category_id":10}]"""
            )
        )
        val movies = mutableListOf<XtreamMovie>()
        assertEquals(2, client.movies("10") { movies += it })
        assertEquals("get_vod_streams", server.takeRequest().requestUrl!!.queryParameter("action"))
        assertEquals(XtreamMovie(501, 1, "EN - Dune (2021)", "10", "http://p/d.jpg", 8.1, 1_700_000_000, "mkv"), movies[0])
        assertNull(movies[1].rating)
        assertEquals("mp4", movies[1].extension)

        server.enqueue(
            MockResponse().setBody(
                """[{"num":"3","name":"Line of Duty","series_id":"77","cover":"http://p/l.jpg","rating":"8.7",
                   "releaseDate":"2012-06-26","last_modified":"1690000000","category_id":"20"}]"""
            )
        )
        val series = mutableListOf<XtreamSeries>()
        client.series { series += it }
        assertEquals(XtreamSeries(77, 3, "Line of Duty", "20", "http://p/l.jpg", 8.7, 2012, 1_690_000_000), series.single())
    }

    @Test
    fun `movie and series details, including empty info and episodes grouped by season`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"info":{"plot":"Spice.","cast":"Timothée","releasedate":"2021-09-15","duration_secs":9300,
                   "backdrop_path":["http://p/b.jpg"],"movie_image":"http://p/m.jpg","rating":"8"},
                   "movie_data":{"stream_id":501,"container_extension":"mkv"}}"""
            )
        )
        val movie = client.movieDetails(501)
        assertEquals("501", server.takeRequest().requestUrl!!.queryParameter("vod_id"))
        assertEquals(2021, movie.year)
        assertEquals(9300, movie.durationSec)
        assertEquals("http://p/b.jpg", movie.backdropUrl)

        server.enqueue(MockResponse().setBody("""{"info":[],"movie_data":{}}"""))
        assertNull(client.movieDetails(502).plot)

        server.enqueue(
            MockResponse().setBody(
                """{"seasons":[],"info":{"plot":"Corruption.","episode_run_time":"60"},
                   "episodes":{"2":[{"id":"9002","episode_num":"1","container_extension":"mp4"}],
                               "1":[{"id":"9001","episode_num":1,"title":"S1 E1","container_extension":"mkv","season":1,
                                     "info":{"plot":"Pilot","duration_secs":3600}}]}}"""
            )
        )
        val s = client.seriesDetails(77)
        assertEquals(3600, s.info.durationSec)
        assertEquals(listOf(1, 2), s.seasons.map { it.number })
        assertEquals(listOf(9001L, 9002L), s.episodes.map { it.id })
        assertEquals("Pilot", s.episodes[0].plot)
        // No title of its own: none is made up here (the app names it when shown, in its language).
        assertEquals(listOf("S1 E1", ""), s.episodes.map { it.title })
    }

    @Test
    fun `stream urls for each kind`() {
        assertTrue(client.movieUrl(5, "mkv").endsWith("/movie/user%20one/p%40ss/5.mkv"))
        assertTrue(client.episodeUrl(6, "mp4").endsWith("/series/user%20one/p%40ss/6.mp4"))
    }

    @Test
    fun `catch-up listing decodes base64 text, mixed types and the server's clock`() = runTest {
        // 18:00 server time (UTC+1) is 17:00 UTC.
        server.enqueue(
            MockResponse().setBody(
                """{"epg_listings":[
                     {"title":"TmV3cyBhdCBTaXg=","description":"VGhlIG5ld3Mu","start":"2026-10-18 18:00:00",
                      "start_timestamp":"1792342800","stop_timestamp":1792344600,"has_archive":1},
                     {"title":"TGF0ZSBuZXdz","description":"Cw==","start":"2026-10-18 18:30:00","start_timestamp":1792344600,
                      "stop_timestamp":"1792348200","has_archive":"0"},
                     {"title":"bm8gdGltZXM="}
                   ]}"""
            )
        )
        val listing = client.catchupListing(42)
        assertTrue(server.takeRequest().path!!.contains("action=get_simple_data_table&stream_id=42"))
        // A description that isn't text when decoded stays as it came; the rest are still decoded.
        assertEquals(listOf("News at Six", "Late news"), listing.programmes.map { it.title })
        assertEquals("Cw==", listing.programmes[1].description)
        assertEquals("The news.", listing.programmes[0].description)
        assertEquals(listOf(true, false), listing.programmes.map { it.hasArchive })
        assertEquals(3_600_000L, listing.serverOffsetAt(1_792_342_800_000L))

        // The address starts at the server's own local time: 17:30 UTC is 18:30 there.
        val url = client.catchupUrl(42, 1_792_344_600_000L, listing, durationMin = 95)
        assertTrue(url, url.endsWith("/timeshift/user%20one/p%40ss/95/2026-10-18:18-30/42.ts"))
    }

    @Test
    fun `a catch-up listing in plain text stays as it is`() = runTest {
        // One-word titles such as "Kids" are also valid base64, of nonsense.
        server.enqueue(
            MockResponse().setBody(
                """{"epg_listings":[
                     {"title":"Kids","description":"Cartoons all morning.","start_timestamp":1792342800,"stop_timestamp":1792344600},
                     {"title":"Wild","description":"Animals at night.","start_timestamp":1792344600,"stop_timestamp":1792348200},
                     {"title":"News at Six","start_timestamp":1792348200,"stop_timestamp":1792350000}
                   ]}"""
            )
        )
        assertEquals(listOf("Kids", "Wild", "News at Six"), client.catchupListing(42).programmes.map { it.title })
    }

    @Test
    fun `an empty or odd catch-up listing is just empty`() = runTest {
        server.enqueue(MockResponse().setBody("""{"epg_listings":[]}"""))
        assertTrue(client.catchupListing(1).programmes.isEmpty())
        server.enqueue(MockResponse().setBody("""{}"""))
        assertTrue(client.catchupListing(1).programmes.isEmpty())
    }
}
