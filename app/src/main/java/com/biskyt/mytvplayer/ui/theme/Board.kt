package com.biskyt.mytvplayer.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Sizes copied from the design boards (the film and series pages, the top bar). A board is 960 x 540: a 1080p TV in
 * dp. The app lays out on a canvas 1280 wide (see MyTvTheme), so each board size is 4/3 of its value here. The boards'
 * sizes are used as they are, so that the screens match them exactly, except text (see [BoardStyle]).
 */
private const val BOARD = 1280f / 960f

/** [this] board pixels. */
val Int.bd: Dp get() = (this * BOARD).dp

/**
 * Text as a board writes it: [size] px, [weight], and an optional CSS line height (a multiple of the size), at
 * [factor] sp a pixel.
 */
private fun boardText(size: Int, weight: Int, lineHeight: Float?, factor: Float) = TextStyle(
    fontFamily = Nunito,
    fontSize = (size * factor).sp,
    fontWeight = FontWeight(weight),
    color = Palette.text,
    lineHeight = lineHeight?.let { (size * it * factor).sp } ?: TextUnit.Unspecified
)

/**
 * A board's text style, in the text colour (give another colour on the Text). Its size is the board's number as the
 * rest of the app sizes text (the theme's scale, not the boards' 4/3), and a step larger with Settings › large text:
 * the boards' text, scaled like their layout, was too large on a TV (the owner, 2026-11-05 and 2026-11-06). Both styles
 * are made once.
 */
class BoardStyle(size: Int, weight: Int, lineHeight: Float? = null) {
    private val normal = boardText(size, weight, lineHeight, TEXT_SCALE)
    private val large = boardText(size, weight, lineHeight, TEXT_SCALE * LARGE_TEXT)

    val current: TextStyle @Composable get() = if (LocalLargeText.current) large else normal
}

/**
 * About the step that large text adds on the other screens (2 points on sizes near 16). Applied to the sizes, not
 * through the density's font scale: Android scales large text non-linearly, so the boards' titles would barely change.
 */
private const val LARGE_TEXT = 1.12f
