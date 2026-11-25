package com.biskyt.mytvplayer.ui.phone

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.biskyt.mytvplayer.ui.common.PanelWindow
import com.biskyt.mytvplayer.ui.common.ignoreHeldButtons
import com.biskyt.mytvplayer.ui.common.onPlainTap
import com.biskyt.mytvplayer.ui.theme.Palette

/**
 * A panel on a phone or an unfolded foldable (the owner, 2026-11-25): a sheet that rises from the bottom, with
 * rounded top corners and a grab handle, over the dimmed screen. It is 85 % of the screen's height at most, and
 * [content] decides if it scrolls. A tap above the sheet or Back closes it ([onDismiss]). [dim] false dims the screen
 * less (a panel that leaves the page in view on a TV).
 */
@Composable
fun PhoneSheet(onDismiss: () -> Unit, dim: Boolean = true, content: @Composable () -> Unit) {
    PanelWindow(onDismiss, hideBars = true) {
        // The sheet rises from the bottom as it opens.
        val rise = remember { Animatable(1f) }
        LaunchedEffect(Unit) { rise.animateTo(0f, tween(RISE_MS)) }
        BoxWithConstraints(
            Modifier.fillMaxSize().ignoreHeldButtons()
                .background(Color.Black.copy(alpha = if (dim) DIM else DIM_LIGHT))
                .onPlainTap(onDismiss)
                // Above the keyboard, when a box in the sheet is typed in.
                .imePadding(),
            contentAlignment = Alignment.BottomCenter
        ) {
            Column(
                Modifier.widthIn(max = MAX_WIDTH).fillMaxWidth()
                    .heightIn(max = maxHeight * MAX_HEIGHT)
                    .graphicsLayer { translationY = size.height * rise.value }
                    .clip(SheetShape)
                    .background(Palette.surface)
                    .onPlainTap {} // a tap on the sheet itself does not close it
                    .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(Modifier.padding(bottom = 10.dp).size(40.dp, 5.dp).clip(RoundedCornerShape(3.dp)).background(Palette.track))
                Box(Modifier.fillMaxWidth()) { content() }
            }
        }
    }
}

private val SheetShape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
private val MAX_WIDTH = 640.dp
private const val MAX_HEIGHT = 0.85f
private const val DIM = 0.6f
private const val DIM_LIGHT = 0.35f
private const val RISE_MS = 220
