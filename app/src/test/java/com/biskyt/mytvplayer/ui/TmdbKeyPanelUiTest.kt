package com.biskyt.mytvplayer.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.testutil.FakeTmdb
import com.biskyt.mytvplayer.testutil.Navigators
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.settle
import com.biskyt.mytvplayer.testutil.waitForText
import com.biskyt.mytvplayer.ui.settings.TmdbKeyPanel
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Settings › TMDB's key panel: the help for a phone, and a fresh start each time it opens. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class TmdbKeyPanelUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val tmdb = FakeTmdb()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()

    @After
    fun tearDown() = tmdb.close()

    private fun shown(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `opened again, the panel no longer shows the last check's answer`() {
        tmdb.use(app.container)
        var open by mutableStateOf(true)
        val navigator = navigators.new(Screen.Settings)
        compose.setContent {
            MyTvTheme { NavHost(remember { navigator }) { if (open) TmdbKeyPanel(FakeTmdb.TEST_KEY) { open = false } } }
        }
        compose.waitForText("Save")
        compose.onNode(hasText("Save") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForText("TMDB accepted the key")

        compose.runOnUiThread { open = false }
        compose.settle()
        compose.runOnUiThread { open = true }
        compose.waitForText("Save")
        compose.settle()
        assertTrue(!shown("TMDB accepted the key"))
    }
}
