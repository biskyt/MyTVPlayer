package com.biskyt.mytvplayer.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.core.m3u.M3uEntry
import com.biskyt.mytvplayer.core.xtream.SeasonInfo
import com.biskyt.mytvplayer.data.db.AppDatabase
import com.biskyt.mytvplayer.data.db.CAT_LIVE
import com.biskyt.mytvplayer.data.db.CAT_MOVIE
import com.biskyt.mytvplayer.data.db.CAT_SERIES
import com.biskyt.mytvplayer.data.db.CategoryEntity
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.PlainApp
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** M3U playlists' movies and series, and how their categories are filed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = PlainApp::class)
class M3uPlaylistTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val provider = FakeProvider()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var epg: EpgRepository
    private lateinit var playlists: PlaylistRepository
    private lateinit var channels: ChannelRepository
    private lateinit var vod: VodRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val http = OkHttpClient()
        settings = SettingsRepository(context)
        epg = EpgRepository(db, http, context.cacheDir.resolve("test-epg"), scope)
        val categoryStore = CategoryStore(db) { Locale.UK }
        playlists = PlaylistRepository(db, http, settings, epg, context.cacheDir.resolve("test-playlists"), categoryStore)
        channels = ChannelRepository(db)
        vod = VodRepository(db, playlists, categoryStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        provider.close()
    }

    private val vodPlaylist = """
        #EXTM3U
        #EXTINF:-1 tvg-logo="http://img/bbb.jpg" group-title="Animation",Big Buck Bunny (2008)
        http://vod.example/movies/bbb.mp4
        #EXTINF:-1 group-title="FR | Films",Amélie (2001)
        http://vod.example/movies/amelie.mkv
        #EXTINF:-1 tvg-logo="http://img/caminandes.jpg" group-title="Animated Series",Caminandes S01E02 - Gran Dillama
        http://vod.example/series/cam2.mp4
        #EXTINF:-1 group-title="Animated Series",Caminandes S01E01 - Llama Drama
        http://vod.example/series/cam1.mp4
    """.trimIndent()

    @Test
    fun `an M3U movie playlist with episodes in it gives movies and series, in the categories wanted`() = runBlocking {
        provider.vodM3u = vodPlaylist
        val id = playlists.addM3u("Free", provider.url + "free.m3u", moviesUrl = provider.url + "vod.m3u")
        val p = playlists.get(id)!!
        assertTrue(p.hasVod)
        vod.sync(p)

        // A film named in French isn't wanted on a UK TV; untagged categories are.
        val movies = db.vod().searchMovies(id, "%", 10)
        assertEquals(listOf("Big Buck Bunny"), movies.map { it.title })
        val bunny = vod.movie(id, movies[0].id)!!
        assertEquals(2008, bunny.year)
        assertEquals("http://vod.example/movies/bbb.mp4", vod.movieUrl(p, bunny))
        assertTrue(db.categories().all(id, CAT_MOVIE).any { it.categoryId == "FR | Films" && !it.included })

        // Episodes make one series, in order, with their titles; each plays from its own address.
        val show = db.vod().searchSeries(id, "%", 10).single()
        assertEquals("Caminandes", show.title)
        val details = vod.seriesDetails(p, show.id)
        assertEquals(listOf(SeasonInfo(1, null)), details.seasons)
        assertEquals(listOf("Llama Drama", "Gran Dillama"), details.episodes.map { it.title })
        assertEquals("http://vod.example/series/cam1.mp4", vod.episodeUrl(p, details.episodes[0].id, ""))
        assertEquals(null, vod.movieDetails(p, bunny.streamId).plot)
    }

    @Test
    fun `a category ticked while the films load comes in on the next update`() = runBlocking {
        provider.vodM3u = vodPlaylist
        val id = playlists.addM3u("Free", provider.url + "free.m3u", moviesUrl = provider.url + "vod.m3u")
        vod.sync(playlists.get(id)!!)
        provider.vodM3u = vodPlaylist + "\n" // changed: loaded again
        // Ticked while that load is under way (its choices already read), which asks for another update after it.
        vod.sync(playlists.get(id)!!) { runBlocking { db.categories().setIncluded(id, CAT_MOVIE, listOf("FR | Films"), true) } }
        vod.sync(playlists.get(id)!!)
        assertEquals(setOf("Big Buck Bunny", "Amélie"), db.vod().searchMovies(id, "%", 10).map { it.title }.toSet())
    }

    @Test
    fun `an unchanged M3U movie playlist isn't loaded again, and removing it removes its titles`() = runBlocking {
        provider.vodM3u = vodPlaylist
        val id = playlists.addM3u("Free", provider.url + "free.m3u", moviesUrl = provider.url + "vod.m3u")
        vod.sync(playlists.get(id)!!)
        val bunny = db.vod().searchMovies(id, "%", 10).single()
        db.vod().deleteStaleMovies(id, Long.MAX_VALUE) // so a reload would show

        vod.sync(playlists.get(id)!!)
        assertTrue(db.vod().searchMovies(id, "%", 10).isEmpty())

        // Changed: loaded again, with the same ids, so favourites and progress still match.
        provider.vodM3u = vodPlaylist + "\n"
        vod.sync(playlists.get(id)!!)
        assertEquals(bunny.id, db.vod().searchMovies(id, "%", 10).single().id)

        playlists.update(playlists.get(id)!!.copy(moviesUrl = null))
        vod.sync(playlists.get(id)!!)
        assertEquals(0, db.vod().titleCount(id))
        assertTrue(db.categories().all(id, CAT_MOVIE).isEmpty())
        assertFalse(playlists.get(id)!!.hasVod)
    }

    @Test
    fun `everything in a series playlist is a series, even unnumbered`() = runBlocking {
        provider.seriesM3u = """
            #EXTM3U
            #EXTINF:-1 group-title="Shorts",Llama Drama
            http://vod.example/series/cam1.mp4
            #EXTINF:-1 group-title="Shorts",Gran Dillama
            http://vod.example/series/cam2.mp4
        """.trimIndent()
        val id = playlists.addM3u("Free", provider.url + "free.m3u", seriesUrl = provider.url + "series.m3u")
        val p = playlists.get(id)!!
        vod.sync(p)
        assertEquals(0, db.vod().searchMovies(id, "%", 10).size)
        assertEquals(listOf("Gran Dillama", "Llama Drama"), db.vod().searchSeries(id, "%", 10).map { it.title }.sorted())
    }

    @Test
    fun `a series with no group keeps the id it had before 1_1_0, and with it its favourites and progress`() = runBlocking {
        provider.seriesM3u = """
            #EXTM3U
            #EXTINF:-1,Llama Drama S01E01
            http://vod.example/series/cam1.mp4
        """.trimIndent()
        val id = playlists.addM3u("Free", provider.url + "free.m3u", seriesUrl = provider.url + "series.m3u")
        vod.sync(playlists.get(id)!!)
        val show = db.vod().searchSeries(id, "%", 10).single()
        assertEquals(playlists.stableId("series|Other|llama drama"), show.id)
    }

    @Test
    fun `M3U categories split by their channels' country or language, unless their name has one`() = runBlocking {
        provider.m3u = """
            #EXTM3U
            #EXTINF:-1 tvg-id="One.uk" tvg-country="GB" group-title="Live: News",UK News
            http://stream.example/uk.m3u8
            #EXTINF:-1 tvg-id="Two.us" tvg-country="US" group-title="Live: News",US News
            http://stream.example/us.m3u8
            #EXTINF:-1 tvg-id="Three.se" tvg-country="SE" tvg-language="Swedish" group-title="Live: News",SE News
            http://stream.example/se.m3u8
            #EXTINF:-1 tvg-language="English" group-title="Live: Music",Music Everywhere
            http://stream.example/music.m3u8
            #EXTINF:-1 tvg-country="US" group-title="UK | Sports",Provider Sports
            http://stream.example/sports.m3u8
            #EXTINF:-1 group-title="Live: Weather",Plain Weather
            http://stream.example/weather.m3u8
            #EXTINF:-1 tvg-country="GB" group-title="GLOBAL: Movies",Global Movies
            http://stream.example/global.m3u8
        """.trimIndent()
        provider.vodM3u = """
            #EXTM3U
            #EXTINF:6000 tvg-type="movie" tvg-language="French" group-title="Movies: Drama",Les Misérables (1934)
            https://archive.org/download/lesmis.mp4
            #EXTINF:6000 tvg-type="movie" tvg-language="English" group-title="Movies: Drama",Detour (1945)
            https://archive.org/download/detour.mp4
        """.trimIndent()
        val id = playlists.addM3u("Free", provider.url + "free.m3u", moviesUrl = provider.url + "vod.m3u")
        playlists.syncChannels(id)
        vod.sync(playlists.get(id)!!)

        val live = db.categories().all(id, com.biskyt.mytvplayer.data.db.CAT_LIVE)
        // One "Live: News" per country, named as the playlist names it; the TV's own country (a UK TV here) is chosen.
        assertEquals(listOf("SE", "UK", "US"), live.filter { it.name == "Live: News" }.mapNotNull { it.lang }.sorted())
        val loaded = db.channels().idsAll(id).mapNotNull { db.channels().get(id, it)?.name }
        assertEquals(listOf("UK News"), loaded.filter { it.endsWith("News") })
        // By language when there's no country; a name that says its own group, or nothing to go by, stays as it is.
        assertEquals("UK", live.single { it.name == "Live: Music" }.lang)
        assertEquals("UK | Sports", live.single { it.name == "UK | Sports" }.categoryId)
        assertEquals("Live: Weather", live.single { it.name == "Live: Weather" }.categoryId)
        // A name only known since 1.8.15 keeps the id it had (with its channels' country), but files as its name says.
        val global = live.single { it.name == "GLOBAL: Movies" }
        assertEquals("GLOBAL" to true, global.lang to (global.categoryId != "GLOBAL: Movies"))
        // Films too: the French one isn't chosen on a UK TV.
        val films = db.categories().all(id, CAT_MOVIE)
        assertEquals(listOf("FR" to false, "UK" to true), films.map { it.lang to it.included }.sortedBy { it.first })
        assertEquals(listOf("Detour"), db.vod().searchMovies(id, "%", 10).map { it.title })
    }

    @Test
    fun `a playlist whose categories name no country or language has them all chosen to start with`() = runBlocking {
        // As the free TV playlist is now: genres only, nothing to choose by country.
        provider.m3u = """
            #EXTM3U
            #EXTINF:-1 group-title="Live: News",World News
            http://stream.example/news.m3u8
            #EXTINF:-1 group-title="FAST: Plex",Plex Movies
            http://stream.example/plex.m3u8
        """.trimIndent()
        provider.vodM3u = """
            #EXTM3U
            #EXTINF:6000 tvg-type="movie" group-title="Movies: Drama",Detour (1945)
            https://archive.org/download/detour.mp4
        """.trimIndent()
        val id = playlists.addM3u("Free", provider.url + "free.m3u", moviesUrl = provider.url + "vod.m3u")
        playlists.syncChannels(id)
        vod.sync(playlists.get(id)!!)

        assertTrue(db.categories().all(id, com.biskyt.mytvplayer.data.db.CAT_LIVE).all { it.included })
        assertEquals(2, db.channels().idsAll(id).size)
        assertEquals(listOf("Detour"), db.vod().searchMovies(id, "%", 10).map { it.title })
    }

    @Test
    fun `a playlist that says what each title is (tvg-type) is taken at its word`() = runBlocking {
        provider.vodM3u = """
            #EXTM3U
            #EXTINF:4020 tvg-type="movie" year="1945" group-title="Movies: Noir",Detour
            https://archive.org/download/Detour/Detour.mp4
            #EXTINF:600 tvg-type="movie" group-title="Movies: Serials",Flash Gordon S01E02
            https://archive.org/download/flash2.mp4
            #EXTINF:1530 tvg-type="series" series-name="The Beverly Hillbillies" season="1" episode="2" group-title="Series: The Beverly Hillbillies",Getting Settled
            https://archive.org/download/bh2.mp4
            #EXTINF:1530 tvg-type="series" series-name="The Beverly Hillbillies" season="1" episode="1" group-title="Series: The Beverly Hillbillies",The Beverly Hillbillies S01E01 - The Clampetts Strike Oil
            https://archive.org/download/bh1.mp4
        """.trimIndent()
        val id = playlists.addM3u("Free", provider.url + "free.m3u", moviesUrl = provider.url + "vod.m3u")
        db.categories().setIncluded(id, CAT_MOVIE, listOf("Movies: Noir", "Movies: Serials"), true)
        db.categories().setIncluded(id, CAT_SERIES, listOf("Series: The Beverly Hillbillies"), true)
        val p = playlists.get(id)!!
        vod.sync(p)

        // A film named like an episode stays a film; the year comes from the playlist.
        val movies = db.vod().searchMovies(id, "%", 10).sortedBy { it.title }
        assertEquals(listOf("Detour", "Flash Gordon S01E02"), movies.map { it.title })
        assertEquals(1945, vod.movie(id, movies[0].id)!!.year)

        // Episodes by series-name, season and episode, whatever their names say.
        val show = db.vod().searchSeries(id, "%", 10).single()
        assertEquals("The Beverly Hillbillies", show.title)
        val details = vod.seriesDetails(p, show.id)
        // An episode with no title of its own saves none; the app names it when shown, in its language.
        assertEquals(listOf("The Clampetts Strike Oil", ""), details.episodes.map { it.title })
    }

    @Test
    fun `M3U titles keep their ids when categories change, and moving a playlist to Series reloads it`() = runBlocking {
        provider.vodM3u = """
            #EXTM3U
            #EXTINF:-1 group-title="FR | Films",Big Buck Bunny (2008)
            http://vod.example/fr/bbb.mp4
            #EXTINF:-1 group-title="Animation",Big Buck Bunny (2008)
            http://vod.example/bbb.mp4
        """.trimIndent()
        val id = playlists.addM3u("Free", provider.url + "free.m3u", moviesUrl = provider.url + "vod.m3u")
        vod.sync(playlists.get(id)!!)
        val before = db.vod().searchMovies(id, "%", 10).single().id
        db.categories().setIncluded(id, CAT_MOVIE, listOf("FR | Films"), true)
        vod.sync(playlists.get(id)!!)
        assertTrue(db.vod().searchMovies(id, "%", 10).map { it.id }.contains(before))
        assertEquals("http://vod.example/bbb.mp4", vod.movieUrl(playlists.get(id)!!, vod.movie(id, before)!!))

        // The same address moved from Movies to Series: everything in it becomes a series.
        playlists.update(playlists.get(id)!!.copy(moviesUrl = null, seriesUrl = provider.url + "vod.m3u"))
        vod.sync(playlists.get(id)!!)
        db.categories().setIncluded(id, CAT_SERIES, listOf("FR | Films"), true)
        vod.sync(playlists.get(id)!!)
        assertEquals(0, db.vod().searchMovies(id, "%", 10).size)
        assertEquals(2, db.vod().searchSeries(id, "%", 10).size)
    }

    private fun entry(group: String?, country: String? = null) =
        M3uEntry("x", "http://x", null, null, group, null, null, null, country = country)

    @Test
    fun `category ids carry their group, names stay the playlist's own`() {
        val news = M3uCategories.idOf(entry("Live: News", country = "SE"))
        assertEquals("SE", M3uCategories.groupOf(news))
        assertEquals("Live: News", M3uCategories.category(news).name)
        assertEquals("UK | News", M3uCategories.idOf(entry("UK | News", country = "SE"))) // the name says already
        assertEquals(null, M3uCategories.groupOf("UK | News"))
    }

    @Test
    fun `entries with no group go in a category of their own with no name`() {
        val none = M3uCategories.idOf(entry(null))
        val noneSe = M3uCategories.idOf(entry(null, country = "SE"))
        // No name saved: the app names it ("No category", in its language) when shown. A playlist's own "Other" stays
        // its own.
        assertEquals("", M3uCategories.category(none).name)
        assertEquals("No category", categoryLabel(M3uCategories.category(none).name))
        assertEquals("SE", M3uCategories.groupOf(noneSe))
        assertEquals("Other", M3uCategories.idOf(entry("Other")))
        assertEquals(null, M3uCategories.oldId("Other"))
    }

    @Test
    fun `entries with no group, and a group called Other, keep what was chosen for Other before 1_1_0`() = runBlocking {
        provider.m3u = """
            #EXTM3U
            #EXTINF:-1 group-title="United Kingdom",Channel One
            http://stream.example/one.m3u8
            #EXTINF:-1,Lone Channel
            http://stream.example/lone.m3u8
            #EXTINF:-1 group-title="Other",Odd Channel
            http://stream.example/odd.m3u8
            #EXTINF:-1 group-title="News",News Channel
            http://stream.example/news.m3u8
        """.trimIndent()
        val ungrouped = M3uCategories.idOf(entry(null))
        // Either choice carries over, whichever a new category would get.
        for (chosen in listOf(true, false)) {
            val id = playlists.addM3u("Free $chosen", provider.url + "free.m3u")
            // As saved before 1.1.0: one "Other" for both, at the end; "News" hidden, so a new category wouldn't be shown.
            db.categories().delete(id, CAT_LIVE, db.categories().all(id, CAT_LIVE).map { it.categoryId } - "United Kingdom")
            db.categories().upsert(
                listOf(CategoryEntity(id, "News", "News", "News", false, 4), CategoryEntity(id, "Other", "Other", "Other", chosen, 5))
            )
            playlists.syncChannels(id)
            val names = channels.ids(id, ChannelFilter.All).map { channels.get(id, it)!!.name }
            assertEquals(chosen, "Lone Channel" in names)
            assertEquals(chosen, "Odd Channel" in names)
            val cats = db.categories().all(id, CAT_LIVE).associateBy { it.categoryId }
            assertEquals(setOf("United Kingdom", "News", "Other", ungrouped), cats.keys)
            assertEquals(chosen, cats.getValue(ungrouped).included)
            // Old "Other" keeps its place; the new category doesn't share it.
            assertEquals(5, cats.getValue("Other").sortOrder)
            assertTrue(cats.getValue(ungrouped).sortOrder != 5)
        }
    }

    @Test
    fun `with no group called Other any more, entries with no group take its place`() = runBlocking {
        provider.m3u = """
            #EXTM3U
            #EXTINF:-1 group-title="United Kingdom",Channel One
            http://stream.example/one.m3u8
            #EXTINF:-1,Lone Channel
            http://stream.example/lone.m3u8
        """.trimIndent()
        val id = playlists.addM3u("Free", provider.url + "free.m3u")
        // As saved before 1.1.0: "Other" first, hidden.
        val uk = db.categories().all(id, CAT_LIVE).single { it.categoryId == "United Kingdom" }
        db.categories().delete(id, CAT_LIVE, db.categories().all(id, CAT_LIVE).map { it.categoryId } - "United Kingdom")
        db.categories().upsert(listOf(CategoryEntity(id, "Other", "Other", "Other", false, 0), uk.copy(sortOrder = 1)))
        playlists.syncChannels(id)
        val cats = db.categories().all(id, CAT_LIVE)
        // First and hidden, as "Other" was; the old category is gone.
        assertEquals(listOf(M3uCategories.idOf(entry(null)), "United Kingdom"), cats.map { it.categoryId })
        assertFalse(cats.first().included)
    }
}
