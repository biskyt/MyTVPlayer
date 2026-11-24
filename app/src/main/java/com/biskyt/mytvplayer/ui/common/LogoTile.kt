package com.biskyt.mytvplayer.ui.common

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.ScaleFactor
import coil3.size.Size as CoilSize
import coil3.transform.Transformation
import com.biskyt.mytvplayer.core.ui.LogoLook
import kotlin.math.min
import kotlin.math.sqrt

// A TV channel's logo on its tile (the owner, 2026-11-24, from MyTVLauncher): logos were of many sizes, and a light or a
// dark tile by the logo's colours looked poor. Every tile is dark and a little see-through ([LOGO_TILE]). Coil runs the
// work once for each logo, off the main thread, and keeps the result.

/** Cuts off a logo's transparent border, and makes a dark logo's dark parts white (see [LogoLook]). */
internal object LogoTrim : Transformation() {
    override val cacheKey = "logo-trim-1"

    override suspend fun transform(input: Bitmap, size: CoilSize): Bitmap {
        // A picture with no transparency has no border to cut, and its own background: left as it is.
        if (!input.hasAlpha()) return input
        val pixels = IntArray(input.width * input.height)
        input.getPixels(pixels, 0, input.width, 0, 0, input.width, input.height)
        val look = LogoLook.of(pixels, input.width, input.height) ?: return input
        if (!look.dark) {
            val whole = look.width == input.width && look.height == input.height
            return if (whole) input else Bitmap.createBitmap(input, look.left, look.top, look.width, look.height)
        }
        LogoLook.lighten(pixels)
        val out = Bitmap.createBitmap(look.width, look.height, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, look.top * input.width + look.left, input.width, 0, 0, look.width, look.height)
        return out
    }
}

/**
 * Gives every logo about the same area ([AREA] of the tile's), so that a wide logo and a square one look as large as
 * each other, and keeps it inside a margin ([MARGIN] of the tile's width and height on each side).
 */
internal object LogoFit : ContentScale {
    override fun computeScaleFactor(srcSize: Size, dstSize: Size): ScaleFactor {
        if (srcSize.width <= 0f || srcSize.height <= 0f) return ScaleFactor(1f, 1f)
        val inside = min(dstSize.width * (1 - 2 * MARGIN) / srcSize.width, dstSize.height * (1 - 2 * MARGIN) / srcSize.height)
        val sameArea = sqrt(AREA * dstSize.width * dstSize.height / (srcSize.width * srcSize.height))
        val scale = min(inside, sameArea)
        return ScaleFactor(scale, scale)
    }
}

private const val MARGIN = 0.1f
private const val AREA = 0.4f

/** The tile behind every logo: black, a little see-through, so it is darker than what is behind it and tinted by it. */
internal val LOGO_TILE = Color.Black.copy(alpha = 0.4f)
