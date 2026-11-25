package com.biskyt.mytvplayer.ui.common

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.biskyt.mytvplayer.ui.phone.PhoneTouch
import com.biskyt.mytvplayer.ui.theme.LocalFormFactor

/** Whether this device has a touch screen (TVs don't): set once at the root (see MyTvTheme). */
val LocalTouch = staticCompositionLocalOf { false }

fun Context.hasTouchScreen(): Boolean = packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)

/**
 * Taps and long presses on a touch screen (a phone or tablet): the TV surfaces the app is built from only answer the
 * remote's OK button. A tap also moves focus here, so what a TV shows for the focused item (e.g. a group's
 * categories) follows the finger too. Nothing at all on a TV, where every list row has one.
 */
@Composable
fun Modifier.onTap(onLongClick: (() -> Unit)? = null, onClick: () -> Unit): Modifier {
    if (!LocalTouch.current) return this
    val click by rememberUpdatedState(onClick)
    val longClick by rememberUpdatedState(onLongClick)
    val focus = remember { FocusRequester() }
    val hasLongClick = onLongClick != null
    return focusRequester(focus).pointerInput(hasLongClick) {
        detectTapGestures(
            // Only where there is something to open: else a slow tap would count as a long press and do nothing.
            onLongPress = if (hasLongClick) ({ longClick?.invoke() }) else null,
            onTap = {
                runCatching { focus.requestFocus() }
                click()
            }
        )
    }
}

/** Taps here run [action] (no focus moves); also swallows taps that would otherwise reach what's underneath. */
@Composable
fun Modifier.onPlainTap(action: () -> Unit): Modifier {
    val current by rememberUpdatedState(action)
    return pointerInput(Unit) { detectTapGestures { current() } }
}

/** Runs [action] whenever a finger goes down here, before (and whatever) the content under it does with the touch. */
@Composable
fun Modifier.onAnyTouch(action: () -> Unit): Modifier {
    val current by rememberUpdatedState(action)
    return pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                if (awaitPointerEvent(PointerEventPass.Initial).type == PointerEventType.Press) current()
            }
        }
    }
}

/** On a touch screen, which has no ◀ ▶: a pair of buttons stepping back (-1) and forward (1). */
@Composable
fun TouchStepButtons(back: String, forward: String, onStep: (Int) -> Unit) {
    if (!LocalTouch.current) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PillButton("◀ $back", { onStep(-1) })
        PillButton("$forward ▶", { onStep(1) })
    }
}

/**
 * At least [min] high on a phone or an unfolded foldable, so that a finger can hit it (the owner, 2026-11-25). On a TV,
 * as it is.
 */
@Composable
internal fun Modifier.touchHeight(min: Dp = PhoneTouch): Modifier = if (LocalFormFactor.current.isTv) this else heightIn(min = min)
