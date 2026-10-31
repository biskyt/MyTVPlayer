package com.biskyt.mytvplayer.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.Navigators
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.addHomeTv
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.waitAdvancingClock
import com.biskyt.mytvplayer.testutil.waitForText
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Search results: films and series name their category, and Back from a result puts the focus on it again. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class SearchResultsUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val provider = FakeProvider()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private lateinit var navigator: Navigator

    @Before
    fun setUp() = runBlocking {
        app.container.addHomeTv(provider, vod = true)
        Unit
    }

    @After
    fun tearDown() = provider.close()

    private fun show(start: Screen) {
        navigator = navigators.new(start)
        compose.setContent { MyTvTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
    }

    @Test
    fun `films and series show their category`() {
        // "du": the film Dune (in UK | MOVIES) and the series Line of Duty (in UK | DRAMA).
        show(Screen.Search("du"))
        compose.waitForText("Dune")
        compose.waitForText("UK | MOVIES")
        compose.waitForText("UK | DRAMA")
    }

    @Test
    fun `back from a film, the focus is on it again`() {
        show(Screen.Search("du"))
        compose.waitForText("Dune")
        val dune = hasText("Dune") and hasClickAction()
        compose.onNode(dune).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNode(dune).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitAdvancingClock { navigator.current.screen is Screen.MovieDetails }
        // Play it, then Back twice: from the player to the film, then to the results.
        compose.waitForText("Play")
        compose.onNode(hasText("Play") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitAdvancingClock { navigator.current.screen is Screen.VodPlayer }
        compose.runOnUiThread { navigator.pop() }
        compose.waitAdvancingClock { navigator.current.screen is Screen.MovieDetails }
        compose.runOnUiThread { navigator.pop() }
        compose.waitAdvancingClock { navigator.current.screen is Screen.Search }
        compose.waitAdvancingClock { runCatching { compose.onNode(dune).assertIsFocused() }.isSuccess }
    }
}
