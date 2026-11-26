package com.biskyt.mytvplayer.testutil

import androidx.activity.ComponentDialog
import androidx.compose.ui.test.junit4.ComposeTestRule
import org.robolectric.shadows.ShadowDialog

/** The remote's Back, as the panel shown last (a window of its own) gets it. */
fun ComposeTestRule.backOnPanel() = runOnUiThread {
    (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressedDispatcher.onBackPressed()
}

/** Waits until the focused text has [text] in it: back from a panel, the option that opened it has the focus. */
fun ComposeTestRule.waitForFocus(text: String) = waitAdvancingClock(stepMs = 50) { focusedText().contains(text) }
