package com.biskyt.mytvplayer.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
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
import com.biskyt.mytvplayer.ui.common.Tab
import com.biskyt.mytvplayer.ui.common.formatDate
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A cast or crew member's page, from TMDB (as MyPlexPlayer's): opened from the film page's cast or from Cast and crew,
 * with their biography, their titles in the playlist (which open), and the rest of their credits.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class PersonPageUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val provider = FakeProvider()
    private val tmdb = FakeTmdb()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var playlistId = 0L
    private lateinit var navigator: Navigator

    @Before
    fun setUp() = runBlocking {
        playlistId = app.container.addHomeTv(provider, vod = true)
        tmdb.use(app.container)
    }

    @After
    fun tearDown() {
        provider.close()
        tmdb.close()
    }

    private fun show(screen: Screen) {
        navigator = navigators.new(screen)
        compose.setContent { MyTvTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
    }

    private fun click(text: String) =
        compose.onAllNodes(hasText(text) and hasClickAction())[0].performSemanticsAction(SemanticsActions.OnClick)

    private fun shown(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private val chalamet = Screen.Person(0, FakeTmdb.PERSON, "Timothée Chalamet", null, "Paul Atreides", "Dune", Tab.MOVIES)

    @Test
    fun `OK on a cast photo opens the person's page`() {
        show(Screen.MovieDetails(playlistId, 501))
        compose.waitForText("Timothée Chalamet")
        click("Timothée Chalamet")
        compose.waitAdvancingClock { navigator.current.screen is Screen.Person }
        assertEquals(chalamet.copy(playlistId = playlistId), navigator.current.screen)
    }

    @Test
    fun `Cast and crew opens a person with a TMDB id, and not one without`() {
        show(Screen.MovieDetails(playlistId, 501))
        compose.waitForText("Trailer")
        click("More…")
        compose.waitForText("Cast and crew")
        click("Cast and crew")
        compose.waitForText("Lady Jessica")
        // Rebecca Ferguson has no TMDB id here: her row does nothing.
        click("Rebecca Ferguson")
        compose.settle()
        assertEquals(Screen.MovieDetails(playlistId, 501), navigator.current.screen)
        click("Timothée Chalamet")
        compose.waitAdvancingClock { navigator.current.screen is Screen.Person }
    }

    @Test
    fun `the page shows who they are, their titles in the playlist and their other credits`() {
        show(chalamet.copy(playlistId = playlistId))
        compose.waitForText("An American actor.")
        val born = formatDate(LocalDate.of(1995, 12, 27))
        compose.waitForText("Actor · Born $born in New York City, USA · Paul Atreides in Dune")
        compose.waitForText("Full biography")
        compose.waitForText("In your library")
        compose.waitForText("Also appears in")
        assert(shown("Little Women"))
        // Further down the page: the list makes it only once scrolled to.
        compose.onAllNodes(hasScrollToNodeAction())[0].performScrollToNode(hasText("Line of Duty (TV show)"))
        compose.waitForText("All credits")
        compose.onAllNodes(hasScrollToNodeAction())[0].performScrollToIndex(0)
        // A title in the playlist opens its page.
        click("Dune")
        compose.waitAdvancingClock { navigator.current.screen is Screen.MovieDetails }
        assertEquals(Screen.MovieDetails(playlistId, 501), navigator.current.screen)
    }

    @Test
    fun `a series in the playlist a year off is in the library and opens its page`() {
        show(chalamet.copy(playlistId = playlistId))
        compose.waitForText("In your library")
        click("Line of Duty")
        compose.waitAdvancingClock { navigator.current.screen is Screen.SeriesDetails }
        assertEquals(Screen.SeriesDetails(playlistId, 77), navigator.current.screen)
    }
}
