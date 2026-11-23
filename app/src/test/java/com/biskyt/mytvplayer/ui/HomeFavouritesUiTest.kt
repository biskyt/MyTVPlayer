package com.biskyt.mytvplayer.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.appString
import com.biskyt.mytvplayer.data.db.CAT_MOVIE
import com.biskyt.mytvplayer.data.db.CAT_SERIES
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.Navigators
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.addHomeTv
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.waitAdvancingClock
import com.biskyt.mytvplayer.testutil.waitForText
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Home's rows of favourite series and movies, below the channels. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class HomeFavouritesUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val provider = FakeProvider()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var playlistId = 0L
    private lateinit var navigator: Navigator

    @Before
    fun setUp() = runBlocking {
        playlistId = app.container.addHomeTv(provider, vod = true)
        // Line of Duty; Paddington 2 and Heat.
        app.container.vod.setFavourite(playlistId, CAT_SERIES, 77, true)
        listOf(502L, 701L).forEach { app.container.vod.setFavourite(playlistId, CAT_MOVIE, it, true) }
    }

    @After
    fun tearDown() = provider.close()

    private fun show() {
        navigator = navigators.new(Screen.Home)
        compose.setContent { MyTvTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
    }

    private fun waitFor(text: String) = compose.waitForText(text, stepMs = 50)

    // The page itself: the rows in it scroll too, sideways.
    private val page = hasScrollToNodeAction() and SemanticsMatcher.keyIsDefined(VerticalScrollAxisRange)

    /** Scrolls to the row titled [title] once the page has it (its rows load after the channels' one). */
    private fun scrollToRow(@StringRes title: Int) {
        compose.waitAdvancingClock(stepMs = 50) {
            val indexForKey = compose.onNode(page).fetchSemanticsNode().config[SemanticsProperties.IndexForKey]
            indexForKey(title) >= 0
        }
        compose.onNode(page).performScrollToNode(hasText(appString(title)))
    }

    /** The place in the page of the row titled [title]. */
    private fun rowIndex(@StringRes title: Int) = compose.onNode(page).fetchSemanticsNode().config[SemanticsProperties.IndexForKey](title)

    private fun shown(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `favourite series and movies have rows of their own, series first, opening their page`() {
        show()
        waitFor("Your channels, on now")
        scrollToRow(R.string.home_favourite_series)
        waitFor("Line of Duty")
        scrollToRow(R.string.home_favourite_movies)
        waitFor("Paddington 2")
        waitFor("Heat")
        // By their places in the page, not on screen: the page draws only the rows in view, and the series' row can be
        // above it once the movies' row is scrolled to (how far depends on how tall the rows above have become).
        assertTrue(rowIndex(R.string.home_favourite_series) < rowIndex(R.string.home_favourite_movies))

        compose.onNode(hasText("Paddington 2") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(Screen.MovieDetails(playlistId, 502), navigator.current.screen)
    }

    @Test
    fun `a favourite's options take it off favourites, and its row with it when it was the last`() {
        show()
        waitFor("Your channels, on now")
        scrollToRow(R.string.home_favourite_series)
        waitFor("Line of Duty")
        val card = compose.onNode(hasText("Line of Duty") and hasClickAction())
        card.performSemanticsAction(SemanticsActions.RequestFocus)
        card.performSemanticsAction(SemanticsActions.OnLongClick)
        waitFor("Open series")
        waitFor("Remove from favourites")
        compose.onNode(hasText("Remove from favourites")).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitAdvancingClock(stepMs = 50) { !shown("Favourite series") }
        // Focus doesn't go with the card: it goes back to the first one on Home (here, as there are no favourite
        // channels, the button to browse them).
        compose.waitAdvancingClock(stepMs = 50) {
            compose.onAllNodes(hasText("Browse channels") and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
        runBlocking { assertFalse(77L in app.container.vod.observeFavouriteIds(playlistId, CAT_SERIES).first()) }
        assertTrue(shown("Favourite movies"))
    }
}
