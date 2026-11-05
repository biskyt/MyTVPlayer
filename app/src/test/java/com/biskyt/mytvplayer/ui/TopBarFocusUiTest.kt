package com.biskyt.mytvplayer.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import com.biskyt.mytvplayer.testutil.Navigators
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.waitAdvancingClock
import com.biskyt.mytvplayer.ui.common.Tab
import com.biskyt.mytvplayer.ui.common.TopBar
import com.biskyt.mytvplayer.ui.theme.LocalAccent
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A top bar item with focus is filled with the accent colour, with no ring, as the film and series pages' buttons are. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class TopBarFocusUiTest {
    @get:Rule val compose = createUiComposeRule<ComponentActivity>()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    @Test
    fun `the item with focus is filled with the accent colour, to its edge`() {
        val navigator = navigators.new(Screen.Home)
        var accent = Color.Unspecified
        compose.setContent {
            MyTvTheme {
                accent = LocalAccent.current.color
                CompositionLocalProvider(LocalNavigator provides navigator) { TopBar(Tab.HOME) }
            }
        }
        // Not the current tab: its text is not in the accent colour.
        val live = compose.onNode(hasText("Live TV") and hasClickAction())
        live.performSemanticsAction(SemanticsActions.RequestFocus)
        // The test's frame clock moves on, so the item recomposes and redraws with its focus.
        compose.mainClock.advanceTimeBy(FRAME_MS * 10)
        compose.waitAdvancingClock { true }

        // Drawn here: captureToImage waits for a frame that the paused main looper never draws.
        val view = compose.activity.window.decorView
        val screen = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(screen)) }
        val node = live.fetchSemanticsNode()
        val middle = (node.positionInRoot.y + node.size.height / 2).toInt()
        // At its edge (where a ring would be) and in the room before the text: the accent fill.
        listOf(1, 6).forEach { x ->
            val pixel = Color(screen.getPixel(node.positionInRoot.x.toInt() + x, middle))
            assertEquals("pixel $x across", accent.toArgb(), pixel.toArgb())
        }
    }

    private companion object {
        const val FRAME_MS = 20L
    }
}
