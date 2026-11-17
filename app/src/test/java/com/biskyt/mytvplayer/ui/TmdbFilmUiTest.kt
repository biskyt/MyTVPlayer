package com.biskyt.mytvplayer.ui

import android.content.Intent
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The film page with TMDB's data (as MyPlexPlayer's film page): the cast along the bottom, who directed and wrote it,
 * the trailer, and More… with Cast and crew, About and the links. Without a TMDB key, nothing is asked of TMDB.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class TmdbFilmUiTest {
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

    /** Dune's page. */
    private fun open() {
        val navigator = navigators.new(Screen.MovieDetails(playlistId, 501))
        compose.setContent { MyTvTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
        compose.waitForText("Dune")
    }

    private fun shown(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun click(text: String) = compose.onNode(hasText(text) and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)

    @Test
    fun `with a TMDB key, the page shows the cast, who made it, the score and the trailer`() {
        tmdb.use(app.container)
        open()
        compose.waitForText("Directed by Denis Villeneuve · Written by Jon Spaihts")
        compose.waitForText("Timothée Chalamet")
        compose.waitForText("Paul Atreides")
        compose.waitForText("Cast")
        compose.waitForText("78%")
        // The provider's plot and genre come first; the provider's cast line gives way to TMDB's cast.
        assertTrue(shown("A story."))
        assertTrue(shown("Drama", substring = true))
        assertTrue(!shown("Cast: Someone"))
        // Play, Trailer, More…: the favourite has moved into More….
        compose.waitForText("Trailer")
        assertTrue(!shown("Favourite"))
        click("Trailer")
        val started: Intent = shadowOf(app).nextStartedActivity
        assertEquals("https://www.youtube.com/watch?v=${FakeTmdb.TRAILER}", started.dataString)
    }

    @Test
    fun `with a TMDB key, More lists the favourite, Cast and crew, About and the links`() {
        tmdb.use(app.container)
        open()
        compose.waitForText("Trailer")
        click("More…")
        compose.waitForText("Add to favourites")
        compose.waitForText("Cast and crew")
        compose.waitForText("About this film")
        compose.waitForText("Links")
        compose.waitAdvancingClock { compose.onAllNodes(hasContentDescription("IMDb")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, compose.onAllNodes(hasContentDescription("TMDB") and hasClickAction()).fetchSemanticsNodes().size)
        click("About this film")
        compose.waitForText("Dune: Part One")
        compose.waitForText("Legendary Pictures")
        compose.waitForText("Science Fiction, Adventure")
    }

    @Test
    fun `a link shows its QR code first, and opens nothing by itself`() {
        tmdb.use(app.container)
        open()
        compose.waitForText("Trailer")
        click("More…")
        compose.waitAdvancingClock {
            compose.onAllNodes(hasContentDescription("IMDb") and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasContentDescription("IMDb") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        // Some TVs have a stand-in picker that takes the browser's intent and does nothing: the panel comes first.
        compose.waitForText("www.imdb.com/title/tt1160419/")
        compose.waitForText("Open on this TV")
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test
    fun `where the YouTube app can't start, the trailer's page shows as a QR code`() {
        tmdb.use(app.container)
        // Nothing on this device answers an intent: the YouTube app's fails.
        shadowOf(app).checkActivities(true)
        open()
        compose.waitForText("Trailer")
        click("Trailer")
        compose.waitForText("www.youtube.com/watch?v=${FakeTmdb.TRAILER}")
        compose.waitForText("Open on this TV")
    }

    @Test
    fun `More's Other trailers lists every trailer, with its country, and plays the one chosen`() {
        tmdb.use(app.container)
        open()
        compose.waitForText("Trailer")
        click("More…")
        compose.waitForText("Other trailers")
        click("Other trailers")
        compose.waitForText("Teaser · GB")
        click("Teaser · GB")
        compose.waitAdvancingClock { shadowOf(app).peekNextStartedActivity() != null }
        assertEquals("https://www.youtube.com/watch?v=${FakeTmdb.TEASER}", shadowOf(app).nextStartedActivity.dataString)
    }

    @Test
    fun `Cast and crew lists the cast, then who directed and wrote it`() {
        tmdb.use(app.container)
        open()
        compose.waitForText("Trailer")
        click("More…")
        compose.waitForText("Cast and crew")
        click("Cast and crew")
        compose.waitForText("Lady Jessica")
        compose.waitForText("Director")
        compose.waitForText("Writer")
    }

    @Test
    fun `without a TMDB key, nothing is asked of TMDB and the page shows the provider's data`() {
        tmdb.use(app.container, key = null)
        open()
        compose.waitForText("A story.")
        compose.waitForText("Cast: Someone")
        compose.waitForText("More…")
        compose.settle()
        assertTrue(!shown("Trailer"))
        assertTrue(!shown("Directed by", substring = true))
        assertEquals(emptyList<String>(), tmdb.requests)
        // More… still has the favourite, and nothing from TMDB.
        click("More…")
        compose.waitForText("Add to favourites")
        compose.settle()
        assertTrue(!shown("Cast and crew"))
        assertTrue(!shown("Links"))
    }

    private fun shown(text: String, substring: Boolean) =
        compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()
}
