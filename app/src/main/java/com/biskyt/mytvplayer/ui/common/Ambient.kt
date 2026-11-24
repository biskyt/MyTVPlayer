package com.biskyt.mytvplayer.ui.common

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.LocalContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.biskyt.mytvplayer.core.ui.BackdropLevel
import com.biskyt.mytvplayer.ui.theme.LocalAccent
import com.biskyt.mytvplayer.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// The browsing screens' background (the owner, 2026-11-24), as MyTVLauncher's Home: it follows the card with focus, so
// that the see-through logo tiles have colour behind them.

/** What the background follows: the card with focus, by its picture, its logo, or neither. */
sealed interface AmbientSource {
    data class Picture(val uri: String) : AmbientSource

    /** A TV channel's logo: glows in its colours, as a logo stretched across the screen would not look right. */
    data class Logo(val uri: String) : AmbientSource

    /** A card with no picture: the accent colour's glow. */
    data object Plain : AmbientSource

    companion object {
        /** A poster's or a picture's card: [uri]'s picture, or the accent's glow without one. */
        fun picture(uri: String?): AmbientSource = uri?.let(::Picture) ?: Plain

        /** A channel's card: its logo's glows, or the accent's without one. */
        fun logo(uri: String?): AmbientSource = uri?.let(::Logo) ?: Plain
    }
}

/** The card with focus on a browsing screen, for the background. */
class Ambience {
    var source by mutableStateOf<AmbientSource?>(null)
}

/** The app's [Ambience]; null where cards do not change the background (tests of a screen alone). */
val LocalAmbience = staticCompositionLocalOf<Ambience?> { null }

/**
 * Tells the background which card has focus: when the card takes focus, and when its
 * [source] changes while it has focus (a card shown from saved data at start-up gets its picture later).
 */
fun Modifier.ambientSource(ambience: Ambience?, source: AmbientSource): Modifier =
    if (ambience == null) this else this then AmbientSourceElement(ambience, source)

private data class AmbientSourceElement(val ambience: Ambience, val source: AmbientSource) : ModifierNodeElement<AmbientSourceNode>() {
    override fun create() = AmbientSourceNode(ambience, source)

    override fun update(node: AmbientSourceNode) {
        node.ambience = ambience
        node.source = source
        if (node.focused) ambience.source = source
    }
}

private class AmbientSourceNode(var ambience: Ambience, var source: AmbientSource) :
    Modifier.Node(),
    FocusEventModifierNode {
    var focused = false

    override fun onFocusEvent(focusState: FocusState) {
        focused = focusState.hasFocus
        if (focused) ambience.source = source
    }

    // A row item reused or removed while it had focus gets no focus event: it must not count as focused after.
    override fun onDetach() {
        focused = false
    }

    override fun onReset() {
        focused = false
    }
}

/**
 * The browsing screens' background (the owner, 2026-11-24, as MyTVLauncher's Home): for a card with a picture, the
 * picture itself, decoded tiny and stretched (a cheap blur, as Android 9 to 11 have no blur effect) under a dark
 * overlay. For a channel's logo or a card with no picture, soft glows in its colours bleed in from the corners of the
 * dark background. It changes only once the focus rests on a card ([SETTLE_MS]), so it does no work while the remote
 * moves along a row, and fades over [FADE_MS]. Only this composable redraws: the rows do not. [level] is the setting
 * (see BackdropLevel). It stays composed while another screen is on top ([shown] false), so that back on a browsing
 * screen the background is there at once, not drawn again from the start.
 */
@Composable
fun AmbientBackground(ambience: Ambience, level: Int, shown: Boolean) {
    val context = LocalContext.current
    var settled by remember { mutableStateOf<AmbientSource?>(null) }
    val source = ambience.source
    LaunchedEffect(source) {
        delay(SETTLE_MS)
        settled = source
    }
    // The backdrop for a picture: null for the glows, and while a new one loads the one before stays.
    var backdrop by remember { mutableStateOf<ImageBitmap?>(null) }
    // What the glows show: a logo or no picture, or (the accent colour) a picture that did not load.
    var light by remember { mutableStateOf<AmbientSource?>(null) }
    val on = level > 0
    LaunchedEffect(settled, on) {
        val now = settled
        // Off: no picture is loaded at all.
        if (now is AmbientSource.Picture && on) {
            val picture = withContext(Dispatchers.IO) { backdrop(context, now.uri) }
            backdrop = picture
            if (picture == null) light = AmbientSource.Plain
        } else {
            backdrop = null
            light = now
        }
    }
    AmbientLight(light, visible = backdrop == null && on && shown, scale = BackdropLevel.glowScale(level))
    Crossfade(backdrop, animationSpec = tween(FADE_MS), label = "backdrop") { picture ->
        // With the background off, or another screen on top, no backdrop shows either.
        if (picture != null && on && shown) {
            Box(Modifier.fillMaxSize()) {
                Image(picture, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                // The setting's share of the picture shows through the dark background (0: none).
                Box(Modifier.fillMaxSize().background(Palette.background.copy(alpha = BackdropLevel.overlayAlpha(level))))
            }
        }
    }
}

/**
 * The glows, in [source]'s colours (the accent's where it has none), as strong as the setting asks ([scale]: 1 at
 * MyTVLauncher 1.1.0's level). Not drawn, and their colours not worked out, while a backdrop covers them, with the
 * background set to off, or with another screen on top.
 */
@Composable
private fun AmbientLight(source: AmbientSource?, visible: Boolean, scale: Float) {
    val context = LocalContext.current
    val accent = LocalAccent.current.color
    val tones by produceState<List<Color?>?>(null, source, visible) {
        if (visible) value = source?.let { s -> withContext(Dispatchers.Default) { tones(context, s) } }
    }
    val a by animateColorAsState(tones?.getOrNull(0) ?: accent, tween(FADE_MS), label = "glow1")
    val b by animateColorAsState(tones?.getOrNull(1) ?: accent, tween(FADE_MS), label = "glow2")
    val c by animateColorAsState(tones?.getOrNull(2) ?: accent, tween(FADE_MS), label = "glow3")
    if (!visible) return
    Spacer(
        Modifier.fillMaxSize().drawWithCache {
            // Made again only when the colours or the size change.
            val glows = listOf(
                glow(a, Offset(size.width * 0.12f, -size.height * 0.1f), size.width * 0.62f, 0.30f * scale),
                glow(b, Offset(size.width * 0.95f, size.height * 0.15f), size.width * 0.5f, 0.24f * scale),
                glow(c, Offset(size.width * 0.45f, size.height * 1.15f), size.width * 0.7f, 0.20f * scale)
            )
            onDrawBehind { glows.forEach { (brush, center, radius) -> drawCircle(brush, radius, center) } }
        }
    )
}

/** One soft glow: [color] at [strength] at [center], that fades to nothing at [radius]. */
private fun glow(color: Color, center: Offset, radius: Float, strength: Float) = Triple(
    Brush.radialGradient(
        0f to color.copy(alpha = strength),
        0.45f to color.copy(alpha = strength * 0.45f),
        1f to Color.Transparent,
        center = center,
        radius = radius
    ),
    center,
    radius
)

/** The picture at [uri], decoded about [px] wide, from Coil's caches (the card loaded it already). */
private suspend fun tiny(context: Context, uri: String, px: Int): Bitmap? {
    val request = ImageRequest.Builder(context).data(uri).size(px, px).allowHardware(false).build()
    return SingletonImageLoader.get(context).execute(request).image?.toBitmap()
}

/**
 * The backdrop for the picture at [uri]: decoded [BACKDROP_PX] wide, blurred, then scaled up [BACKDROP_UP] times. The
 * blur and the steps up make it soft, where one stretch of a tiny picture to the whole screen shows a grid of blocks.
 */
private suspend fun backdrop(context: Context, uri: String): ImageBitmap? {
    val small = tiny(context, uri, BACKDROP_PX) ?: return null
    val blurred = boxBlur(small.copy(Bitmap.Config.ARGB_8888, true))
    return Bitmap.createScaledBitmap(blurred, blurred.width * BACKDROP_UP, blurred.height * BACKDROP_UP, true).asImageBitmap()
}

/** Two passes of a 3 by 3 box blur on a small [bitmap] (some thousand pixels), in place. */
private fun boxBlur(bitmap: Bitmap): Bitmap {
    val w = bitmap.width
    val h = bitmap.height
    val pixels = IntArray(w * h)
    repeat(BLUR_PASSES) {
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var r = 0
                var g = 0
                var b = 0
                var n = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val p = pixels[(y + dy).coerceIn(0, h - 1) * w + (x + dx).coerceIn(0, w - 1)]
                        r += p shr 16 and 0xFF
                        g += p shr 8 and 0xFF
                        b += p and 0xFF
                        n++
                    }
                }
                out[y * w + x] = (0xFF shl 24) or (r / n shl 16) or (g / n shl 8) or (b / n)
            }
        }
        bitmap.setPixels(out, 0, w, 0, 0, w, h)
    }
    return bitmap
}

/** The glows' colours for [source]: from its logo; null for the accent's. */
private suspend fun tones(context: Context, source: AmbientSource): List<Color?>? = when (source) {
    is AmbientSource.Logo -> logoTones.get(source.uri) ?: tiny(context, source.uri, TONE_PX)?.let(::tonesOf)?.also {
        logoTones.put(source.uri, it)
    }
    is AmbientSource.Picture, AmbientSource.Plain -> null
}

/**
 * Three colours of [bitmap]: its top left, top right and lower half, each brightened, so that a dark picture still
 * gives a glow. Transparent pixels (a logo's background) do not count; a part with none gives null (the accent).
 */
private fun tonesOf(bitmap: Bitmap): List<Color?> {
    // (A software bitmap: see tiny.)
    val small = Bitmap.createScaledBitmap(bitmap, TONE_PX, TONE_PX, true)
    val h = TONE_PX / 2
    return listOf(average(small, 0, 0, h, h), average(small, h, 0, TONE_PX, h), average(small, 0, h, TONE_PX, TONE_PX))
}

private fun average(b: Bitmap, left: Int, top: Int, right: Int, bottom: Int): Color? {
    var r = 0f
    var g = 0f
    var bl = 0f
    var n = 0
    for (y in top until bottom) {
        for (x in left until right) {
            val p = b.getPixel(x, y)
            if (p ushr 24 < OPAQUE) continue
            r += (p shr 16 and 0xFF) / 255f
            g += (p shr 8 and 0xFF) / 255f
            bl += (p and 0xFF) / 255f
            n++
        }
    }
    if (n == 0) return null
    val max = maxOf(r, g, bl) / n
    val lift = if (max > 0f) (BRIGHTEST / max).coerceAtMost(MAX_LIFT) else 1f
    return Color((r / n * lift).coerceAtMost(1f), (g / n * lift).coerceAtMost(1f), (bl / n * lift).coerceAtMost(1f))
}

private val logoTones = LruCache<String, List<Color?>>(200)

private const val SETTLE_MS = 250L
private const val FADE_MS = 600
private const val TONE_PX = 12
private const val OPAQUE = 128
private const val BRIGHTEST = 0.85f
private const val MAX_LIFT = 2.5f

/** The backdrop is decoded this small, blurred and scaled up: a soft picture at almost no cost. */
private const val BACKDROP_PX = 48
private const val BACKDROP_UP = 8
private const val BLUR_PASSES = 2
