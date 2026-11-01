package com.biskyt.mytvplayer.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.core.epg.ProgrammeKind
import com.biskyt.mytvplayer.core.xtream.XtreamException
import com.biskyt.mytvplayer.data.db.AppDatabase
import com.biskyt.mytvplayer.data.db.CAT_LIVE
import com.biskyt.mytvplayer.data.db.CAT_MOVIE
import com.biskyt.mytvplayer.data.db.CAT_SERIES
import com.biskyt.mytvplayer.data.db.VOD_EPISODE
import com.biskyt.mytvplayer.data.db.VOD_MOVIE
import com.biskyt.mytvplayer.data.db.WatchProgressEntity
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.PlainApp
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = PlainApp::class)
class RepositoriesTest {
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

    private suspend fun addPlaylist(): Long = playlists.add("Home", provider.url, "me", "secret")

    @Test
    fun `wrong password is rejected and nothing is saved`() = runBlocking {
        try {
            playlists.add("Home", provider.url, "me", "wrong")
            fail("expected login failure")
        } catch (_: XtreamException) {
        }
        assertTrue(playlists.allIds().isEmpty())
    }

    @Test
    fun `new playlist includes the TV's own language groups and becomes active`() = runBlocking {
        val id = addPlaylist()
        val cats = db.categories().all(id, CAT_LIVE).associateBy { it.categoryId }
        assertTrue(cats.getValue("1").included)
        assertTrue(cats.getValue("2").included)
        assertFalse("French category should be excluded for a UK TV", cats.getValue("3").included)
        assertFalse(cats.getValue("4").included)
        assertEquals("FR", cats.getValue("3").lang)
        assertEquals(id, settings.current().activePlaylistId)
    }

    @Test
    fun `adult sections are an Adult group, never chosen to start with, in live TV, films and series`() = runBlocking {
        provider.categories += listOf("5" to "18: FOR ADULTS [1080p]", "6" to "UK | XXX")
        provider.movieCategories += listOf("13" to "GLOBAL: FOR ADULTS [1080p]", "14" to "ADULT MOVIES")
        provider.seriesCategories += listOf("22" to "Adults 18+")
        val id = addPlaylist()
        vod.sync(playlists.get(id)!!)
        val adult = listOf(CAT_LIVE to "5", CAT_LIVE to "6", CAT_MOVIE to "13", CAT_MOVIE to "14", CAT_SERIES to "22").map { (type, cat) ->
            db.categories().all(id, type).single { it.categoryId == cat }
        }
        assertEquals(List(5) { "ADULT" to false }, adult.map { it.lang to it.included })
    }

    @Test
    fun `an adult section included by default before 1_8_18 is left out once, then as the user chooses`() = runBlocking {
        provider.movieCategories += listOf("13" to "GLOBAL: FOR ADULTS [1080p]")
        val id = addPlaylist()
        // As 1.8.17 stored it: a Global film section, included by default.
        db.openHelper.writableDatabase.execSQL("UPDATE category SET lang = 'GLOBAL' WHERE playlistId = $id AND categoryId = '13'")
        db.categories().setIncluded(id, CAT_MOVIE, listOf("13"), true)
        vod.sync(playlists.get(id)!!)
        suspend fun adult() = db.categories().all(id, CAT_MOVIE).single { it.categoryId == "13" }
        assertEquals("ADULT" to false, adult().lang to adult().included)
        // Chosen again, it stays chosen.
        db.categories().setIncluded(id, CAT_MOVIE, listOf("13"), true)
        vod.sync(playlists.get(id)!!)
        assertTrue(adult().included)
    }

    @Test
    fun `a new playlist's Global sections are chosen too, and Canada's and Nordic ones are named`() = runBlocking {
        provider.categories +=
            listOf("5" to "GLOBAL: [24/7] MOVIES", "6" to "CAN: LOCALS", "7" to "SCAN: NORDIC KIDS", "8" to "SPT: BOXING")
        val id = addPlaylist()
        val cats = db.categories().all(id, CAT_LIVE).associateBy { it.categoryId }
        assertEquals(listOf("GLOBAL", "CA", "NORDIC", null), listOf("5", "6", "7", "8").map { cats.getValue(it).lang })
        assertTrue("Global, with the TV's own UK", cats.getValue("5").included)
        assertFalse(cats.getValue("6").included)
        assertFalse(cats.getValue("8").included)
    }

    @Test
    fun `a TV whose country the provider lacks falls back to its language`() = runBlocking {
        val us =
            PlaylistRepository(
                db,
                OkHttpClient(),
                settings,
                epg,
                context.cacheDir.resolve("test-playlists"),
                CategoryStore(db) {
                    Locale.US
                }
            )
        val id = us.add("Home", provider.url, "me", "secret")
        val included = db.categories().all(id, CAT_LIVE).filter { it.included }.map { it.categoryId }
        assertEquals(listOf("1", "2"), included)
    }

    @Test
    fun `m3u playlist loads the TV's country and keeps ids stable across refreshes`() = runBlocking {
        val id = playlists.addM3u("Free", provider.url + "free.m3u")
        val cats = db.categories().all(id, CAT_LIVE).associateBy { it.categoryId }
        assertTrue(cats.getValue("United Kingdom").included)
        assertFalse(cats.getValue("France").included)
        assertEquals("UK", cats.getValue("United Kingdom").lang)

        playlists.syncChannels(id)
        val ids = channels.ids(id, ChannelFilter.All)
        assertEquals(2, ids.size)
        val kite = channels.get(id, ids[1])!!
        assertEquals("Kite Kids (720p)", kite.name)
        assertEquals("http://stream.example/kite.m3u8", kite.streamUrl)
        assertEquals("Agent/2", kite.userAgent)
        assertEquals("KiteKids.uk", kite.providerEpgId)

        // The provider changes a stream URL; the channel keeps its id (and so its favourite).
        channels.setFavourite(id, kite.streamId, true)
        provider.m3u = provider.m3u.replace("kite.m3u8", "kite-new.m3u8")
        playlists.syncChannels(id)
        assertEquals("http://stream.example/kite-new.m3u8", channels.get(id, kite.streamId)!!.streamUrl)
        assertEquals(listOf(kite.streamId), channels.ids(id, ChannelFilter.Favourites))
    }

    @Test
    fun `an address that isn't a playlist is rejected`() = runBlocking {
        try {
            playlists.addM3u("Bad", provider.url + "not-a-playlist")
            fail("expected rejection")
        } catch (_: XtreamException) {
        }
        assertTrue(playlists.allIds().isEmpty())
    }

    @Test
    fun `sync only downloads included categories and keeps user choices`() = runBlocking {
        val id = addPlaylist()
        playlists.syncChannels(id)
        assertEquals(listOf(101L, 102L, 103L), channels.ids(id, ChannelFilter.All))
        // Few categories included out of many → fetched one category at a time.
        assertTrue(provider.requests.any { it.endsWith("get_live_streams") })

        channels.setHidden(id, 103, true)
        channels.setFavourite(id, 101, true)
        db.channels().setSortOrder(id, 102, 0) // an order set with Move up, before 1.2.1
        assertEquals(listOf(102L, 101L), channels.ids(id, ChannelFilter.All))

        // Provider renames a channel and drops another; user choices survive.
        provider.streams = provider.streams.filter { it.id != 103L }.map { if (it.id == 101L) it.copy(name = "UK: CHANNEL 1") else it }
        playlists.syncChannels(id)
        assertEquals(listOf(102L, 101L), channels.ids(id, ChannelFilter.All))
        assertEquals("UK: CHANNEL 1", channels.get(id, 101)!!.name)
        assertNull(channels.get(id, 103))
        assertEquals(listOf(101L), channels.ids(id, ChannelFilter.Favourites))
    }

    @Test
    fun `excluding a category removes its channels on the next sync`() = runBlocking {
        val id = addPlaylist()
        playlists.syncChannels(id)
        db.categories().setIncluded(id, CAT_LIVE, listOf("2"), false)
        db.categories().setIncluded(id, CAT_LIVE, listOf("3"), true)
        playlists.syncChannels(id)
        assertEquals(setOf(101L, 103L, 201L), channels.ids(id, ChannelFilter.All).toSet())
        assertEquals(listOf(201L), channels.ids(id, ChannelFilter.Category("3")))
    }

    @Test
    fun `guide import matches channels and stores programmes only for used guide channels`() = runBlocking {
        val id = addPlaylist()
        playlists.syncChannels(id)
        epg.ensureProviderSource(playlists.get(id)!!, playlists.client(playlists.get(id)!!).xmltvUrl())
        epg.importAll(listOf(id))

        val now = System.currentTimeMillis()
        val programmes = epg.programmes(id, listOf(101, 102, 103), now, now + 3 * 3_600_000)
        // 101 matches automatically via its provider epg id.
        assertEquals("Now on one.uk", programmes.getValue(101).first().title)
        assertEquals("Next on one.uk", programmes.getValue(101)[1].title)
        // Misspelt names aren't matched automatically (too risky) but are the top suggestion for the user to confirm.
        assertNull(programmes[102])
        assertEquals("arena1.uk", epg.suggestions(id, "UK: ARENA SPRTS 1 FHD", null).first().candidate.xmlId)
        assertEquals("kite.uk", epg.suggestions(id, "UK: KITE KIDZ", null).first().candidate.xmlId)

        // cine.fr is in the guide but no included channel uses it, so its programmes aren't stored.
        val stored = db.query("SELECT COUNT(*) FROM programme WHERE xmlId = 'cine.fr'", null).use {
            it.moveToFirst()
            it.getInt(0)
        }
        assertEquals(0, stored)
    }

    @Test
    fun `a provider's M3U link is added as its login, for movies, series and catch-up`() = runBlocking {
        val panel = playlists.addM3u("Panel", provider.url + "get.php?username=me&password=secret&type=m3u_plus&output=ts")
        val p = playlists.get(panel)!!
        assertFalse(p.isM3u)
        assertEquals(Triple(provider.url.trimEnd('/'), "me", "secret"), Triple(p.server, p.username, p.password))
        vod.sync(p)
        assertTrue(vod.movie(panel, 501) != null)
    }

    @Test
    fun `a provider's M3U link stays a playlist when the panel refuses logins, and shows the account's expiry`() = runBlocking {
        provider.loginsRefused = true
        val panel = playlists.addM3u("Panel", provider.url + "get.php?username=me&password=secret&type=m3u_plus&output=ts")
        assertTrue(playlists.get(panel)!!.isM3u)
        provider.loginsRefused = false
        playlists.syncChannels(panel)
        assertEquals(1_900_000_000L, playlists.get(panel)!!.expiresAt)
        val free = playlists.addM3u("Free", provider.url + "free.m3u")
        playlists.syncChannels(free)
        assertNull(playlists.get(free)!!.expiresAt)
    }

    @Test
    fun `an Xtream panel's M3U link gives its server and login`() {
        assertEquals(
            Triple("http://tv.example:8080", "me", "p@ss"),
            PlaylistRepository.xtreamLoginIn("http://tv.example:8080/get.php?username=me&password=p%40ss&type=m3u_plus&output=ts")
        )
        assertNull(PlaylistRepository.xtreamLoginIn(PlaylistRepository.FREE_PLAYLIST_URL))
        assertNull(PlaylistRepository.xtreamLoginIn("http://tv.example/get.php?type=m3u"))
        // Only plain links become logins: another stream format or a choice of channels stays a playlist.
        assertTrue(PlaylistRepository.isPlainPanelLink("http://tv.example/get.php?username=me&password=p&type=m3u_plus&output=ts"))
        assertTrue(PlaylistRepository.isPlainPanelLink("http://tv.example/get.php?username=me&password=p"))
        assertFalse(PlaylistRepository.isPlainPanelLink("http://tv.example/get.php?username=me&password=p&type=m3u_plus&output=m3u8"))
        assertFalse(PlaylistRepository.isPlainPanelLink("http://tv.example/get.php?username=me&password=p&bouquet=3"))
    }

    @Test
    fun `an unchanged guide isn't downloaded or read again, and adding a guide leaves the others alone`() = runBlocking {
        val id = addPlaylist()
        playlists.syncChannels(id)
        epg.ensureProviderSource(playlists.get(id)!!, playlists.client(playlists.get(id)!!).xmltvUrl())
        epg.importAll(listOf(id))
        val provider101 = db.epg().mapping(id, 101)!!.xmlId

        // The same guide again: the server says it hasn't changed, so its channels and programmes aren't read again.
        val again = mutableListOf<GuideStep>()
        epg.importAll(listOf(id)) { again += it }
        assertTrue(again.none { it is GuideStep.Reading || it is GuideStep.Loading })
        assertEquals("Now on $provider101", epg.programmes(id, listOf(101), now(), now() + 60_000).getValue(101).first().title)

        // Adding a guide reads its own programmes, with progress; the provider's guide isn't read again.
        val added = mutableListOf<GuideStep>()
        val extra = epg.addSource("Extra", provider.url + "xmltv.php?username=me&password=secret&extra=1", id)
        epg.importAll(listOf(id), only = extra) { added += it }
        assertTrue(added.any { it is GuideStep.Loading && it.guide == "Extra" && it.fraction != null })
        assertTrue(added.none { it is GuideStep.Loading && it.guide.orEmpty().startsWith("Home") })
    }

    private fun now() = System.currentTimeMillis()

    @Test
    fun `a guide listing channels between programmes still has all its channels`() = runBlocking {
        provider.interleavedGuide = true
        val id = addPlaylist()
        playlists.syncChannels(id)
        epg.ensureProviderSource(playlists.get(id)!!, playlists.client(playlists.get(id)!!).xmltvUrl())
        fun guideChannelCount() = db.query("SELECT COUNT(*) FROM epg_channel", null).use {
            it.moveToFirst()
            it.getInt(0)
        }
        epg.importAll(listOf(id))
        assertEquals(4, guideChannelCount())
        assertEquals("kite.uk", epg.suggestions(id, "UK: KITE KIDZ", null).first().candidate.xmlId)
        // Later updates read such a guide in full straight away.
        epg.importAll(listOf(id))
        assertEquals(4, guideChannelCount())
    }

    @Test
    fun `manual guide choice wins over automatic matching and survives re-import`() = runBlocking {
        val id = addPlaylist()
        playlists.syncChannels(id)
        val p = playlists.get(id)!!
        epg.ensureProviderSource(p, playlists.client(p).xmltvUrl())
        epg.importAll(listOf(id))
        val source = db.epg().sourcesFor(listOf(id)).first().id

        epg.setManual(id, 103, source, "cine.fr", offsetMinutes = 60)
        delay(2_500) // debounced programme import
        val now = System.currentTimeMillis()
        val list = epg.programmes(id, listOf(103), now - 4 * 3_600_000, now + 4 * 3_600_000).getValue(103)
        assertEquals("Now on cine.fr", list.first().title)
        // The guide has it starting 30 min ago; the +1 h time shift moves it to 30 min from now.
        assertTrue(kotlin.math.abs(list.first().startMs - (now + 30 * 60_000)) < 120_000)

        epg.importAll(listOf(id))
        assertEquals("cine.fr", db.epg().mapping(id, 103)!!.xmlId)

        epg.setNoGuide(id, 102)
        epg.importAll(listOf(id))
        assertNull(epg.programmes(id, listOf(102), now, now + 3_600_000)[102])
    }

    @Test
    fun `deleting a playlist removes its data and guide`() = runBlocking {
        val id = addPlaylist()
        playlists.syncChannels(id)
        val p = playlists.get(id)!!
        epg.ensureProviderSource(p, playlists.client(p).xmltvUrl())
        playlists.delete(id)
        assertTrue(channels.ids(id, ChannelFilter.All).isEmpty())
        assertTrue(db.epg().allSourceIds().isEmpty())
        assertEquals(0L, settings.current().activePlaylistId)
    }

    @Test
    fun `movies and series sync for the TV's language, with clean titles`() = runBlocking {
        val id = addPlaylist()
        val p = playlists.get(id)!!
        vod.sync(p)
        val movies = db.vod().moviesSnapshot(id)
        // Untagged categories ("ACTION") count as the provider's own language, so they're included too.
        assertEquals(listOf("Dune" to 2021, "Paddington 2" to 2017, "Heat" to 1995), movies.map { it.title to it.year })
        assertEquals("10", movies.first().categoryId)
        assertEquals(listOf("Line of Duty"), db.vod().seriesSnapshot(id).map { it.title })
        // Categories of each kind are kept apart, and live categories are untouched.
        assertEquals(listOf("10", "11", "12"), db.categories().all(id, CAT_MOVIE).map { it.categoryId })
        assertEquals(2, db.categories().all(id, CAT_MOVIE).first().channelCount)
        assertEquals(4, db.categories().all(id, CAT_LIVE).size)

        // Including French movies brings Amélie in on the next sync; removing a movie at the provider drops it.
        db.categories().setIncluded(id, CAT_MOVIE, listOf("11"), true)
        provider.movies = provider.movies.filter { it.first != 502L }
        vod.sync(p)
        assertEquals(setOf("Dune", "Amélie", "Heat"), db.vod().moviesSnapshot(id).map { it.title }.toSet())

        // Unticking every movie category turns movies off: the next sync doesn't tick the defaults again.
        db.categories().setIncluded(id, CAT_MOVIE, listOf("10", "11", "12"), false)
        vod.sync(p)
        assertEquals(listOf<String>(), db.categories().all(id, CAT_MOVIE).filter { it.included }.map { it.categoryId })
        assertEquals(listOf<String>(), db.vod().moviesSnapshot(id).map { it.title })
        // Nor does a new category the provider adds, though categories named without a language are usually ticked.
        provider.movieCategories = provider.movieCategories + ("13" to "DOCUMENTARIES")
        provider.movies = provider.movies + Triple(801L, "Planet (2020)", "13")
        vod.sync(p)
        assertEquals(listOf<String>(), db.categories().all(id, CAT_MOVIE).filter { it.included }.map { it.categoryId })
    }

    @Test
    fun `details, favourites and carry on watching`() = runBlocking {
        val id = addPlaylist()
        val p = playlists.get(id)!!
        vod.sync(p)
        assertEquals(6000, vod.movieDetails(p, 501).durationSec)
        val show = vod.seriesDetails(p, 77)
        assertEquals(listOf(1, 2), show.seasons.map { it.number })
        assertEquals(3, show.episodes.size)

        vod.setFavourite(id, CAT_SERIES, 77, true)
        assertTrue(vod.observeFavourite(id, CAT_SERIES, 77).first())

        fun progress(kind: String, item: Long, pos: Long, at: Long, seriesId: Long? = null, hasNext: Boolean = false) =
            WatchProgressEntity(id, kind, item, pos, 3_600_000, at, "t", null, seriesId = seriesId, hasNext = hasNext)
        vod.saveProgress(progress(VOD_MOVIE, 501, 600_000, 1))
        vod.saveProgress(progress(VOD_EPISODE, 9001, 3_500_000, 2, seriesId = 77)) // finished
        vod.saveProgress(progress(VOD_EPISODE, 9002, 60_000, 3, seriesId = 77))
        vod.saveProgress(progress(VOD_MOVIE, 502, 3_400_000, 4)) // finished
        // Newest first; one row per series (its latest episode); finished things drop off.
        assertEquals(listOf(9002L, 501L), vod.observeCarryOn(id).first().map { it.itemId })
        // A finished episode that has a next one stays, so the series carries on.
        vod.saveProgress(progress(VOD_EPISODE, 9002, 3_500_000, 5, seriesId = 77, hasNext = true))
        assertEquals(listOf(9002L, 501L), vod.observeCarryOn(id).first().map { it.itemId })
        vod.saveProgress(progress(VOD_EPISODE, 9003, 3_500_000, 6, seriesId = 77), trim = true) // the last episode
        assertEquals(listOf(501L), vod.observeCarryOn(id).first().map { it.itemId })
    }

    @Test
    fun `a watched episode's card becomes the next episode's`() = runBlocking {
        val id = addPlaylist()
        val p = playlists.get(id)!!
        vod.sync(p)
        val episodes = vod.seriesDetails(p, 77).episodes
        fun watched(item: Long, at: Long, hasNext: Boolean) =
            WatchProgressEntity(id, VOD_EPISODE, item, 3_500_000, 3_600_000, at, "Show", "poster", seriesId = 77, hasNext = hasNext)
        suspend fun card() = vod.observeCarryOn(id).first().single()

        // Episode 1 watched: its card is episode 2's, not started, with its running time.
        vod.saveProgress(watched(9001, 10, hasNext = true), next = episodes[1])
        val up = card()
        assertEquals(9002L, up.itemId)
        assertEquals(0L, up.positionMs)
        assertEquals(3_500_000L, up.durationMs)
        assertEquals(listOf("Show", "poster", 1, 2), listOf(up.title, up.imageUrl, up.season, up.episode))
        // The credits play on, saved again: episode 2 stays the card.
        vod.saveProgress(watched(9001, 20, hasNext = true), next = episodes[1])
        assertEquals(9002L, card().itemId)

        // Episode 1 counted as unwatched after all: episode 2, never started, goes with it.
        vod.forgetEpisode(id, 9001, next = episodes[1])
        assertTrue(vod.observeCarryOn(id).first().isEmpty())
        vod.saveProgress(watched(9001, 25, hasNext = true), next = episodes[1])

        // Started already: it keeps where it got.
        vod.saveProgress(WatchProgressEntity(id, VOD_EPISODE, 9002, 60_000, 3_500_000, 30, "Show", null, seriesId = 77))
        vod.saveProgress(watched(9001, 40, hasNext = true), next = episodes[1])
        assertEquals(9002L to 60_000L, card().let { it.itemId to it.positionMs })

        // The last episode watched: no card.
        vod.saveProgress(watched(9003, 50, hasNext = false), next = null)
        assertTrue(vod.observeCarryOn(id).first().isEmpty())
    }

    @Test
    fun `search finds channels, programmes, movies and series`() = runBlocking {
        val id = addPlaylist()
        playlists.syncChannels(id)
        playlists.get(id)?.let { epg.ensureProviderSource(it, playlists.client(it).xmltvUrl()) }
        epg.importAll(listOf(id))
        vod.sync(playlists.get(id)!!)
        val search = SearchRepository(db)
        suspend fun find(q: String) = search.search(id, q)!!

        assertNull(search.search(id, "a")) // one letter: too broad to search
        assertEquals(listOf(102L), find("2").channels.map { it.streamId }) // one digit: channel number only
        assertEquals(listOf("UK: KITE KIDZ"), find("kite").channels.map { it.name })
        val news = find("on one.uk")
        assertEquals(listOf("Now on one.uk", "Next on one.uk"), news.programmes.map { it.title })
        assertEquals("UK: CHANNEL ONE HD", news.programmes.first().channelName)
        assertTrue(find("on").programmes.isEmpty()) // the guide needs 3+ letters
        assertEquals(listOf("Paddington 2"), find("padd").movies.map { it.title })
        assertEquals(listOf("Line of Duty"), find("duty").series.map { it.title })
        assertTrue(find("zzz").isEmpty)
        assertTrue(find("%%").isEmpty) // typed literally, not as wildcards
        assertTrue(find("__").isEmpty)
    }

    @Test
    fun `a backup restores playlists, choices, favourites, guide choices and settings`() = runBlocking {
        val backup = BackupRepository(db, settings, epg, SyncManager(scope, playlists, vod, epg, settings))
        fun import(text: String) = runBlocking { backup.apply(backup.read(text.byteInputStream())) }
        val id = addPlaylist()
        playlists.syncChannels(id)
        vod.sync(playlists.get(id)!!)
        val p = playlists.get(id)!!
        epg.ensureProviderSource(p, playlists.client(p).xmltvUrl())
        val source = db.epg().sourcesFor(listOf(id)).first().id
        channels.setHidden(id, 103, true)
        channels.setFavourite(id, 101, true)
        db.channels().setSortOrder(id, 102, 0) // an order set with Move up, before 1.2.1
        epg.setManual(id, 101, source, "one.uk", offsetMinutes = 60)
        db.categories().setIncluded(id, CAT_MOVIE, listOf("11"), true)
        vod.setFavourite(id, CAT_MOVIE, 501, true)
        vod.saveProgress(WatchProgressEntity(id, VOD_MOVIE, 501, 600_000, 3_600_000, 1, "Dune", null))
        settings.update { it.copy(skipBackSec = 20, largeText = true) }

        val file = backup.export().decodeToString()
        // A bad file is turned down and changes nothing.
        for (bad in listOf("not json", "{}", file.replace("\"MyTVPlayer\"", "\"Other\""))) {
            try {
                import(bad)
                fail("accepted $bad")
            } catch (_: BackupException) {
            }
        }
        // A newer app's file says so, even when its contents have changed shape.
        try {
            import("""{"app":"MyTVPlayer","version":99,"playlists":"changed"}""")
            fail("accepted a newer file")
        } catch (e: BackupException) {
            assertTrue(e.message!!.contains("newer version"))
        }
        assertEquals(listOf(id), playlists.allIds())

        playlists.delete(id)
        settings.update { Settings() }
        assertTrue(import(file))

        assertEquals(id, settings.current().activePlaylistId)
        assertEquals(20, settings.current().skipBackSec)
        assertTrue(settings.current().largeText)
        assertEquals("secret", playlists.get(id)!!.password)
        assertTrue(playlists.get(id)!!.isStale())
        assertTrue(db.categories().all(id, CAT_MOVIE).single { it.categoryId == "11" }.included)
        assertTrue(vod.observeFavourite(id, CAT_MOVIE, 501).first())
        assertEquals(600_000L, vod.progress(id, VOD_MOVIE, 501)!!.positionMs)
        assertEquals(source, db.epg().sourcesFor(listOf(id)).single().id)
        assertEquals(60, db.epg().mapping(id, 101)!!.offsetMinutes)

        // The next download fills the channel list back in, keeping the hidden, moved and favourite channels.
        playlists.syncChannels(id)
        assertEquals(listOf(102L, 101L), channels.ids(id, ChannelFilter.All))
        assertTrue(channels.get(id, 103)!!.hidden)
        assertEquals(listOf(101L), channels.ids(id, ChannelFilter.Favourites))
    }

    @Test
    fun `guide updates keep matches, retry when the guide changes, and match on request`() = runBlocking {
        provider.streams = provider.streams + FakeProvider.Stream(104, 4, "US: NBC 4 (WNBC) NEW YORK (A)", "1", null)
        val id = addPlaylist()
        playlists.syncChannels(id)
        val p = playlists.get(id)!!
        epg.ensureProviderSource(p, playlists.client(p).xmltvUrl())
        epg.importAll(listOf(id)) // the guide's first load matches everything
        assertEquals("one.uk", db.epg().mapping(id, 101)!!.xmlId)
        assertNull(db.epg().mapping(id, 104)!!.sourceId) // tried: nothing found

        // An unchanged guide keeps automatic matches as they are and doesn't retry channels already tried.
        db.epg().upsertMappings(listOf(db.epg().mapping(id, 101)!!.copy(score = 0.5)))
        epg.importAll(listOf(id))
        assertEquals(0.5, db.epg().mapping(id, 101)!!.score, 0.0)
        assertNull(db.epg().mapping(id, 104)!!.sourceId)

        // When the guide's channel list changes, channels without a guide are tried again.
        provider.guideChannels = provider.guideChannels + ("wnbc.us" to "US - NBC WNBC NY")
        epg.importAll(listOf(id))
        assertEquals("wnbc.us", db.epg().mapping(id, 104)!!.xmlId)
        assertEquals(0.5, db.epg().mapping(id, 101)!!.score, 0.0)

        // The button: near-certain matches for channels without a guide (here as an older version left it),
        // with their programmes loaded.
        db.epg().upsertMappings(listOf(db.epg().mapping(id, 104)!!.copy(sourceId = null, xmlId = null, score = 0.0)))
        val progress = mutableListOf<GuideStep>()
        val result = epg.matchMissing(listOf(id)) { progress += it }
        assertEquals("wnbc.us", db.epg().mapping(id, 104)!!.xmlId)
        assertEquals(1, result.matched)
        assertTrue(result.checked >= 1)
        assertTrue(progress.any { it is GuideStep.Matching && it.total > 0 })
        val now = System.currentTimeMillis()
        assertEquals("Now on wnbc.us", epg.programmes(id, listOf(104), now, now + 3_600_000).getValue(104).first().title)

        // A new guide source matches everything again.
        epg.addSource("Extra", provider.url + "xmltv.php?username=me&password=secret&extra=1", id)
        epg.importAll(listOf(id))
        assertEquals(1.0, db.epg().mapping(id, 101)!!.score, 0.0)

        // A matched guide channel that leaves the guide is matched again (here to nothing).
        assertEquals("wnbc.us", db.epg().mapping(id, 104)!!.xmlId)
        provider.guideChannels = provider.guideChannels.filter { it.first != "wnbc.us" }
        epg.importAll(listOf(id))
        assertNull(db.epg().mapping(id, 104)!!.sourceId)
    }

    @Test
    fun `guide programmes know if they are a series episode or a film, and are found on demand`() = runBlocking {
        val id = addPlaylist()
        playlists.syncChannels(id)
        vod.sync(playlists.get(id)!!)
        val p = playlists.get(id)!!
        epg.ensureProviderSource(p, playlists.client(p).xmltvUrl())
        provider.nowProgramme = mapOf(
            "one.uk" to "<title>Line of Duty</title><category>Drama</category><episode-num system=\"xmltv_ns\">0.1.</episode-num>"
        )
        epg.importAll(listOf(id))
        val now = System.currentTimeMillis()
        val onNow = epg.programmes(id, listOf(101), now, now + 60_000).getValue(101).first()
        assertEquals(Triple(ProgrammeKind.SERIES, 1, 2), Triple(onNow.kind, onNow.season, onNow.episode))
        assertEquals(77L, vod.findTitle(id, onNow.title, CAT_SERIES))

        provider.nowProgramme = mapOf("one.uk" to "<title>Dune</title><category>Movie</category>")
        epg.importAll(listOf(id))
        assertEquals(ProgrammeKind.MOVIE, epg.programmes(id, listOf(101), now, now + 60_000).getValue(101).first().kind)
        assertEquals(501L, vod.findTitle(id, "Dune", CAT_MOVIE))
        assertEquals(501L, vod.findTitle(id, "DUNE!", CAT_MOVIE)) // compared without case or punctuation
        assertNull(vod.findTitle(id, "Dune: Part Two", CAT_MOVIE))
        assertNull(vod.findTitle(id, "Line of Duty", CAT_MOVIE))
    }

    @Test
    fun `where Live TV opened last (until 1_2_1) is forgotten`() = runBlocking {
        val old = androidx.datastore.preferences.core.stringPreferencesKey("last_live_filter")
        context.dataStore.edit { it[old] = "category:2" }
        settings.update { it }
        assertNull(context.dataStore.data.first()[old])
    }

    @Test
    fun `catch-up lists what can be replayed, from the provider only when asked, and briefly cached`() = runBlocking {
        val id = addPlaylist()
        playlists.syncChannels(id)
        val one = channels.get(id, 101)!!
        assertEquals(3, one.archiveDays) // known from the channel list itself
        assertEquals(0, channels.get(id, 102)!!.archiveDays)
        assertTrue(provider.requests.none { it.endsWith("get_simple_data_table") })

        val catchup = CatchupRepository(playlists)
        val p = playlists.get(id)!!
        val listing = catchup.listing(p, one)
        // Started, kept by the provider, and within the channel's 3 days.
        assertEquals(listOf("Yesterday Show", "Earlier Today", "On Now"), catchup.playable(listing, one).map { it.title })
        assertEquals("On Now", catchup.onNow(listing)?.title)
        assertEquals("About Earlier Today", listing.programmes.first { it.title == "Earlier Today" }.description)

        catchup.listing(p, one)
        assertEquals(1, provider.requests.count { it.endsWith("get_simple_data_table") })

        // The address runs from the chosen moment to now.
        val start = listing.programmes.first { it.title == "Earlier Today" }.startMs
        val url = catchup.url(p, one, listing, start)
        assertTrue(url, url.contains("/timeshift/me/secret/121/") && url.endsWith("/101.ts"))
    }

    @Test
    fun `M3U channels that declare catch-up get hourly slots and their archive's address`() = runBlocking {
        provider.m3u = """
            #EXTM3U
            #EXTINF:-1 tvg-id="One.uk" catchup="shift" catchup-days="3" group-title="United Kingdom",Channel One
            http://stream.example/one.m3u8
            #EXTINF:-1 tvg-id="Two.uk" catchup="append" catchup-source="?start={utc}&end={utcend}" group-title="United Kingdom",Channel Two
            http://stream.example/two.m3u8
            #EXTINF:-1 tvg-id="Odd.uk" catchup="vod" catchup-days="5" group-title="United Kingdom",Odd One
            http://stream.example/odd.m3u8
            #EXTINF:-1 tvg-id="Plain.uk" group-title="United Kingdom",Plain
            http://stream.example/plain.m3u8
        """.trimIndent()
        val id = playlists.addM3u("Free", provider.url + "free.m3u")
        playlists.syncChannels(id)
        val p = playlists.get(id)!!
        val all = db.channels().search(id, "", 10).associateBy { it.name }
        assertEquals(3, all.getValue("Channel One").archiveDays)
        assertEquals(7, all.getValue("Channel Two").archiveDays) // no days given: a week
        assertEquals(0, all.getValue("Odd One").archiveDays) // a kind of catch-up it can't play
        assertEquals(0, all.getValue("Plain").archiveDays)

        val now = 1_714_588_200_000L // 2024-05-01 18:30 UTC
        val catchup = CatchupRepository(playlists) { now }
        val one = all.getValue("Channel One")
        val listing = catchup.listing(p, one)
        // Three days of hours, from the first that starts within them to the one on now.
        assertEquals(3 * 24, catchup.playable(listing, one).size)
        val start = listing.programmes.last().startMs
        assertEquals("http://stream.example/one.m3u8?utc=${start / 1000}&lutc=${now / 1000}", catchup.url(p, one, listing, start))
        val two = all.getValue("Channel Two")
        assertEquals(
            "http://stream.example/two.m3u8?start=${start / 1000}&end=${now / 1000 + 60}",
            catchup.url(p, two, catchup.listing(p, two), start)
        )
    }
}
