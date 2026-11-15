package com.biskyt.mytvplayer.ui.common

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.ui.graphics.Color
import coil3.size.Size
import coil3.transform.Transformation
import com.biskyt.mytvplayer.core.ui.LogoLook

/**
 * Notes whether a TV channel's logo is dark (see [LogoLook], from MyTVLauncher; the owner, 2026-11-15): black text on a
 * transparent background, which the dark tile behind logos would hide. The logo then gets a light tile ([LIGHT_LOGO_TILE]).
 * The picture itself is left as it is. Coil runs this once for each logo it decodes, on its own thread, at the logo's
 * size on screen, and keeps the picture in its memory cache.
 */
internal class LogoDark(private val url: String) : Transformation() {
    override val cacheKey = "logo-dark-1"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        when {
            // Known already (decoded again from the disk cache, say): not looked at again.
            dark.get(url) != null -> Unit
            // A picture with no transparency is never dark (see LogoLook): not looked at.
            input.hasAlpha() -> look(url, input)
            else -> dark.put(url, false)
        }
        return input
    }

    companion object {
        /**
         * Whether each logo is dark, by its address. Coil's memory cache keeps the picture, not this, so it is kept here:
         * a few hundred booleans at most. A logo no longer here is looked at again (see ImageWithInitials).
         */
        private val dark = LruCache<String, Boolean>(LOGOS_KEPT)

        /** Whether the logo at [url] is dark, or null when it is not known (any more). */
        fun known(url: String): Boolean? = dark.get(url)

        /** Looks at [bitmap], the logo at [url], and notes whether it is dark. Off the main thread. */
        fun look(url: String, bitmap: Bitmap): Boolean {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return (LogoLook.of(pixels, bitmap.width, bitmap.height)?.dark ?: false).also { dark.put(url, it) }
        }
    }
}

private const val LOGOS_KEPT = 512

/** The tile behind a dark logo: light enough for black text, softer than white (MyTVLauncher's, 15% darker than first). */
internal val LIGHT_LOGO_TILE = Color(0xFFC4C5C8)
