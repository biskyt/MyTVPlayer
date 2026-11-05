package com.biskyt.mytvplayer.ui.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowRgb565
import com.biskyt.mytvplayer.ui.common.AppIcons
import com.biskyt.mytvplayer.ui.common.FocusStroke
import com.biskyt.mytvplayer.ui.common.favouriteLabel
import com.biskyt.mytvplayer.ui.common.onTap
import com.biskyt.mytvplayer.ui.theme.BOLD
import com.biskyt.mytvplayer.ui.theme.BoardStyle
import com.biskyt.mytvplayer.ui.theme.EXTRA_BOLD
import com.biskyt.mytvplayer.ui.theme.LocalAccent
import com.biskyt.mytvplayer.ui.theme.Palette
import com.biskyt.mytvplayer.ui.theme.bd

// The film and series pages, as the design boards (2026-11-05) draw them. Sizes are the boards' (see Board.kt).

/**
 * A film or series page's button: [background] (#22262D), or filled with the accent colour while it has focus, with no
 * ring. [strong] is the main one's heavier text. [progress] (0 to 1) runs along its bottom edge.
 */
@Composable
fun DetailButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    strong: Boolean = false,
    icon: ImageVector? = null,
    /** What the icon says, for a screen reader (the heart: whether it is a favourite). */
    iconDescription: String? = null,
    progress: Float? = null,
    horizontal: Dp = 20.bd,
    vertical: Dp = 10.bd,
    style: TextStyle = if (strong) ButtonStrong.current else ButtonText.current,
    background: Color = Palette.surfaceHigh
) {
    val accent = LocalAccent.current
    PillSurface(onClick, modifier, background, Palette.text, accent.color, accent.onAccent, ring = false) {
        Row(
            Modifier.padding(horizontal = horizontal, vertical = vertical),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.bd)
        ) {
            // The surface's content colour: the text's, or the accent's while it has focus.
            icon?.let { Icon(it, contentDescription = iconDescription, tint = LocalContentColor.current, modifier = Modifier.size(16.bd)) }
            if (text.isNotEmpty()) Text(text, style = style, color = LocalContentColor.current, maxLines = 1)
        }
        if (progress != null) ButtonProgress(progress)
    }
}

/** A season's pill: the one shown filled with the accent colour. It takes focus with the app's usual ring. */
@Composable
fun SeasonPill(name: String, current: Boolean, onClick: () -> Unit) {
    val accent = LocalAccent.current
    val bg = if (current) accent.color else Palette.surfaceHigh
    val fg = if (current) accent.onAccent else Palette.text
    PillSurface(onClick, Modifier, bg, fg, bg, fg, ring = true) {
        Text(
            name,
            style = if (current) PillStrong.current else PillText.current,
            color = fg,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 14.bd, vertical = 6.bd)
        )
    }
}

/** A pill-shaped surface for the pages' buttons, with these colours, and the app's focus [ring] or none. */
@Composable
private fun PillSurface(
    onClick: () -> Unit,
    modifier: Modifier,
    background: Color,
    content: Color,
    focusedBackground: Color,
    focusedContent: Color,
    ring: Boolean,
    body: @Composable BoxScope.() -> Unit
) = Surface(
    onClick = onClick,
    modifier = modifier.onTap(onClick = onClick),
    shape = ClickableSurfaceDefaults.shape(Pill),
    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    colors = ClickableSurfaceDefaults.colors(
        containerColor = background,
        focusedContainerColor = focusedBackground,
        pressedContainerColor = focusedBackground,
        contentColor = content,
        focusedContentColor = focusedContent
    ),
    border = if (ring) ClickableSurfaceDefaults.border(focusedBorder = Border(FocusStroke)) else ClickableSurfaceDefaults.border()
) {
    // Centred: an icon-only button stretched to its neighbour's height keeps its icon in the middle.
    Box(contentAlignment = Alignment.Center) { body() }
}

/**
 * How far into the film: a 4 px bar along the bottom of its button, in the page's background at 45 %. It takes the
 * button's size and does not set it.
 */
@Composable
private fun BoxScope.ButtonProgress(fraction: Float) {
    // matchParentSize: measured after the text, so the bar never makes the button larger.
    Box(Modifier.matchParentSize(), contentAlignment = Alignment.BottomStart) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(4.bd).background(Palette.background.copy(alpha = 0.45f)))
    }
}

/** The backdrop, faded behind a gradient from the left, when there is one; nothing when there is none. */
@Composable
fun Backdrop(url: String?) {
    if (url == null) return
    // Faded behind a gradient, so a third of the screen's resolution is plenty (and a ninth of the memory).
    val context = LocalContext.current
    val request = remember(url) {
        ImageRequest.Builder(context).data(url).size(BACKDROP_WIDTH, BACKDROP_WIDTH * 9 / 16).allowRgb565(true).build()
    }
    AsyncImage(request, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().testTag("backdrop"), alpha = 0.35f)
    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Palette.background, Palette.background.copy(alpha = 0.6f)))))
}

private const val BACKDROP_WIDTH = 640
private val Pill = RoundedCornerShape(50)

/** The film's or the series' favourite: a heart, filled when it is one, with [text] beside it (none: just the heart). */
@Composable
fun FavouriteButton(favourite: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, text: String = "") = DetailButton(
    text,
    onClick,
    modifier,
    icon = if (favourite) AppIcons.Heart else AppIcons.HeartOutline,
    iconDescription = favouriteLabel(favourite),
    horizontal = if (text.isEmpty()) 12.bd else 20.bd
)

// The buttons' and pills' text (their colour comes from where they are).
private val ButtonText = BoardStyle(15, BOLD)
private val ButtonStrong = BoardStyle(15, EXTRA_BOLD)
private val PillText = BoardStyle(14, BOLD)
private val PillStrong = BoardStyle(14, EXTRA_BOLD)
