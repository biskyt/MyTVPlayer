package com.biskyt.mytvplayer.ui.vod

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.AppText
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.ui.theme.BOLD
import com.biskyt.mytvplayer.ui.theme.BoardStyle
import com.biskyt.mytvplayer.ui.theme.Palette
import com.biskyt.mytvplayer.ui.theme.bd
import java.text.NumberFormat
import kotlin.math.roundToInt

// Review scores and the sites' logos, as MyPlexPlayer draws them (its Scores.kt). MyTVPlayer has one score: TMDB's.

/** How tall the sources' logos are drawn: about a line of the details text, the largest place they show. */
private val LOGO_HEIGHT = 20.dp

/**
 * Side by side, [spacing] apart, centred on each line, carried on to the next line when the width runs out: what a
 * FlowRow does, without what that brings into the app.
 */
@Composable
fun WrapRow(spacing: Dp, content: @Composable () -> Unit) = Layout(content) { measurables, constraints ->
    val gap = spacing.roundToPx()
    val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
    val lines = mutableListOf<MutableList<Placeable>>()
    var x = 0
    placeables.forEach { p ->
        if (lines.isEmpty() || x + gap + p.width > constraints.maxWidth) {
            lines += mutableListOf(p)
            x = p.width
        } else {
            lines.last() += p
            x += gap + p.width
        }
    }
    val heights = lines.map { line -> line.maxOf { it.height } }
    val width = lines.maxOfOrNull { line -> line.sumOf { it.width } + gap * (line.size - 1) } ?: 0
    layout(width.coerceIn(constraints.minWidth, constraints.maxWidth), heights.sum() + gap / 2 * (lines.size - 1).coerceAtLeast(0)) {
        var y = 0
        lines.forEachIndexed { i, line ->
            var lineX = 0
            line.forEach { p ->
                p.placeRelative(lineX, y + (heights[i] - p.height) / 2)
                lineX += p.width + gap
            }
            y += heights[i] + gap / 2
        }
    }
}

private const val PERCENT = 100.0

/** TMDB's users' score (0 to 10) as the boards draw a score: TMDB's logo, then the score out of 100 ("76%"). */
@Composable
fun TmdbScore(score: Double) = BoardScore(R.drawable.logo_tmdb, stringResource(R.string.vod_score_tmdb), percentOf(score))

/**
 * One review score as the boards draw it: its source's [logo] (named [name] for screen readers), 20 high, then the score
 * as that source writes it ([value]: "76%"), 14 bold.
 */
@Composable
fun BoardScore(@DrawableRes logo: Int, name: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.bd), verticalAlignment = Alignment.CenterVertically) {
        // At the board's 20, as MyPlexPlayer draws it (not SourceLogo's default, which is in plain dp).
        SourceLogo(logo, name, height = 20.bd)
        Text(value, style = ScoreText.current, color = Palette.textMuted, maxLines = 1)
    }
}

private val ScoreText = BoardStyle(14, BOLD)

/**
 * A source's logo, [height] high (as tall as it's drawn everywhere, unless given), named [name] for screen readers.
 * Drawn by Android's own vector drawables: Compose's reader of them would add more to the app than the logos themselves.
 */
@Composable
fun SourceLogo(@DrawableRes logo: Int, name: String, modifier: Modifier = Modifier, height: Dp = LOGO_HEIGHT) {
    val context = LocalContext.current
    val drawable = remember(logo) { checkNotNull(context.getDrawable(logo)) }
    val width = height * drawable.intrinsicWidth.toFloat() / drawable.intrinsicHeight
    Box(
        modifier.size(width, height).semantics { contentDescription = name }.drawBehind {
            drawable.setBounds(0, 0, size.width.roundToInt(), size.height.roundToInt())
            drawIntoCanvas { drawable.draw(it.nativeCanvas) }
        }
    )
}

/** A score out of 10 as a percentage, in the app's language: "76%". */
private fun percentOf(score: Double): String = NumberFormat.getPercentInstance(AppText.locale).format(score * 10 / PERCENT)

/** A link's site's logo, by its name ("IMDb", "TMDB"); null for one without. */
@DrawableRes
fun linkLogo(name: String): Int? = when (name) {
    "IMDb" -> R.drawable.logo_imdb
    "TMDB" -> R.drawable.logo_tmdb
    else -> null
}
