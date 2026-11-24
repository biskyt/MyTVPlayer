package com.biskyt.mytvplayer.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.addHomeTv
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.focusedText
import com.biskyt.mytvplayer.testutil.waitAdvancingClock
import com.biskyt.mytvplayer.testutil.waitForText
import com.biskyt.mytvplayer.ui.theme.Palette
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The browsing screens' background follows the card with focus, as MyTVLauncher's Home (the owner, 2026-11-24). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class AmbientUiTest {
    @get:Rule val compose = createUiComposeRule<ComponentActivity>()

    private val provider = FakeProvider()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()

    @Before
    fun setUp() = runBlocking {
        val red = ByteArrayOutputStream().use {
            Bitmap.createBitmap(124, 186, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
                .compress(Bitmap.CompressFormat.PNG, 100, it)
            it.toByteArray()
        }
        // Every film's poster is red.
        provider.images = { if (it == "red.png") red else null }
        provider.moviePoster = { provider.art("red.png") }
        app.container.addHomeTv(provider)
        Unit
    }

    @After
    fun tearDown() = provider.close()

    /** The colour at the screen's left edge, half way down: clear of the cards, where the background shows. */
    private fun edge(): Int {
        var pixel = 0
        compose.runOnUiThread {
            val v = compose.activity.window.decorView
            val img = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            v.draw(Canvas(img))
            pixel = img.getPixel(2, v.height / 2)
        }
        return pixel
    }

    /** Opens the films, whose first poster takes the focus. */
    private fun openMovies() {
        compose.setContent { AppRoot() }
        compose.waitForText("Movies", stepMs = 50)
        compose.onNode(hasText("Movies") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
    }

    @Test
    fun `a red poster with focus tints the background red`() {
        openMovies()
        compose.waitAdvancingClock(stepMs = 50) { edge().let { Color.red(it) > Color.green(it) + 20 } }
    }

    @Test
    fun `with the background brightness at 0, the background stays plain and dark`() {
        runBlocking { app.container.settings.update { it.copy(backdropLevel = 0) } }
        openMovies()
        // A poster has the focus: without the setting at 0, the background would now follow it.
        compose.waitAdvancingClock(stepMs = 50) { FILMS.any { it in compose.focusedText() } }
        // Past the settle delay and the fade (40 steps of 50 ms): still the plain background.
        var steps = 0
        compose.waitAdvancingClock(stepMs = 50) { ++steps > 40 }
        assertEquals(Palette.background.toArgb(), edge())
    }

    private companion object {
        /** The fake provider's films, as their cards show them. */
        val FILMS = listOf("Dune", "Paddington", "Amélie", "Heat")
    }
}
