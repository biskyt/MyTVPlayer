package com.biskyt.mytvplayer.ui.phone

import android.content.pm.PackageManager
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
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.data.db.VOD_EPISODE
import com.biskyt.mytvplayer.data.db.VOD_MOVIE
import com.biskyt.mytvplayer.data.db.WatchProgressEntity
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.FakeTmdb
import com.biskyt.mytvplayer.testutil.Navigators
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.addHomeTv
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.waitAdvancingClock
import com.biskyt.mytvplayer.testutil.waitForText
import com.biskyt.mytvplayer.ui.NavHost
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.ScreenContent
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
import com.biskyt.mytvplayer.ui.theme.Palette
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The film and series pages on a phone (phone spike, 2026-11-25), and on an unfolded foldable, with More open as a
 * sheet, recorded to compare with the mock-ups.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "w390dp-h844dp-xxhdpi")
class PhoneDetailPagesScreenshotTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val provider = FakeProvider()
    private val tmdb = FakeTmdb()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var playlistId = 0L

    @Before
    fun setUp() {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_TOUCHSCREEN, true)
        provider.images = { art(it) }
        provider.moviePoster = { provider.art("poster-$it.png") }
        provider.seriesCover = { provider.art("cover-$it.png") }
        playlistId = runBlocking { app.container.addHomeTv(provider, vod = true) }
        tmdb.use(app.container)
    }

    @After
    fun tearDown() {
        provider.close()
        tmdb.close()
    }

    private fun open(screen: Screen) {
        val navigator = navigators.new(screen)
        compose.setContent {
            MyTvTheme {
                Box(Modifier.fillMaxSize().background(Palette.background)) { NavHost(remember { navigator }) { ScreenContent(it) } }
            }
        }
    }

    private fun save(kind: String, item: Long, position: Long, duration: Long, episode: Int? = null) = runBlocking {
        val series = episode?.let { 77L }
        app.container.vod.saveProgress(
            WatchProgressEntity(playlistId, kind, item, position, duration, 1_700_000_000_000, "Title", null, series, 1, episode)
        )
    }

    private fun film(name: String) {
        save(VOD_MOVIE, 501, 74 * MIN, 112 * MIN)
        open(Screen.MovieDetails(playlistId, 501))
        compose.waitForText("Resume")
        compose.waitForText("Timothée Chalamet")
        compose.waitAdvancingClock(stepMs = 50) { true }
        compose.onRoot().captureRoboImage("screenshots/phone/$name.png")
        compose.onNode(hasText("More") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForText("Cast and crew")
        repeat(10) { compose.waitAdvancingClock(stepMs = 50) { true } }
        captureScreenRoboImage("screenshots/phone/$name-more.png")
    }

    private fun series(name: String) {
        save(VOD_EPISODE, 9001, 20 * MIN, 60 * MIN, 1)
        open(Screen.SeriesDetails(playlistId, 77))
        compose.waitForText("Continue")
        compose.waitForText("Episode 1")
        compose.waitAdvancingClock(stepMs = 50) { true }
        compose.onRoot().captureRoboImage("screenshots/phone/$name.png")
    }

    @Test
    fun `the film page on a phone`() = film("film-phone")

    @Test
    fun `the series page on a phone`() = series("series-phone")

    @Test
    @Config(qualifiers = "w840dp-h720dp-xhdpi")
    fun `the film page unfolded`() = film("film-unfolded")

    @Test
    @Config(qualifiers = "w840dp-h720dp-xhdpi")
    fun `the series page unfolded`() = series("series-unfolded")

    private companion object {
        const val MIN = 60_000L

        /** A plain picture for [name], in a gradient, as the boards draw their placeholders. */
        fun art(name: String): ByteArray {
            val (w, h) = if (name.startsWith("back")) 640 to 360 else 200 to 300
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val paint = Paint().apply {
                shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), 0xFF6A4A2C.toInt(), 0xFF2B3B52.toInt(), Shader.TileMode.CLAMP)
            }
            Canvas(bitmap).drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
            return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        }
    }
}
