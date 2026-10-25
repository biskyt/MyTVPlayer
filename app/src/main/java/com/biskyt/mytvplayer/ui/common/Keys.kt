package com.biskyt.mytvplayer.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

private val digitKeys = listOf(Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine)
private val numPadKeys =
    listOf(Key.NumPad0, Key.NumPad1, Key.NumPad2, Key.NumPad3, Key.NumPad4, Key.NumPad5, Key.NumPad6, Key.NumPad7, Key.NumPad8, Key.NumPad9)

/** The number on a remote's digit key (or number pad), or null for any other key. */
fun Key.digit(): Int? = digitKeys.indexOf(this).takeIf { it >= 0 } ?: numPadKeys.indexOf(this).takeIf { it >= 0 }

/** Remote buttons that open a context menu; remotes label them Info, Options, Menu or "…". */
private val optionsKeys = setOf(Key.Info, Key.Menu, Key.TvContentsMenu, Key.TvMediaContextMenu)

private val selectKeys = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)

fun Key.isOptions(): Boolean = this in optionsKeys

/** OK, or a keyboard's Enter. */
fun Key.isSelect(): Boolean = this in selectKeys

/** The remote's Guide/EPG button. */
fun Key.isGuide(): Boolean = this == Key.Guide

/**
 * Opens [onOptions] from the remote's Info/Options/Menu button, as an alternative to holding OK.
 */
fun Modifier.onOptionsKey(onOptions: () -> Unit): Modifier = onPreviewKeyEvent { e -> handlePress(e, e.key.isOptions(), onOptions) }

/**
 * Runs [onGuide] for the Guide button when [enabled] says so, once per press. After the screen's own handling (a
 * screen that uses the button itself, like the live list, gets it first).
 */
fun Modifier.onGuideKey(enabled: () -> Boolean, onGuide: () -> Unit): Modifier =
    onKeyEvent { e -> handlePress(e, e.key.isGuide() && enabled(), onGuide) }

/** Runs [onMenu] for the remote's Menu button, once per press. */
fun Modifier.onMenuKey(onMenu: () -> Unit): Modifier = onKeyEvent { e -> handlePress(e, e.key == Key.Menu, onMenu) }

/** Consumes the whole press (down, repeats, up) when [matches], acting once on the first key-down. */
private fun handlePress(e: KeyEvent, matches: Boolean, action: () -> Unit): Boolean {
    if (!matches) return false
    if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) action()
    return true
}

/**
 * Ignores OK presses that began before this content appeared. Holding OK opens a menu while the button is still
 * down; without this, the held key's repeats and its release land on the menu's first item and select it.
 */
@Composable
fun Modifier.ignoreHeldSelect(): Modifier {
    val armed = remember { booleanArrayOf(false) }
    return onPreviewKeyEvent { e ->
        when {
            e.key !in selectKeys || armed[0] -> false
            e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0 -> {
                armed[0] = true
                false
            }
            else -> true
        }
    }
}
