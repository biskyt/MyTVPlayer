package com.biskyt.mytvplayer.core.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class BackdropLevelTest {
    @Test
    fun `the overlay and the glows follow the level`() {
        assertEquals(0.8f, BackdropLevel.overlayAlpha(20), 0.001f)
        assertEquals(1f, BackdropLevel.overlayAlpha(0), 0.001f)
        assertEquals(1f, BackdropLevel.glowScale(28), 0.001f)
        assertEquals(0f, BackdropLevel.glowScale(0), 0.001f)
    }

    @Test
    fun `a value that is not a choice gives the default`() {
        assertEquals(20, BackdropLevel.valid(7))
        assertEquals(35, BackdropLevel.valid(35))
    }
}
