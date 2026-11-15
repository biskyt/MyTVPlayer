package com.biskyt.mytvplayer.ui.common

import android.graphics.Bitmap
import android.graphics.Color
import com.biskyt.mytvplayer.testutil.TestApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A channel logo with black text on a transparent background is dark, and gets a light tile (the owner, 2026-11-15); a
 * white one, or one with its own background, is not. What is found is kept for the logo's address.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class)
class LogoDarkTest {
    /** A [w] by [h] logo: [ink] in its middle third of rows, on [background]. */
    private fun logo(ink: Int, background: Int, w: Int = 30, h: Int = 12) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
        for (y in 0 until h) for (x in 0 until w) setPixel(x, y, if (y in h / 3 until 2 * h / 3) ink else background)
    }

    @Test
    fun `black text on a transparent background is dark, and noted for its address`() {
        assertTrue(LogoDark.look("http://logo/black.png", logo(Color.BLACK, Color.TRANSPARENT)))
        assertEquals(true, LogoDark.known("http://logo/black.png"))
    }

    @Test
    fun `a white logo, or a black one with its own background, is not dark`() {
        assertFalse(LogoDark.look("http://logo/white.png", logo(Color.WHITE, Color.TRANSPARENT)))
        assertFalse(LogoDark.look("http://logo/boxed.png", logo(Color.BLACK, Color.RED)))
        assertEquals(false, LogoDark.known("http://logo/white.png"))
    }
}
