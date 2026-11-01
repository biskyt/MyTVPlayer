package com.biskyt.mytvplayer.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.Navigators
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.addHomeTv
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.settle
import com.biskyt.mytvplayer.testutil.waitForText
import com.biskyt.mytvplayer.ui.common.formatDate
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** An episode's options on the series page: "Episode description", for an episode with a description or a date. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class EpisodeDescriptionUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val provider = FakeProvider()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var playlistId = 0L

    @Before
    fun setUp() = runBlocking {
        playlistId = app.container.addHomeTv(provider, vod = true)
    }

    @After
    fun tearDown() = provider.close()

    /** The series page on [season], with the options of its episode called [name] open. */
    private fun options(season: Int, name: String) {
        val navigator = navigators.new(Screen.SeriesDetails(playlistId, 77, season = season))
        compose.setContent { MyTvTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
        compose.waitForText(name)
        compose.onNode(hasText(name) and hasClickAction()).performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForText("Mark as watched")
    }

    private fun count(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().size

    private fun shown(text: String) = count(text) > 0

    @Test
    fun `an episode's description says what it is about and when it was first shown`() {
        options(season = 1, name = "Episode 1")
        // The page behind shows these too: the panel adds its own.
        val texts = listOf("Line of Duty", "First.")
        val before = texts.map(::count)
        compose.onNode(hasText("Episode description") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForText("First shown ${formatDate(LocalDate.of(2014, 6, 26))}")
        compose.settle()
        assertEquals(before.map { it + 1 }, texts.map(::count))
    }

    @Test
    fun `an episode with only a date has a description too`() {
        options(season = 2, name = "Episode 1")
        compose.onNode(hasText("Episode description") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForText("First shown ${formatDate(LocalDate.of(2014, 7, 3))}")
    }

    @Test
    fun `an episode with no description or date has no Episode description item`() {
        options(season = 1, name = "Episode 2")
        compose.settle()
        assertTrue("no Episode description item", !shown("Episode description"))
    }
}
