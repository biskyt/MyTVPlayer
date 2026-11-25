package com.biskyt.mytvplayer.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Density

/**
 * What the app runs on (phone spike, 2026-11-25): a TV (the screens as they are, on the 1280-unit canvas), a phone, or
 * a large touch screen such as an unfolded foldable (its smallest width is 600 dp or more). Phones and unfolded screens
 * use their real size; a screen with no phone layout yet is drawn as on a TV inside [TvCanvas].
 */
enum class FormFactor {
    TV,
    PHONE,
    UNFOLDED;

    val isTv: Boolean get() = this == TV
}

val LocalFormFactor = staticCompositionLocalOf { FormFactor.TV }

/** [content] as on a TV: on the 1280-unit canvas, scaled to the window's width (see MyTvTheme). */
@Composable
fun TvCanvas(content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val widthPx = LocalWindowInfo.current.containerSize.width
    val scaled = remember(density, widthPx) {
        if (widthPx > 0) Density(widthPx / DESIGN_WIDTH, density.fontScale) else density
    }
    CompositionLocalProvider(LocalDensity provides scaled, LocalFormFactor provides FormFactor.TV, content = content)
}
