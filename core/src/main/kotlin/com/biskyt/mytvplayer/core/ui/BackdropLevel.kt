package com.biskyt.mytvplayer.core.ui

/**
 * How bright the browsing screens' background is, in percent (MyTVLauncher's, the owner, 2026-11-24; set in
 * Appearance): how much of the selected card's picture shows through the dark background, and the glows' strength in
 * step with it. 0 is a plain dark background. The glow around the card with focus does not change with it.
 */
object BackdropLevel {
    /** MyTVLauncher 1.1.0's brightness (its overlay was 72% dark): the glows' strength here is their strength there. */
    const val FIRST_LEVEL = 28f

    val CHOICES = (0..40 step 5).toList()
    const val DEFAULT = 20

    /** How much of the dark background lies over the backdrop at [percent] (1: all of it, so none of the picture). */
    fun overlayAlpha(percent: Int): Float = 1f - percent / 100f

    /** The glows' strength at [percent], as a share of their MyTVLauncher 1.1.0 strength (1 at [FIRST_LEVEL]). */
    fun glowScale(percent: Int): Float = percent / FIRST_LEVEL

    /** A saved or restored value as a choice: one that is not a choice gives the default. */
    fun valid(percent: Int): Int = percent.takeIf { it in CHOICES } ?: DEFAULT
}
