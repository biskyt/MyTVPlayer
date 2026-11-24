package com.biskyt.mytvplayer.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogoLookTest {
    private val clear = 0x00000000
    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val yellow = 0xFFFFD000.toInt()

    /** A [w] by [h] picture of [background], with [fill]'s colour where it gives one. */
    private fun picture(w: Int, h: Int, background: Int, fill: (Int, Int) -> Int?) =
        IntArray(w * h) { i -> fill(i % w, i / w) ?: background }

    @Test
    fun `the transparent border is cut off`() {
        val pixels = picture(10, 6, clear) { x, y -> if (x in 2..7 && y in 1..3) white else null }
        val look = LogoLook.of(pixels, 10, 6)!!
        assertEquals(LogoLook(2, 1, 8, 4, dark = false), look)
        assertEquals(6, look.width)
        assertEquals(3, look.height)
    }

    @Test
    fun `black text on a transparent background is dark`() {
        // Black text with a yellow mark, as Comedy Central's logo.
        val pixels = picture(10, 4, clear) { x, y ->
            when {
                y !in 1..2 -> null
                x in 0..2 -> yellow
                x in 4..9 -> black
                else -> null
            }
        }
        assertTrue(LogoLook.of(pixels, 10, 4)!!.dark)
    }

    @Test
    fun `thin black letters beside a large yellow mark are enough`() {
        // One black column in eight, under a transparent row.
        val pixels = picture(16, 4, clear) { x, y ->
            if (y == 0) {
                null
            } else if (x % 8 == 7) {
                black
            } else {
                yellow
            }
        }
        assertTrue(LogoLook.of(pixels, 16, 4)!!.dark)
    }

    @Test
    fun `white letters with a black outline are not dark`() {
        // Half white, a quarter black: a light card would hide the white.
        val pixels = picture(8, 4, clear) { x, y ->
            if (y == 0) {
                null
            } else if (x % 4 == 0) {
                black
            } else {
                white
            }
        }
        assertFalse(LogoLook.of(pixels, 8, 4)!!.dark)
    }

    @Test
    fun `a light logo, or a dark one with its own background, is not dark`() {
        assertFalse(LogoLook.of(picture(10, 4, clear) { _, y -> if (y in 1..2) white else null }, 10, 4)!!.dark)
        // Opaque: no transparent background for a dark logo to be lost on.
        assertFalse(LogoLook.of(picture(10, 4, black) { _, _ -> null }, 10, 4)!!.dark)
    }

    @Test
    fun `an all transparent picture has no logo`() {
        assertNull(LogoLook.of(IntArray(12) { clear }, 4, 3))
    }

    @Test
    fun `lightening makes the dark parts white, and keeps the colours and the transparency`() {
        val halfBlack = 0x80000000.toInt()
        val pixels = intArrayOf(black, halfBlack, yellow, clear)
        LogoLook.lighten(pixels)
        assertEquals(listOf(white, 0x80FFFFFF.toInt(), yellow, clear), pixels.toList())
    }
}
