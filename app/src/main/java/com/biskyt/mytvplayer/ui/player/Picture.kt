package com.biskyt.mytvplayer.ui.player

import androidx.annotation.OptIn
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.SubtitleView
import com.biskyt.mytvplayer.R
import kotlin.math.roundToInt

/** How the picture fills the screen. The fixed shapes fix channels whose picture is flagged with the wrong one. */
enum class PictureMode(@param:StringRes val label: Int, private val shape: Float?) {
    FIT(R.string.player_picture_fit, null),
    ZOOM(R.string.player_picture_zoom, null),
    STRETCH(R.string.player_picture_stretch, null),
    WIDE(R.string.player_picture_wide, 16f / 9),
    STANDARD(R.string.player_picture_standard, 4f / 3);

    val next: PictureMode get() = entries[(ordinal + 1) % entries.size]

    /**
     * The picture's size on a [width] × [height] screen, for a video of [videoAspect] (width over height, null while
     * unknown). Zoom may be larger than the screen (the overflow is cut off).
     */
    fun size(width: Int, height: Int, videoAspect: Float?): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return width.coerceAtLeast(0) to height.coerceAtLeast(0)
        val screen = width.toFloat() / height
        val aspect = shape ?: videoAspect?.takeIf { it > 0f && it.isFinite() } ?: screen
        return when {
            this == STRETCH -> width to height
            // Zoom: cover the screen; the rest: fit inside it.
            (aspect > screen) == (this == ZOOM) -> (height * aspect).roundToInt() to height
            else -> width to (width / aspect).roundToInt()
        }
    }
}

/**
 * Sizes this (the video) to the picture's size for [mode], centred on the screen; [onScreen] keeps it to the part
 * that's on the screen (for what's drawn over a zoomed picture, such as subtitles).
 */
fun Modifier.picture(mode: PictureMode, videoSize: Size?, onScreen: Boolean = false): Modifier = layout { measurable, constraints ->
    val width = constraints.maxWidth
    val height = constraints.maxHeight
    val aspect = videoSize?.takeIf { it.height > 0f }?.let { it.width / it.height }
    val (fullW, fullH) = mode.size(width, height, aspect)
    val w = if (onScreen) fullW.coerceAtMost(width) else fullW
    val h = if (onScreen) fullH.coerceAtMost(height) else fullH
    val placeable = measurable.measure(Constraints.fixed(w, h))
    layout(width, height) { placeable.place((width - w) / 2, (height - h) / 2) }
}

/**
 * The player's subtitles, drawn by Media3's own subtitle view (every format, placed as the stream says, in the TV's
 * caption style): the Compose video surface draws only the video.
 */
@OptIn(UnstableApi::class)
@Composable
fun Subtitles(player: Player, modifier: Modifier = Modifier) = key(player) {
    AndroidView(
        factory = { context ->
            val view = SubtitleView(context).apply {
                setUserDefaultStyle()
                setUserDefaultTextSize()
                setCues(player.currentCues.cues)
            }
            // Listening from the start, so no subtitle is missed while the screen settles.
            val listener = object : Player.Listener {
                override fun onCues(cueGroup: CueGroup) = view.setCues(cueGroup.cues)
            }
            player.addListener(listener)
            view.tag = listener
            view
        },
        modifier = modifier,
        onRelease = { player.removeListener(it.tag as Player.Listener) }
    )
}
