package com.biskyt.mytvplayer.ui.theme

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Density
import com.biskyt.mytvplayer.data.LayoutMode
import com.biskyt.mytvplayer.ui.common.hasTouchScreen

/**
 * What the app runs on (the owner, 2026-11-25): a TV (the screens as they are, on the 1280-unit canvas), a phone, or
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

/**
 * Whether the Automatic layout ([LayoutMode.AUTO]) finds a TV: one has the TV feature, runs in the TV's UI mode, or has
 * no touch screen.
 */
fun Context.looksLikeTv(): Boolean = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
    (getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
    !hasTouchScreen()

/** How the Automatic layout finds a TV. Tests set it (TestApp: a TV), as Robolectric reports a touch screen. */
@VisibleForTesting
internal var autoFindsTv: (Context) -> Boolean = { it.looksLikeTv() }

/** Whether the Automatic layout finds a TV here: Settings then offers no touch layout (a remote cannot use it). */
fun Context.findsTv(): Boolean = autoFindsTv(this)

/**
 * The form factor for [layout]: a TV, else (a touch screen, or the phone's layout chosen) a phone, or [FormFactor.UNFOLDED]
 * when the screen's smallest width is [UNFOLDED_DP] or more.
 */
fun formFactor(context: Context, layout: LayoutMode, smallestWidthDp: Int): FormFactor = when {
    layout == LayoutMode.TV || layout == LayoutMode.AUTO && autoFindsTv(context) -> FormFactor.TV
    smallestWidthDp >= UNFOLDED_DP -> FormFactor.UNFOLDED
    else -> FormFactor.PHONE
}

/** A touch screen at least this wide both ways (an unfolded foldable, a tablet) gets the larger touch layouts. */
private const val UNFOLDED_DP = 600

/** [content] as on a TV: on the 1280-unit canvas, scaled to the window's width (see MyTvTheme). */
@Composable
fun TvCanvas(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalDensity provides rememberCanvasDensity(), LocalFormFactor provides FormFactor.TV, content = content)
}

/**
 * The density that makes the 1280-unit canvas span the window's width (see MyTvTheme), or the screen's own density when
 * [canvas] is false or the window has no width yet.
 */
@Composable
internal fun rememberCanvasDensity(canvas: Boolean = true): Density {
    val density = LocalDensity.current
    val widthPx = LocalWindowInfo.current.containerSize.width
    return remember(density, widthPx, canvas) {
        if (canvas && widthPx > 0) Density(widthPx / DESIGN_WIDTH, density.fontScale) else density
    }
}
