package com.biskyt.mytvplayer.ui

import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_UP
import android.view.KeyEvent.KEYCODE_DPAD_CENTER
import android.view.KeyEvent.KEYCODE_INFO
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performKeyPress
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.ui.common.SidePanel
import com.biskyt.mytvplayer.ui.common.TvCard
import com.biskyt.mytvplayer.ui.common.rememberFirstFocus
import com.biskyt.mytvplayer.ui.phone.PhoneSheet
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class)
class RemoteKeysUiTest {
    @get:Rule val compose = createUiComposeRule()

    private fun key(action: Int, code: Int, repeat: Int = 0) = KeyEvent(android.view.KeyEvent(0L, 0L, action, code, repeat))

    @Test
    fun `info button opens the options and a held OK doesn't pick from them`() = heldOkPicksNothing { close, content ->
        SidePanel(onDismiss = close) { content() }
    }

    /** The same in a phone's sheet (the owner, 2026-11-25), which a panel is on a touch layout. */
    @Test
    fun `a held OK doesn't pick from a phone's sheet either`() = heldOkPicksNothing { close, content ->
        PhoneSheet(onDismiss = close) { content() }
    }

    /** Opens options in [panel] with Info, then checks that the rest of a held OK picks nothing and a fresh one does. */
    private fun heldOkPicksNothing(panel: @Composable (close: () -> Unit, content: @Composable () -> Unit) -> Unit) {
        var options by mutableStateOf(false)
        var watched by mutableIntStateOf(0)
        compose.setContent {
            MyTvTheme {
                TvCard(onClick = {}, onLongClick = { options = true }, modifier = rememberFirstFocus()) { Text("Channel") }
                if (options) {
                    panel({ options = false }) {
                        TvCard(onClick = { watched++ }, modifier = rememberFirstFocus()) { Text("Watch") }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNode(hasText("Channel")).performKeyPress(key(ACTION_DOWN, KEYCODE_INFO))
        compose.onNode(hasText("Channel")).performKeyPress(key(ACTION_UP, KEYCODE_INFO))
        compose.waitForIdle()
        assertEquals(true, options)

        // The rest of a long OK press (repeats, then the release) arrives at the menu: nothing is picked.
        val watch = compose.onNode(hasText("Watch"))
        watch.performKeyPress(key(ACTION_DOWN, KEYCODE_DPAD_CENTER, repeat = 3))
        watch.performKeyPress(key(ACTION_UP, KEYCODE_DPAD_CENTER))
        compose.waitForIdle()
        assertEquals(0, watched)

        // A fresh press does pick.
        watch.performKeyPress(key(ACTION_DOWN, KEYCODE_DPAD_CENTER))
        watch.performKeyPress(key(ACTION_UP, KEYCODE_DPAD_CENTER))
        compose.waitForIdle()
        assertEquals(1, watched)
    }
}
