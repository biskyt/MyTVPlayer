package com.biskyt.mytvplayer.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class PaletteContrastTest {
    private fun contrast(a: Color, b: Color): Float {
        val (light, dark) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (light + 0.05f) / (dark + 0.05f)
    }

    @Test
    fun `every text colour is clear on every surface text sits on`() {
        // Each with the least contrast it needs: secondary text has to read from across the room too.
        val texts = listOf(
            Triple("text", Palette.text, 12f),
            Triple("textMuted", Palette.textMuted, 12f),
            Triple("textDim", Palette.textDim, 8f)
        )
        val surfaces = mapOf(
            "background" to Palette.background,
            "surface" to Palette.surface,
            "surfaceHigh" to Palette.surfaceHigh,
            "surfaceLow" to Palette.surfaceLow
        )
        for ((t, fg, least) in texts) {
            for ((s, bg) in surfaces) {
                val ratio = contrast(fg, bg)
                assertTrue("$t on $s is only $ratio to 1", ratio >= least)
            }
        }
    }
}
