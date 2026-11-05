package com.biskyt.mytvplayer.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.data.db.VOD_EPISODE
import com.biskyt.mytvplayer.data.db.VOD_MOVIE
import com.biskyt.mytvplayer.data.db.WatchProgressEntity
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.Navigators
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.addHomeTv
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.focusedText
import com.biskyt.mytvplayer.testutil.waitAdvancingClock
import com.biskyt.mytvplayer.testutil.waitForText
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
import com.biskyt.mytvplayer.ui.theme.Palette
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The film and series pages in the four states of the design boards (2026-11-05), recorded to compare with them:
 * a film with progress ("Main"), a film with almost no data ("TvFilmMinimal"), and a series with and without episode
 * pictures ("TvSeries", "TvSeriesNoPic"), with episode 4 open.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class DetailPagesScreenshotTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val provider = FakeProvider()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()

    @After
    fun tearDown() = provider.close()

    private fun start(screen: (Long) -> Screen, pictures: Boolean = true): Long {
        provider.images = { name -> art(name) }
        provider.moviePoster = { provider.art("poster-$it.png") }
        provider.movieRating = { if (it == 501L) "7.4" else null }
        provider.seriesCover = { provider.art("cover-$it.png") }
        provider.movies =
            listOf(Triple(501L, "EN: The Long Harbour (2021)", "10"), Triple(502L, "UK| NIGHT OF THE LIGHTHOUSE (1958) FHD", "13"))
        provider.movieCategories = listOf("10" to "UK | MOVIES", "13" to "UK | Movies: Classics")
        provider.movieInfo =
            { id ->
                if (id ==
                    "501"
                ) {
                    LONG_HARBOUR.replace("BACK", provider.art("back.png"))
                } else {
                    """{"info":{},"movie_data":{"stream_id":$id}}"""
                }
            }
        provider.series = listOf(Triple(77L, "EN: Kitchen Masters USA (2010) Multi-Audio - Gordon Hale [FHD] (Europe)", "20"))
        provider.seriesInfo = { seriesInfo(if (pictures) provider::art else null) }
        val id = runBlocking { app.container.addHomeTv(provider, vod = true) }
        val navigator = navigators.new(screen(id))
        // On the app's background, as AppRoot paints it below every screen.
        compose.setContent {
            MyTvTheme {
                Box(Modifier.fillMaxSize().background(Palette.background)) { NavHost(remember { navigator }) { ScreenContent(it) } }
            }
        }
        return id
    }

    private var saved = 0L

    /** Progress on [item] (an episode of series 77 when [episode] is given); each saved after the one before. */
    private fun save(id: Long, item: Long, position: Long, duration: Long, episode: Int? = null) = runBlocking {
        val kind = if (episode != null) VOD_EPISODE else VOD_MOVIE
        val series = episode?.let { 77L }
        app.container.vod.saveProgress(
            WatchProgressEntity(id, kind, item, position, duration, 1_700_000_000_000 + saved++, "Title", null, series, 2, episode)
        )
    }

    @Test
    fun `a film with progress`() {
        val id = start({ Screen.MovieDetails(it, 501) })
        save(id, 501, 74 * MIN, 112 * MIN)
        compose.waitForText("Resume · 38 min left")
        compose.waitForText("Director: Ana Ruiz")
        compose.waitAdvancingClock { compose.focusedText().contains("Resume") }
        // The progress bar must not size its button: Resume is a pill as high as Favourite, beside it on the screen.
        val resume = compose.onNode(hasText("Resume · 38 min left") and hasClickAction()).getBoundsInRoot()
        val favourite = compose.onNode(hasText("Favourite") and hasClickAction()).assertIsDisplayed().getBoundsInRoot()
        assertEquals((favourite.bottom - favourite.top).value, (resume.bottom - resume.top).value, 1f)
        assertTrue(resume.right < favourite.left)
        compose.onRoot().captureRoboImage("screenshots/redesign-film.png")
    }

    @Test
    fun `a film with almost no data`() {
        start({ Screen.MovieDetails(it, 502) })
        compose.waitForText("Movies: Classics")
        compose.waitAdvancingClock { compose.focusedText().contains("Play") }
        compose.onRoot().captureRoboImage("screenshots/redesign-film-minimal.png")
    }

    @Test
    fun `a series with episode pictures`() = series(pictures = true, "screenshots/redesign-series.png")

    @Test
    fun `a series with no episode pictures`() = series(pictures = false, "screenshots/redesign-series-no-pictures.png")

    private fun series(pictures: Boolean, file: String) {
        val id = start({ Screen.SeriesDetails(it, 77) }, pictures)
        listOf(2, 3).forEach { save(id, 9200L + it, 58 * MIN, 58 * MIN, it) }
        save(id, 9205, 43 * MIN, 55 * MIN, 5)
        save(id, 9204, 33 * MIN, 54 * MIN, 4)
        compose.waitForText("Continue S2 E4 · 21 min left")
        // The season shown, its year (from its first episode), then the show's facts.
        compose.waitForText("Series 2 · 2023 · 3 seasons · Crime")
        compose.waitForText("The First Service")
        compose.onNode(
            hasText("The First Service", substring = true) and hasClickAction()
        ).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForText("More…")
        compose.waitForText("54 min · 21 min left · First shown 14 Mar 2023")
        assertTrue(compose.focusedText(), compose.focusedText().contains("The First Service"))
        // With a 4-line plot, the favourite's heart is beside Continue, as high as it, and inside the screen. In pixels
        // (MyTvTheme gives the page its own density), from each node's position: boundsInRoot is clipped by its parents.
        val screen = compose.onRoot().fetchSemanticsNode().size.height
        val resume = compose.onNode(hasText("Continue", substring = true) and hasClickAction()).fetchSemanticsNode()
        val heart = compose.onNode(hasContentDescription("Add to favourites") and hasClickAction()).fetchSemanticsNode()
        assertEquals(resume.positionInRoot.y, heart.positionInRoot.y, 1f)
        assertEquals(resume.size.height, heart.size.height)
        assertTrue(heart.positionInRoot.x > resume.positionInRoot.x + resume.size.width)
        val bottom = heart.positionInRoot.y + heart.size.height
        assertTrue("The heart ends at $bottom px, the screen at $screen px", bottom <= screen)
        compose.onRoot().captureRoboImage(file)
    }

    private companion object {
        const val MIN = 60_000L

        const val LONG_HARBOUR =
            """{"info":{"plot":"A harbour master on a remote island finds a boat with no crew and starts to ask who sent it. """ +
                """As the winter storms close the port, the islanders take sides.",""" +
                """"cast":"Tom Hale, Ines Moreau, Kofi Mensah, Ruth Lane",""" +
                """"director":"Ana Ruiz","genre":"Drama, Mystery","releasedate":"2021-03-02","duration_secs":6720,"rating":"7.4",""" +
                """"backdrop_path":["BACK"]},"movie_data":{"stream_id":501,"container_extension":"mkv"}}"""

        /** Series 2 as the boards show it; [art] names each episode's picture, when it has one. */
        fun seriesInfo(art: ((String) -> String)?): String {
            val names = listOf("Auditions Day 1", "Auditions Day 2", "Mystery Box", "The First Service", "Team Challenge")
            val plots =
                listOf(
                    "",
                    "",
                    "",
                    "A body on the rocks below the lighthouse leads Rose to a keeper who has not left the island in ten years.",
                    ""
                )
            val minutes = listOf(59, 58, 57, 54, 55)
            val shown = listOf("2023-02-21", "2023-02-28", "2023-03-07", "2023-03-14", "2023-03-21")
            val episodes = names.indices.joinToString(",") { i ->
                val n = i + 1
                val image = art?.let { ""","movie_image":"${it("ep$n.png")}"""" }.orEmpty()
                """{"id":"92${"%02d".format(
                    n
                )}","episode_num":$n,"title":"EN: Kitchen Masters USA (2010) - S02E0$n - ${names[i]} (Europe)[1080p]",""" +
                    """"container_extension":"mkv","season":2,"info":{"plot":"${plots[i]}","air_date":"${shown[i]}",""" +
                    """"duration_secs":${minutes[i] * 60}$image}}"""
            }
            return """{"seasons":[{"season_number":1,"name":"Series 1"},{"season_number":2,"name":"Series 2"},
               {"season_number":3,"name":"Series 3"}],
               "info":{"name":"EN: Kitchen Masters USA (2010)",
               "plot":"Two detectives on the north coast work the cases nobody else wants, while a cold case from their past comes back. As the town turns against them, they find that the answer was in their own files all along, and the storm season gives them one week to prove it.",
               "genre":"Crime","releaseDate":"2023-01-01"},
               "episodes":{"1":[{"id":"9101","episode_num":1,"title":"Pilot","container_extension":"mkv","season":1,"info":{}}],
                           "2":[$episodes],
                           "3":[{"id":"9301","episode_num":1,"title":"Return","container_extension":"mkv","season":3,"info":{}}]}}"""
        }

        /** A plain picture for [name], in a gradient of two greys, as the boards draw their placeholders. */
        fun art(name: String): ByteArray {
            val (w, h) = if (name.startsWith("back")) 640 to 360 else 200 to 300
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val paint = Paint().apply {
                shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), 0xFF546A80.toInt(), 0xFF26323E.toInt(), Shader.TileMode.CLAMP)
            }
            Canvas(bitmap).drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
            return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        }
    }
}
