package com.biskyt.mytvplayer.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.data.db.VOD_EPISODE
import com.biskyt.mytvplayer.data.db.VOD_MOVIE
import com.biskyt.mytvplayer.data.db.WatchProgressEntity
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.Navigators
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.addHomeTv
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.focusedText
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

/** Carry on watching's cards: a poster, the title, and the episode and time left, short. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class HomeCarryOnUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val provider = FakeProvider()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()

    private var pid = 0L

    @Before
    fun setUp() = runBlocking {
        pid = app.container.addHomeTv(provider, vod = true)
        val now = System.currentTimeMillis()
        // A film: 100 minutes, 10 in.
        app.container.vod.saveProgress(
            WatchProgressEntity(
                pid,
                VOD_MOVIE,
                501,
                positionMs = 600_000,
                durationMs = 6_000_000,
                updatedAt = now,
                title = "Dune",
                imageUrl = null
            )
        )
        // An episode: 24 minutes, 2 in.
        app.container.vod.saveProgress(
            WatchProgressEntity(
                pid, VOD_EPISODE, 9002, positionMs = 120_000, durationMs = 1_440_000, updatedAt = now + 1, title = "Line of Duty",
                imageUrl = null, seriesId = 77, season = 1, episode = 2, episodeTitle = "Second"
            )
        )
    }

    @After
    fun tearDown() = provider.close()

    @Test
    fun `a watched episode's card is the next episode, not started`() {
        runBlocking {
            val p = app.container.playlists.get(pid)!!
            val next = app.container.vod.seriesDetails(p, 77).episodes.first { it.id == 9003L }
            app.container.vod.saveProgress(
                WatchProgressEntity(
                    pid, VOD_EPISODE, 9002, positionMs = 1_440_000, durationMs = 1_440_000, updatedAt = System.currentTimeMillis() + 5,
                    title = "Line of Duty", imageUrl = null, seriesId = 77, season = 1, episode = 2, hasNext = true
                ),
                next = next
            )
        }
        val navigator = navigators.new(Screen.Home)
        compose.setContent { MyTvTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
        // Its code and running time (unknown here: none), and no "Up next after".
        compose.waitAdvancingClock(50) { compose.onAllNodes(hasText("S2E1")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, compose.onAllNodes(hasText("Up next", substring = true)).fetchSemanticsNodes().size)
    }

    @Test
    fun `a card says its episode and the time left, or a film's time left`() {
        val navigator = navigators.new(Screen.Home)
        compose.setContent { MyTvTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
        compose.waitAdvancingClock(50) { compose.onAllNodes(hasText("S1E2 22m")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, compose.onAllNodes(hasText("Second", substring = true)).fetchSemanticsNodes().size)
        assertTrue(compose.onAllNodes(hasText("1h 30m")).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `Home opens on the first card of Carry on watching, and focus stays in the row when a card is removed`() {
        val navigator = navigators.new(Screen.Home)
        compose.setContent { MyTvTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
        // The newest first: the episode, then the film.
        compose.waitAdvancingClock(50) { compose.focusedText().contains("Line of Duty") }
        compose.onNode(hasText("Line of Duty") and hasClickAction()).performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForText("Remove from Carry on watching")
        compose.onNode(hasText("Remove from Carry on watching") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        // The card now in its place, not the row below.
        compose.waitAdvancingClock(50) { compose.focusedText().contains("Dune") }
        compose.settle()
        assertTrue(compose.focusedText(), compose.focusedText().contains("Dune"))
    }
}
