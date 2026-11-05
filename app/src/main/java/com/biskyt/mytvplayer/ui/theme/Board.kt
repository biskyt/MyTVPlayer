package com.biskyt.mytvplayer.ui.theme

import androidx.compose.ui.graphics.Color
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
fun boardText(size: Int, weight: Int, color: Color = Palette.text, lineHeight: Float? = null) = TextStyle(
    fontFamily = Nunito,
    fontSize = size.bsp,
    fontWeight = FontWeight(weight),
    color = color,
    lineHeight = lineHeight?.let { (size * it * BOARD).sp } ?: TextUnit.Unspecified
)
