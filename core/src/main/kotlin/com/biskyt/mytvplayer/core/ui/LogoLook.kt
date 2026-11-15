package com.biskyt.mytvplayer.core.ui

/**
 * What a TV channel's logo needs to look right on a card (the owner, 2026-11-15), from MyTVLauncher: the part of the picture that is not
 * transparent, from [left] and [top] to [right] and [bottom] (exclusive), and whether it is [dark]: it has dark parts
 * (such as black text) on a transparent background, which a dark card would hide, and it is not mostly white or very
 * light, which a light card would hide.
 */
data class LogoLook(val left: Int, val top: Int, val right: Int, val bottom: Int, val dark: Boolean) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    companion object {
        /** A pixel with less alpha than this (of 255) counts as transparent. */
        private const val SEEN_ALPHA = 24

        /** A pixel with more alpha than this counts when the logo's colour is judged. */
        private const val SOLID_ALPHA = 128

        /** A pixel darker than this (0 to 1) counts as dark. */
        private const val DARK_LUMINANCE = 0.2

        /** A pixel lighter than this counts as light. */
        private const val LIGHT_LUMINANCE = 0.85

        /** The logo is dark when more than this part of its solid pixels is dark (thin black letters are enough)... */
        private const val DARK_SHARE = 0.12

        /** ...and no more than this part is light. */
        private const val LIGHT_SHARE = 0.3

        /** It is on a transparent background when at least this part of the picture is transparent. */
        private const val TRANSPARENT_SHARE = 0.05

        /**
         * Looks at a picture's ARGB [pixels] ([width] by [height], row by row). Null for a picture that is all
         * transparent.
         */
        fun of(pixels: IntArray, width: Int, height: Int): LogoLook? {
            var left = width
            var top = height
            var right = -1
            var bottom = -1
            var transparent = 0
            var solid = 0
            var dark = 0
            var light = 0
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val p = pixels[y * width + x]
                    val alpha = p ushr 24
                    if (alpha < SEEN_ALPHA) {
                        transparent++
                        continue
                    }
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                    if (alpha > SOLID_ALPHA) {
                        solid++
                        val l = luminance(p)
                        if (l < DARK_LUMINANCE) dark++
                        if (l > LIGHT_LUMINANCE) light++
                    }
                }
            }
            if (right < 0) return null
            val onTransparent = transparent >= TRANSPARENT_SHARE * width * height
            val isDark = onTransparent && solid > 0 && dark > DARK_SHARE * solid && light <= LIGHT_SHARE * solid
            return LogoLook(left, top, right + 1, bottom + 1, isDark)
        }

        /** How light a pixel looks, from 0 to 1 (Rec. 709 weights, on the stored values). */
        private fun luminance(p: Int): Double {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            return (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255
        }
    }
}
