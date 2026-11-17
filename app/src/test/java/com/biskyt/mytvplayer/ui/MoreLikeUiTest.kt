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
import com.biskyt.mytvplayer.ui.common.Tab
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
 * "More like this" (the owner, 2026-11-17): a series' options and a film's More… open the titles TMDB suggests, those
 * in the playlist (which open) and the others. Without a TMDB key, the option is not there and TMDB is not asked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class MoreLikeUiTest {
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
        // A second UK drama, which TMDB suggests for Line of Duty.
        provider.series += Triple(FakeTmdb.BODYGUARD, "UK - Bodyguard", "20")
        playlistId = app.container.addHomeTv(provider, vod = true)
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

    /** Line of Duty's page on series 1, with the options of its first episode open. */
    private fun seriesOptions() {
        show(Screen.SeriesDetails(playlistId, 77, season = 1))
        compose.waitForText("Episode 1")
        compose.onNode(hasText("Episode 1") and hasClickAction()).performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForText("Mark as watched")
    }

    private val lineOfDuty get() = Screen.MoreLike(playlistId, 31917, series = true, "Line of Duty", Tab.SERIES)

    @Test
    fun `a series with a TMDB match offers More like this in its options`() {
        tmdb.use(app.container)
        seriesOptions()
        compose.waitForText("THE SHOW") // headings are in upper case
        compose.waitForText("More like this")
        click("More like this")
        compose.waitAdvancingClock { navigator.current.screen is Screen.MoreLike }
        assertEquals(lineOfDuty, navigator.current.screen)
    }

    @Test
    fun `the page shows the suggestions in TMDB's order, and OK opens one in the playlist on its page there`() {
        tmdb.use(app.container)
        show(lineOfDuty)
        compose.waitForText("More like Line of Duty")
        compose.waitForText("Suggested by TMDB")
        compose.waitForText("Happy Valley")
        assertTrue(shown("2014 · TMDB 83%"))
        // The playlist's year, and that the playlist has it.
        assertTrue(shown("2012 · In your playlist"))
        assertTrue("tv/31917/recommendations" in tmdb.requests)
        click("Bodyguard")
        compose.waitAdvancingClock { navigator.current.screen is Screen.SeriesDetails }
        assertEquals(Screen.SeriesDetails(playlistId, FakeTmdb.BODYGUARD), navigator.current.screen)
    }

    @Test
    fun `a suggestion not in the playlist opens its TMDB page, where Find in your playlist searches for it`() {
        tmdb.use(app.container)
        show(lineOfDuty)
        compose.waitForText("Happy Valley")
        click("Happy Valley")
        compose.waitAdvancingClock { navigator.current.screen is Screen.TmdbTitle }
        assertEquals(
            Screen.TmdbTitle(playlistId, FakeTmdb.HAPPY_VALLEY, series = true, "Happy Valley", null, Tab.SERIES),
            navigator.current.screen
        )
        compose.waitForText("A sergeant in West Yorkshire.")
        compose.waitForText("2014 · Crime · TMDB 83%")
        compose.waitForText("Written by Sally Wainwright")
        compose.waitForText("Play trailer")
        click("Find in your playlist")
        compose.waitAdvancingClock { navigator.current.screen is Screen.Search }
        assertEquals(Screen.Search("Happy Valley"), navigator.current.screen)
    }

    @Test
    fun `a film's More… opens More like this, with its film in the playlist`() {
        tmdb.use(app.container)
        show(Screen.MovieDetails(playlistId, 501))
        compose.waitForText("Trailer")
        click("More…")
        compose.waitForText("More like this")
        click("More like this")
        compose.waitAdvancingClock { navigator.current.screen is Screen.MoreLike }
        assertEquals(Screen.MoreLike(playlistId, 438631, series = false, "Dune", Tab.MOVIES), navigator.current.screen)
        compose.waitForText("Arrival")
        click("Paddington 2")
        compose.waitAdvancingClock { navigator.current.screen is Screen.MovieDetails }
        assertEquals(Screen.MovieDetails(playlistId, 502), navigator.current.screen)
    }

    @Test
    fun `a title with no suggestions says so`() {
        tmdb.use(app.container)
        show(Screen.MoreLike(playlistId, 1, series = true, "Bodyguard", Tab.SERIES))
        compose.waitForText("TMDB has no suggestions for this")
        assertTrue(!shown("In your playlist"))
    }

    @Test
    fun `without a TMDB key, More like this is not offered and TMDB is not asked`() {
        tmdb.use(app.container, key = null)
        seriesOptions()
        compose.settle()
        assertTrue(!shown("More like this"))
        assertEquals(emptyList<String>(), tmdb.requests)
    }
}
