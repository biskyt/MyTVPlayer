package com.biskyt.mytvplayer.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.FakeTmdb
import com.biskyt.mytvplayer.testutil.Navigators
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.addHomeTv
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.settle
import com.biskyt.mytvplayer.testutil.waitAdvancingClock
import com.biskyt.mytvplayer.testutil.waitForText
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
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

/**
 * The series page with TMDB's data: an episode with no plot from the provider gets TMDB's (the provider's own wins),
 * and an episode's options end with the show's: Cast and crew, About and the links. Without a key, TMDB is not asked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class TmdbSeriesUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val provider = FakeProvider()
    private val tmdb = FakeTmdb()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var playlistId = 0L

    @Before
    fun setUp() = runBlocking {
        playlistId = app.container.addHomeTv(provider, vod = true)
    }

    @After
    fun tearDown() {
        provider.close()
        tmdb.close()
    }

    /** Line of Duty's page on series 1, with the options of its episode called [name] open. */
    private fun options(name: String) {
        val navigator = navigators.new(Screen.SeriesDetails(playlistId, 77, season = 1))
        compose.setContent { MyTvTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
        compose.waitForText(name)
        compose.onNode(hasText(name) and hasClickAction()).performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForText("Mark as watched")
    }

    private fun shown(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun click(text: String) = compose.onNode(hasText(text) and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)

    @Test
    fun `an episode with no plot from the provider gets TMDB's`() {
        tmdb.use(app.container)
        // Episode 2 has no description from the provider: with TMDB's, its options offer one.
        options("Episode 2")
        compose.waitForText("Episode description")
        click("Episode description")
        compose.waitForText("TMDB's second.")
    }

    @Test
    fun `the provider's own plot wins over TMDB's`() {
        tmdb.use(app.container)
        options("Episode 1")
        // The show's options come once TMDB has answered; its season's episodes are asked for too.
        compose.waitForText("THE SHOW")
        compose.waitAdvancingClock { "tv/31917/season/1" in tmdb.requests }
        click("Episode description")
        compose.waitForText("First.")
        compose.settle()
        assertTrue(!shown("TMDB's first."))
    }

    @Test
    fun `an episode's options end with the show's Cast and crew, About and links`() {
        tmdb.use(app.container)
        options("Episode 1")
        compose.waitForText("THIS EPISODE") // headings are in upper case
        compose.waitForText("THE SHOW")
        compose.waitForText("Cast and crew")
        compose.waitForText("Links")
        click("About this show")
        compose.waitForText("BBC Two")
    }

    @Test
    fun `without a TMDB key, the episode's options are the provider's only`() {
        tmdb.use(app.container, key = null)
        options("Episode 2")
        compose.settle()
        assertTrue(!shown("Episode description"))
        assertTrue(!shown("THE SHOW"))
        assertEquals(emptyList<String>(), tmdb.requests)
    }
}
