package com.biskyt.mytvplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import com.biskyt.mytvplayer.AppText
import com.biskyt.mytvplayer.InAppLanguage
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.appString
import com.biskyt.mytvplayer.core.ui.AccentPalette
import com.biskyt.mytvplayer.data.ChannelFilter
import com.biskyt.mytvplayer.data.db.CAT_LIVE
import com.biskyt.mytvplayer.data.db.CAT_MOVIE
import com.biskyt.mytvplayer.data.db.CAT_SERIES
import com.biskyt.mytvplayer.data.db.VOD_EPISODE
import com.biskyt.mytvplayer.data.db.VOD_MOVIE
import com.biskyt.mytvplayer.data.db.WatchProgressEntity
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.Navigators
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.ui.common.initials
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
import com.biskyt.mytvplayer.ui.theme.Palette
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Google Play and Amazon Appstore listings' graphics and screenshots, drawn from the app itself so they always match it. Only run when
 * asked: `./gradlew :app:recordRoborazziDebug --tests '*StoreAssetsTest*' -PstoreAssets`. Files go to store/ at the
 * repository root. Demo channels and programmes are invented: no real broadcaster or title appears.
 *
 * xhdpi on a 960×540 dp screen gives the 1920×1080 pixels Play asks for in TV screenshots.
 *
 * With `-PstoreLanguage=<tag>` (one of AppText.languages) the screenshots are in that language, with some settings
 * pages too, in app/build/store-assets/<tag>/: for checking that the translated text fits.
 */
@OptIn(ExperimentalTestApi::class, DelicateCoilApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-xhdpi")
class StoreAssetsTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val provider = FakeProvider().apply {
        categories = listOf("1" to "News", "2" to "Sport", "3" to "Kids", "4" to "Entertainment", "5" to "Documentaries")
        streams = listOf(
            FakeProvider.Stream(101, 1, "Riverside One HD", "4", "one", archiveDays = 7),
            FakeProvider.Stream(102, 2, "Northbridge News", "1", "news", archiveDays = 3),
            FakeProvider.Stream(103, 3, "Harbour Sport 1", "2", "sport1", archiveDays = 3),
            FakeProvider.Stream(104, 4, "Harbour Sport 2", "2", "sport2"),
            FakeProvider.Stream(105, 5, "Little Sky Kids", "3", "kids", archiveDays = 2),
            FakeProvider.Stream(106, 6, "Lantern Drama", "4", "drama", archiveDays = 7),
            FakeProvider.Stream(107, 7, "Atlas Documentary", "5", "docs", archiveDays = 7),
            FakeProvider.Stream(108, 8, "Meadow Comedy", "4", "comedy"),
        ).map { it.copy(logo = art("${it.epgId}.png")) }
        guideChannels = listOf(
            "one" to "Riverside One HD",
            "news" to "Northbridge News",
            "sport1" to "Harbour Sport 1",
            "sport2" to "Harbour Sport 2",
            "kids" to "Little Sky Kids",
            "drama" to "Lantern Drama",
            "docs" to "Atlas Documentary",
            "comedy" to "Meadow Comedy"
        )
        fun now(title: String, desc: String) = "<title>$title</title><desc>$desc</desc><category>Series</category>"
        nowProgramme = mapOf(
            "one" to now("The Garden Planners", "Two neighbours turn a bare yard into a summer garden."),
            "news" to now("Evening Bulletin", "The day's main stories, with weather and sport."),
            "sport1" to now("Coastal Cup: Live", "The semi-final, live from the harbour stadium."),
            "sport2" to now("Cycling Highlights", "The best of the mountain stage."),
            "kids" to now("Paper Boats", "Mia and Leo build a fleet for the park pond."),
            "drama" to now("The Lighthouse Keeper", "A storm cuts the island off for the night."),
            "docs" to now("Wild Rivers", "Following salmon upstream through the autumn."),
            "comedy" to now("Flat Share", "Nobody admits to finishing the milk.")
        )
        nextTitle = mapOf(
            "one" to "Weekend Kitchen",
            "news" to "World Tonight",
            "sport1" to "Coastal Cup: Reaction",
            "sport2" to "Track Day",
            "kids" to "Star Painters",
            "drama" to "Harbour Lights",
            "docs" to "Night Skies",
            "comedy" to "Open Mic"
        )
        // The Blender Foundation's open movies, with their posters (CC BY; see demo-art/CREDITS.md).
        movieCategories = listOf("30" to "Animation", "31" to "Sci-fi")
        movies = listOf(
            Triple(801L, "Charge (2022)", "30"),
            Triple(802L, "Cosmos Laundromat (2015)", "30"),
            Triple(803L, "Tears of Steel (2012)", "31"),
            Triple(804L, "Sintel (2010)", "30"),
            Triple(805L, "Big Buck Bunny (2008)", "30"),
            Triple(806L, "Elephants Dream (2006)", "30"),
        )
        moviePoster = { id -> art(posterFile(movies.first { it.first == id }.second)) }
        // Only Big Buck Bunny's details page is shown.
        movieInfo = { id ->
            """{"info":{"plot":"$BUNNY_PLOT","director":"Sacha Goedegebure",""" +
                """"genre":"Animation, Comedy","releasedate":"2008-05-30","duration_secs":596,""" +
                """"backdrop_path":["${art("big-buck-bunny-still.jpg")}"]},"movie_data":{"stream_id":$id,"container_extension":"mkv"}}"""
        }
        // And their series: Caminandes, three shorts about Koro the llama.
        seriesCategories = listOf("40" to "Animation")
        series = listOf(Triple(901L, "Caminandes (2013)", "40"))
        seriesCover = { art("caminandes.jpg") }
        seriesReleaseDate = "2013-01-30"
        seriesInfo = {
            fun episode(n: Int, title: String, plot: String, secs: Int) =
                """{"id":"910$n","episode_num":$n,"title":"$title","container_extension":"mp4","season":1,""" +
                    """"info":{"plot":"$plot","duration_secs":$secs}}"""
            """{"seasons":[{"season_number":1,"name":"Season 1"}],
               "info":{"plot":"$LLAMA_PLOT","genre":"Animation, Comedy","backdrop_path":["${art("caminandes-still.jpg")}"]},
               "episodes":{"1":[${episode(1, "Llama Drama", "Koro wants to cross the road. So does the traffic.", 90)},
                                ${episode(2, "Gran Dillama", "The grass is greener beyond the electric fence.", 146)},
                                ${episode(3, "Llamigos", "Winter, a berry bush, and a penguin called Oti.", 150)}]}}"""
        }
    }
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var playlistId = 0L
    private val language = System.getProperty("storeLanguage").orEmpty()
    private val out = if (language.isEmpty()) OUT else "build/store-assets/$language"

    @Before
    fun setUp() = runBlocking {
        assumeTrue("Run with -PstoreAssets", System.getProperty("storeAssets") == "true")
        require(language.isEmpty() || language in AppText.languages) { "-PstoreLanguage: one of ${AppText.languages}" }
        AppText.setLanguage(app, language)
        val c = app.container
        // Robolectric's native graphics can only decode images on the main thread.
        SingletonImageLoader.setUnsafe(app.newImageLoader(app).newBuilder().decoderCoroutineContext(Dispatchers.Main.immediate).build())
        provider.images = { name -> javaClass.getResourceAsStream("/demo-art/$name")?.use { it.readBytes() } }
        playlistId = c.playlists.add("My TV provider", provider.url, "me", "secret")
        // The demo categories have no country prefix, so none is picked by default: show them all.
        c.db.categories().setIncluded(playlistId, CAT_LIVE, provider.categories.map { it.first }, true)
        c.playlists.syncChannels(playlistId)
        c.playlists.get(playlistId)?.let { c.epg.ensureProviderSource(it, c.playlists.client(it).xmltvUrl()) }
        c.epg.importAll(listOf(playlistId))
        listOf(101L, 103L, 105L, 107L).forEach { c.channels.setFavourite(playlistId, it, true) }
        listOf(102L, 106L, 104L).forEach { c.channels.markWatched(playlistId, it) }
    }

    @After
    fun tearDown() {
        provider.close()
        SingletonImageLoader.reset()
        AppText.setLanguage(app, "")
    }

    /** A movie's poster file in demo-art/, from its title ("Big Buck Bunny (2008)" → big-buck-bunny.jpg). */
    private fun posterFile(title: String) = title.substringBefore(" (").lowercase().replace(' ', '-') + ".jpg"

    /** Runs the main thread until [done]. */
    private fun idleUntil(timeoutMs: Long = 10_000, done: () -> Boolean) = compose.waitUntil(timeoutMs) {
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        done()
    }

    private fun waitFor(text: String) = idleUntil { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    /** Initials shown in place of a logo or poster until it loads. */
    private val placeholders by lazy {
        (
            provider.streams.map {
                it.name
            } + (provider.movies + provider.series).map { it.second.substringBefore(" (") }
            ).map { initials(it) }.toSet()
    }
    private val placeholder = SemanticsMatcher("logo or poster placeholder") { node ->
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text in placeholders }
    }

    private fun screenshot(name: String, start: Screen, ready: String, then: () -> Unit = {}) {
        val navigator = navigators.new(start)
        compose.setContent { InAppLanguage { MyTvTheme { NavHost(remember { navigator }) { ScreenContent(it) } } } }
        runCatching { waitFor(ready) }.onFailure {
            compose.onRoot().captureRoboImage("build/store-assets/stuck-$name.png")
            throw AssertionError("'$ready' not shown on $name", it)
        }
        then()
        // Logos and posters load in the background: wait until none is still showing its initials.
        idleUntil(15_000) { compose.onAllNodes(placeholder).fetchSemanticsNodes().isEmpty() }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$out/screenshots/$name.png")
        flatten("$out/screenshots/$name.png")
    }

    // Part-way through a film and a series, as well as the channels.
    @Test fun home() {
        runBlocking {
            val now = System.currentTimeMillis()
            app.container.vod.saveProgress(
                WatchProgressEntity(playlistId, VOD_MOVIE, 804, 530_000, 888_000, now, "Sintel", provider.moviePoster(804))
            )
            app.container.vod.saveProgress(
                WatchProgressEntity(
                    playlistId, VOD_EPISODE, 9102, 50_000, 146_000, now - 3_600_000, "Caminandes", provider.seriesCover(901),
                    seriesId = 901, season = 1, episode = 2, episodeTitle = "Gran Dillama", hasNext = true
                )
            )
        }
        screenshot("1-home", Screen.Home, "The Garden Planners")
    }

    @Test fun liveTv() = screenshot("2-live-tv", Screen.Live(ChannelFilter.All), "Evening Bulletin")

    @Test fun tvGuide() = screenshot("3-tv-guide", Screen.Live(ChannelFilter.All, guide = true), "World Tonight")

    // Its last row, so the list has all its channels.
    @Test fun catchup() = screenshot("4-catch-up", Screen.Live(ChannelFilter.Catchup), "Wild Rivers")

    @Test fun movies() {
        loadMoviesAndSeries()
        screenshot("5-movies", Screen.Movies(), "Elephants Dream")
    }

    @Test fun movieDetails() {
        loadMoviesAndSeries()
        screenshot("7-movie-details", Screen.MovieDetails(playlistId, 805), "Sacha Goedegebure")
    }

    @Test fun seriesDetails() {
        loadMoviesAndSeries()
        screenshot("8-series-details", Screen.SeriesDetails(playlistId, 901), "Llamigos")
    }

    private fun loadMoviesAndSeries() = runBlocking {
        val c = app.container
        c.db.categories().setIncluded(playlistId, CAT_MOVIE, provider.movieCategories.map { it.first }, true)
        c.db.categories().setIncluded(playlistId, CAT_SERIES, provider.seriesCategories.map { it.first }, true)
        c.vod.sync(c.playlists.get(playlistId)!!)
    }

    // One channel set by hand (an hour's shift), and another open on its suggestions.
    @Test fun guideMatching() {
        runBlocking {
            val source = app.container.db.epg().sourcesFor(listOf(playlistId)).first().id
            app.container.epg.setManual(playlistId, 108, source, "comedy", offsetMinutes = 60)
        }
        screenshot("6-guide-matching", Screen.GuideMatching(playlistId), "Meadow Comedy") {
            // As AppUiTest does: performClick() doesn't reach the TV card's onClick under Robolectric.
            compose.onAllNodes(hasText("Harbour Sport 2") and hasClickAction())[0].performSemanticsAction(SemanticsActions.OnClick)
            waitFor(appString(R.string.live_guide_time_shift, 0).substringBefore(':'))
        }
    }

    // Not in the store listing: settings pages, whose long labels are the likeliest not to fit when translated.
    @Test fun settings() = translatedOnly { screenshot("9-settings", Screen.Settings, appString(R.string.settings_playlists_title)) }

    @Test fun playbackSettings() =
        translatedOnly { screenshot("10-playback-settings", Screen.PlaybackSettings, appString(R.string.settings_subtitle_language)) }

    @Test fun playlists() = translatedOnly { screenshot("11-playlists", Screen.Playlists, "My TV provider") }

    @Test fun categories() = translatedOnly { screenshot("12-categories", Screen.Categories(playlistId), "Documentaries") }

    private fun translatedOnly(shot: () -> Unit) {
        assumeTrue("Run with -PstoreLanguage", language.isNotEmpty())
        shot()
    }

    @Test fun icon() = graphic("icon-512", 256.dp, 256.dp) { Icon() }

    @Test fun featureGraphic() = graphic("feature-graphic-1024x500", 512.dp, 250.dp) { Feature() }

    @Test fun tvBanner() = graphic("tv-banner-1280x720", 640.dp, 360.dp) { Banner() }

    // Amazon's small and large icons, if the console asks for them (it wants no transparency).
    @Test fun amazonIconSmall() = graphic("amazon-icon-114", 57.dp, 57.dp) { Icon() }

    @Test fun amazonIconLarge() = graphic("amazon-icon-512", 256.dp, 256.dp) { Icon() }

    @Test fun fireTvBackground() = graphic("fire-tv-background-1920x1080", 960.dp, 540.dp) { Background() }

    /** [width] × [height] in dp, each exactly 2 px: half the pixel size the store asks for. */
    private fun graphic(name: String, width: Dp, height: Dp, content: @Composable () -> Unit) {
        compose.setContent {
            // The app scales itself to the TV; here every dp is exactly 2 px.
            MyTvTheme {
                CompositionLocalProvider(LocalDensity provides Density(2f, 1f)) {
                    Box(Modifier.requiredSize(width, height).testTag(name)) { content() }
                }
            }
        }
        compose.onNodeWithTag(name).captureRoboImage("$out/graphics/$name.png")
        // Play's icon may be transparent; everything else (Amazon's icons too) is without (24-bit).
        if (name != "icon-512") flatten("$out/graphics/$name.png")
    }

    /** Rewrites a PNG without its alpha channel. */
    private fun flatten(path: String) {
        val file = java.io.File(path)
        if (!file.exists()) return // only when recording
        val bitmap = android.graphics.BitmapFactory.decodeFile(path).copy(android.graphics.Bitmap.Config.ARGB_8888, true)
        bitmap.setHasAlpha(false)
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** The launcher icon, full-bleed (Play rounds the corners itself). */
    @Composable
    private fun Icon() {
        Box(Modifier.fillMaxSize().background(Accent), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize())
        }
    }

    @Composable
    private fun Feature() {
        Row(
            Modifier.fillMaxSize().background(Palette.background).padding(horizontal = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(28.dp)
        ) {
            Box(Modifier.size(130.dp).clip(RoundedCornerShape(28.dp))) { Icon() }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Wordmark(34)
                Text("Live TV, TV guide and catch-up", color = Palette.text, fontSize = 15.sp, softWrap = false)
                Text("Movies and series from your provider", color = Palette.textMuted, fontSize = 15.sp, softWrap = false)
            }
        }
    }

    @Composable
    private fun Banner() {
        Column(
            Modifier.fillMaxSize().background(Palette.background),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(Modifier.size(150.dp).clip(RoundedCornerShape(32.dp))) { Icon() }
            Box(Modifier.padding(top = 24.dp)) { Wordmark(56) }
        }
    }

    /**
     * Amazon's Fire TV background: shown behind the app's details, which sit on the left, so it has no text. A soft glow
     * of the accent colour over the app's dark ground, with a few rows of a TV guide on the right (the one on now lit).
     */
    @Composable
    private fun Background() {
        val glow = Accent.copy(alpha = 0.28f)
        Box(
            Modifier.fillMaxSize().background(Palette.background).drawBehind {
                val center = Offset(size.width * 0.78f, size.height * 0.28f)
                drawRect(Brush.radialGradient(listOf(glow, Color.Transparent), center, size.width * 0.68f))
            }
        ) {
            Column(
                Modifier.align(Alignment.CenterEnd).padding(end = 48.dp).width(430.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                GuideRows.forEachIndexed { row, cells -> GuideRow(cells, onNow = row == LIT_ROW) }
            }
        }
    }

    /** One row of programme blocks; [cells] are their widths in tenths of the row, and the first is on now. */
    @Composable
    private fun GuideRow(cells: List<Int>, onNow: Boolean) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            cells.forEachIndexed { i, width ->
                val colour = when {
                    i == 0 && onNow -> Accent.copy(alpha = 0.55f)
                    i == 0 -> Palette.surface.copy(alpha = 0.9f)
                    else -> Palette.surface.copy(alpha = 0.6f)
                }
                Box(Modifier.weight(width.toFloat()).height(46.dp).clip(RoundedCornerShape(12.dp)).background(colour))
            }
        }
    }

    /** "MyTVPlayer" with "TV" in the accent colour, as in the app's top bar. */
    @Composable
    private fun Wordmark(size: Int) {
        val style = MaterialTheme.typography.titleLarge.copy(fontSize = size.sp)
        Row {
            Text("My", style = style, color = Palette.text, softWrap = false)
            Text("TV", style = style, color = Accent, softWrap = false)
            Text("Player", style = style, color = Palette.text, softWrap = false)
        }
    }

    private companion object {
        const val BUNNY_PLOT = "A giant, gentle rabbit's lazy day in the meadow is spoiled by three bullying rodents. " +
            "Big mistake: he decides to get even."
        const val LLAMA_PLOT = "Koro the llama will go to any lengths for greener grass in the Patagonian hills. " +
            "Three short comedies from the Blender Foundation."
        const val OUT = "../store"

        /** The Fire TV background's guide: programme widths per row, in tenths. */
        val GuideRows = listOf(listOf(4, 6), listOf(7, 3), listOf(3, 3, 4), listOf(5, 5), listOf(6, 4), listOf(2, 5, 3))
        const val LIT_ROW = 2
        val Accent = Color(AccentPalette.DEFAULT)
    }
}
