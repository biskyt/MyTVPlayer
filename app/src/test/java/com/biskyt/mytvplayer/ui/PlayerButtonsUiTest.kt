package com.biskyt.mytvplayer.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.core.player.RemoteKey
import com.biskyt.mytvplayer.data.ChannelFilter
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.Navigators
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.addHomeTv
import com.biskyt.mytvplayer.testutil.assertColourAcross
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.drawScreen
import com.biskyt.mytvplayer.testutil.focusedText
import com.biskyt.mytvplayer.testutil.pressAndRead
import com.biskyt.mytvplayer.testutil.waitAdvancingClock
import com.biskyt.mytvplayer.ui.player.PlayerViewModel
import com.biskyt.mytvplayer.ui.theme.LocalAccent
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
import com.biskyt.mytvplayer.ui.theme.Palette
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The player's buttons are filled with the accent colour only while they have focus, as menus are (the owner,
 * 2026-11-11): play/pause too, which was always filled.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class PlayerButtonsUiTest {
    @get:Rule val compose = createUiComposeRule<ComponentActivity>()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val provider = FakeProvider().apply { streamsTrickle = true }
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var accent = Color.Unspecified
    private var playlistId = 0L

    @Before
    fun setUp() = runBlocking<Unit> { playlistId = app.container.addHomeTv(provider) }

    @After
    fun tearDown() = provider.close()

    /** The button whose icon or words say [label]. */
    private fun button(label: String) = compose.onNode(
        hasClickAction() and hasAnyDescendant(hasContentDescription(label) or hasText(label)),
        useUnmergedTree = true
    ).fetchSemanticsNode()

    /** [label]'s button is filled with [colour] (inside its edge, beside its icon). */
    private fun assertButton(label: String, colour: Color) = assertColourAcross(compose.drawScreen(), button(label), listOf(INSIDE), colour)

    @Test
    fun `play-pause is filled only while it has focus, and the button with focus is filled`() {
        val navigator = navigators.new(Screen.Player(playlistId, 101, ChannelFilter.All))
        compose.setContent {
            MyTvTheme {
                accent = LocalAccent.current.color
                NavHost(remember { navigator }) { ScreenContent(it) }
            }
        }
        val vm = ViewModelProvider(navigator.current)[PlayerViewModel::class.java]
        compose.waitAdvancingClock(stepMs = 50) { vm.channel != null && compose.focusedText().let { it == "Pause" || it == "Play" } }
        val playPause = compose.focusedText()
        assertButton(playPause, accent)
        assertButton("Skip back", Palette.surfaceHigh)

        compose.pressAndRead(Key.DirectionLeft)
        compose.waitAdvancingClock(stepMs = 50) { compose.focusedText() == "Skip back" }
        assertButton("Skip back", accent)
        assertButton(playPause, Palette.surfaceHigh)

        // A button with words too: More (the remote's Menu button takes focus there).
        assertButton("More", Palette.surfaceHigh)
        compose.runOnIdle { vm.onKey(RemoteKey.MENU) }
        compose.waitAdvancingClock(stepMs = 50) { compose.focusedText() == "More" }
        assertButton("More", accent)
    }

    private companion object {
        /** Pixels in from a button's left edge, at its middle: inside the circle, before the icon. */
        const val INSIDE = 5
    }
}
