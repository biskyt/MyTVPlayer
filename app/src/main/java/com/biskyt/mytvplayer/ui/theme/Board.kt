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
 * sizes are used as they are, so that the screens match them exactly.
 */
private const val BOARD = 1280f / 960f

/** [this] board pixels. */
val Int.bd: Dp get() = (this * BOARD).dp

/** A board font size of [this] pixels. */
val Int.bsp: TextUnit get() = (this * BOARD).sp

/** Text as a board writes it: [size] px, [weight], and an optional CSS line height (a multiple of the size). */
internal fun boardText(size: Int, weight: Int, lineHeight: Float? = null, scale: Float = 1f) = TextStyle(
    fontFamily = Nunito,
    fontSize = (size * BOARD * scale).sp,
    fontWeight = FontWeight(weight),
    color = Palette.text,
    lineHeight = lineHeight?.let { (size * it * BOARD * scale).sp } ?: TextUnit.Unspecified
)

/**
 * A board's text style, in the text colour (give another colour on the Text), and a step larger when Settings › large
 * text is on: the boards' sizes are for normal text. Both are made once.
 */
class BoardStyle(size: Int, weight: Int, lineHeight: Float? = null) {
    private val normal = boardText(size, weight, lineHeight)
    private val large = boardText(size, weight, lineHeight, LARGE_TEXT)

    val current: TextStyle @Composable get() = if (LocalLargeText.current) large else normal
}

/**
 * About the step that large text adds on the other screens (2 points on sizes near 16). Applied to the sizes, not
 * through the density's font scale: Android scales large text non-linearly, so the boards' titles would barely change.
 */
private const val LARGE_TEXT = 1.12f
