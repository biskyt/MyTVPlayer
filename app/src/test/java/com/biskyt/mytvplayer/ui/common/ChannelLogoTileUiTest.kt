package com.biskyt.mytvplayer.ui.common

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.assertColourAcross
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.drawScreen
import com.biskyt.mytvplayer.testutil.waitAdvancingClock
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
import java.io.ByteArrayOutputStream
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Every channel logo shows on the light tile, also a white one (the owner, 2026-11-24). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class ChannelLogoTileUiTest {
    @get:Rule val compose = createUiComposeRule<ComponentActivity>()

    private val provider = FakeProvider()

    @After
    fun tearDown() = provider.close()

    /** A white logo on a transparent background: before, it got the dark tile. */
    private fun whiteLogo(): ByteArray {
        val bitmap = Bitmap.createBitmap(LOGO_WIDTH, LOGO_HEIGHT, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawRect(20f, 20f, 100f, 40f, Paint().apply { color = android.graphics.Color.WHITE })
        return ByteArrayOutputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            it.toByteArray()
        }
    }

    @Test
    fun `a white logo shows on the light tile`() {
        val logo = whiteLogo()
        provider.images = { if (it == "white.png") logo else null }
        compose.setContent {
            MyTvTheme { Box(Modifier.testTag("logo")) { ChannelLogo(provider.art("white.png"), "Channel One") } }
        }
        // The initials show until the logo does.
        compose.waitAdvancingClock { compose.onAllNodes(hasText("CO")).fetchSemanticsNodes().isEmpty() }
        val screen = compose.drawScreen()
        // Next to the left edge, in the padding round the logo: the tile.
        assertColourAcross(screen, compose.onNodeWithTag("logo").fetchSemanticsNode(), listOf(2), LIGHT_LOGO_TILE)
    }
}

private const val LOGO_WIDTH = 120
private const val LOGO_HEIGHT = 60
