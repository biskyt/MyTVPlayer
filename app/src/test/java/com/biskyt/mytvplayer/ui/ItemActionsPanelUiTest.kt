package com.biskyt.mytvplayer.ui

import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_UP
import android.view.KeyEvent.KEYCODE_DPAD_DOWN
import android.view.KeyEvent.KEYCODE_DPAD_UP
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.biskyt.mytvplayer.testutil.Navigators
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.focusedText
import com.biskyt.mytvplayer.testutil.idleFrames
import com.biskyt.mytvplayer.testutil.sendKey
import com.biskyt.mytvplayer.testutil.waitForFocus
import com.biskyt.mytvplayer.ui.common.ItemAction
import com.biskyt.mytvplayer.ui.common.ItemActionsPanel
import com.biskyt.mytvplayer.ui.common.OptionPlace
import com.biskyt.mytvplayer.ui.common.optionPlace
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Which option of an options panel has the focus. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class ItemActionsPanelUiTest {
    @get:Rule val compose = createUiComposeRule<ComponentActivity>()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    @Test
    fun `an option read later, above the one used, does not take the focus from where the user is`() {
        val labels = mutableStateOf(listOf("Alpha", "Bravo", "Charlie", "Delta"))
        val navigator = navigators.new(Screen.Home)
        compose.setContent {
            MyTvTheme {
                CompositionLocalProvider(LocalNavigator provides navigator) {
                    // Back from the panel Bravo opened.
                    val used = remember { mutableStateOf<OptionPlace?>(optionPlace(listOf("Alpha", "Bravo", "Charlie", "Delta"), 1)) }
                    ItemActionsPanel("Title", labels.value.map { ItemAction.Run(it) {} }, {}, used = used)
                }
            }
        }
        compose.waitForFocus("Bravo")
        repeat(2) {
            compose.sendKey(KEYCODE_DPAD_DOWN, ACTION_DOWN)
            compose.sendKey(KEYCODE_DPAD_DOWN, ACTION_UP)
        }
        compose.waitForFocus("Delta")
        // As TMDB's options come in above: Bravo, the option used, moves down a row, but must not take the focus again.
        labels.value = listOf("Extra") + labels.value
        compose.idleFrames()
        // Still the option the user is on, not Bravo, and not the option now in Delta's old row.
        assertEquals("Delta", compose.focusedText())
    }

    @Test
    fun `a panel whose options come in after it opened focuses the first of them`() {
        val labels = mutableStateOf(emptyList<String>())
        val navigator = navigators.new(Screen.Home)
        compose.setContent {
            MyTvTheme {
                CompositionLocalProvider(LocalNavigator provides navigator) {
                    ItemActionsPanel("Title", labels.value.map { ItemAction.Run(it) {} }, {})
                }
            }
        }
        compose.idleFrames()
        labels.value = listOf("Alpha", "Bravo")
        compose.waitForFocus("Alpha")
    }

    @Test
    fun `back from a panel, the option used keeps the focus as options read later come in above it`() {
        val labels = mutableStateOf(listOf("Alpha", "Bravo", "Charlie"))
        val navigator = navigators.new(Screen.Home)
        compose.setContent {
            MyTvTheme {
                CompositionLocalProvider(LocalNavigator provides navigator) {
                    val used = remember { mutableStateOf<OptionPlace?>(optionPlace(listOf("Alpha", "Bravo", "Charlie"), 1)) }
                    ItemActionsPanel("Title", labels.value.map { ItemAction.Run(it) {} }, {}, used = used)
                }
            }
        }
        compose.waitForFocus("Bravo")
        labels.value = listOf("Extra", "More") + labels.value
        compose.idleFrames()
        assertEquals("Bravo", compose.focusedText())
    }

    @Test
    fun `an option whose label changes keeps the focus`() {
        val labels = mutableStateOf(listOf("Open", "Add to favourites"))
        val navigator = navigators.new(Screen.Home)
        compose.setContent {
            MyTvTheme {
                CompositionLocalProvider(LocalNavigator provides navigator) {
                    ItemActionsPanel("Title", labels.value.map { ItemAction.Run(it) {} }, {})
                }
            }
        }
        compose.waitForFocus("Open")
        compose.sendKey(KEYCODE_DPAD_DOWN, ACTION_DOWN)
        compose.sendKey(KEYCODE_DPAD_DOWN, ACTION_UP)
        compose.waitForFocus("Add to favourites")
        labels.value = listOf("Open", "Remove from favourites")
        compose.idleFrames()
        assertEquals("Remove from favourites", compose.focusedText())
        // Then an option read later just above it: the focus stays on it.
        labels.value = listOf("Open", "Trailer", "Remove from favourites")
        compose.idleFrames()
        assertEquals("Remove from favourites", compose.focusedText())
    }

    @Test
    fun `back from a panel, the option used takes the focus when it comes in late`() {
        val labels = mutableStateOf(listOf("Alpha", "Bravo"))
        show(labels, optionPlace(listOf("Alpha", "Bravo", "Charlie"), 2))
        // Until then the first option stands in.
        compose.waitForFocus("Alpha")
        labels.value = listOf("Alpha", "Bravo", "Charlie")
        compose.waitForFocus("Charlie")
    }

    @Test
    fun `while the option used has not loaded, the option the user moves to keeps the focus`() {
        val labels = mutableStateOf(listOf("Alpha", "Bravo"))
        show(labels, optionPlace(listOf("Alpha", "Bravo", "Charlie"), 2))
        compose.waitForFocus("Alpha")
        press(KEYCODE_DPAD_DOWN)
        press(KEYCODE_DPAD_UP)
        compose.waitForFocus("Alpha")
        labels.value = listOf("Alpha", "Bravo", "Charlie")
        compose.idleFrames()
        assertEquals("Alpha", compose.focusedText())
    }

    @Test
    fun `an option read later above the one the user moved to, at the same row as before, does not take the focus`() {
        val labels = mutableStateOf(listOf("Alpha", "Bravo", "Charlie"))
        show(labels, optionPlace(listOf("Alpha", "Bravo", "Charlie"), 1))
        compose.waitForFocus("Bravo")
        press(KEYCODE_DPAD_UP)
        compose.waitForFocus("Alpha")
        // Alpha moves to row 1, where the focus was first put.
        labels.value = listOf("Extra") + labels.value
        compose.idleFrames()
        assertEquals("Alpha", compose.focusedText())
    }

    @Test
    fun `with no option used, an option read later above the first does not take the focus`() {
        val labels = mutableStateOf(listOf("Open"))
        show(labels, null)
        compose.waitForFocus("Open")
        labels.value = listOf("Trailer", "Open")
        compose.idleFrames()
        assertEquals("Open", compose.focusedText())
    }

    @Test
    fun `an option between the same entries as the option used, before it loads, is not taken for it renamed`() {
        val labels = mutableStateOf(listOf("Alpha", "Bravo", "Delta"))
        // Charlie was between Bravo and the end, where Delta is now.
        show(labels, optionPlace(listOf("Alpha", "Bravo", "Charlie"), 2))
        compose.waitForFocus("Delta")
        labels.value = listOf("Alpha", "Bravo", "Charlie", "Delta")
        compose.waitForFocus("Charlie")
    }

    private fun show(labels: MutableState<List<String>>, used: OptionPlace?) {
        val navigator = navigators.new(Screen.Home)
        compose.setContent {
            MyTvTheme {
                CompositionLocalProvider(LocalNavigator provides navigator) {
                    val place = remember { mutableStateOf(used) }
                    ItemActionsPanel("Title", labels.value.map { ItemAction.Run(it) {} }, {}, used = place)
                }
            }
        }
    }

    private fun press(code: Int) {
        compose.sendKey(code, ACTION_DOWN)
        compose.sendKey(code, ACTION_UP)
    }
}
