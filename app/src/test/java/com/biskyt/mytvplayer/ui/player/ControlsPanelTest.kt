package com.biskyt.mytvplayer.ui.player

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.ui.common.Muted
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The shading behind the playback controls, so their text reads over a bright picture. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-xhdpi")
class ControlsPanelTest {
    @get:Rule val compose = createUiComposeRule()

    @Test
    fun `the top line of the controls is shaded dark over a white picture`() {
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            MyTvTheme {
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    ControlsPanel {
                        Muted("1 · Riverside One HD")
                        Spacer(Modifier.height(400.dp)) // taller than the live controls: the shading mustn't depend on their height
                    }
                }
            }
        }
        compose.waitForIdle()
        val line = compose.onNode(hasText("1 · Riverside One HD")).fetchSemanticsNode().boundsInRoot
        val picture = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnIdle { view.draw(Canvas(picture)) }
        // Just left of the line's start, at its middle: the shading itself, not the text.
        val behind = Color(picture.getPixel(line.left.toInt() - 4, line.center.y.toInt())).luminance()
        assertTrue("behind the top line is $behind light", behind < 0.1f)
    }

    @Test
    fun `small text over the video has a shadow`() {
        compose.setContent { MyTvTheme { ControlsPanel { Muted("1 · Riverside One HD") } } }
        compose.waitForIdle()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNode(hasText("1 · Riverside One HD"), useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        assertNotNull(layouts.single().layoutInput.style.shadow)
    }
}
