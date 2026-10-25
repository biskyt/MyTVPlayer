package com.biskyt.mytvplayer.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.data.db.VOD_EPISODE
import com.biskyt.mytvplayer.data.db.WatchProgressEntity
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.Navigators
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.addHomeTv
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.focusedText
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
import org.robolectric.annotation.Config

/** Holding OK on an episode of a series: mark it watched, or (once finished) unwatched. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class EpisodeWatchedUiTest {
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

    private fun progress(id: Long): WatchProgressEntity? = runBlocking { app.container.vod.progress(playlistId, VOD_EPISODE, id) }

    /** Holds OK on the episode called [name], then picks [option] from its menu. */
    private fun markFromMenu(name: String, option: String) {
        compose.onNode(hasText(name) and hasClickAction(), useUnmergedTree = false)
            .performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForText(option)
        compose.onNode(hasText(option) and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
    }

    @Test
    fun `an episode is marked watched, then unwatched, from its menu`() {
        val navigator = navigators.new(Screen.SeriesDetails(playlistId, 77))
        compose.setContent { MyTvTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
        compose.waitForText("Episode 2")
        compose.onNode(hasText("Episode 2") and hasClickAction()).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitAdvancingClock { compose.focusedText().contains("Episode 2") }

        markFromMenu("Episode 2", "Mark as watched")
        compose.waitAdvancingClock { progress(9002)?.finished == true }
        val row = progress(9002)!!
        assertEquals(77L, row.seriesId)
        assertEquals(1, row.season)
        assertEquals(2, row.episode)
        assertEquals("Line of Duty", row.title)
        assertEquals("Episode 2", row.episodeTitle) // the provider's title
        assertTrue("the last episode of series 1 has another after it", row.hasNext)
        compose.waitForText("Watched")
        // Where to carry on moved to S2 E1; the remote stays on the episode marked.
        compose.waitForText("Continue: S2 E1")
        assertTrue(compose.focusedText(), compose.focusedText().contains("Episode 2"))

        markFromMenu("Episode 2", "Mark as unwatched")
        compose.waitAdvancingClock { progress(9002) == null }
        assertNull(progress(9001)) // only that episode
    }

    @Test
    fun `marking an earlier episode keeps where to carry on`() {
        // Part-way through series 2's first episode.
        runBlocking {
            app.container.vod.saveProgress(
                WatchProgressEntity(
                    playlistId, VOD_EPISODE, 9003, 60_000, 3_600_000, System.currentTimeMillis(), "Line of Duty", null,
                    seriesId = 77, season = 2, episode = 1
                )
            )
        }
        val navigator = navigators.new(Screen.SeriesDetails(playlistId, 77, season = 1))
        compose.setContent { MyTvTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
        compose.waitForText("Continue: S2 E1")
        compose.waitForText("Episode 1")

        // Series 1's first episode: not next to the one being watched.
        markFromMenu("Episode 1", "Mark as watched")
        compose.waitAdvancingClock { progress(9001)?.finished == true }
        compose.waitForText("Watched")
        assertTrue("still carrying on with S2 E1", compose.onAllNodes(hasText("Continue: S2 E1")).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `a part-watched episode can be counted as watched or started again`() {
        runBlocking {
            app.container.vod.saveProgress(
                WatchProgressEntity(
                    playlistId, VOD_EPISODE, 9001, 600_000, 3_600_000, System.currentTimeMillis(), "Line of Duty", null,
                    seriesId = 77, season = 1, episode = 1
                )
            )
        }
        val navigator = navigators.new(Screen.SeriesDetails(playlistId, 77, season = 1))
        compose.setContent { MyTvTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
        compose.waitForText("Episode 1")
        compose.onNode(hasText("Episode 1") and hasClickAction()).performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForText("Mark as watched")
        compose.waitForText("Mark as unwatched")
        compose.onNode(hasText("Mark as unwatched") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitAdvancingClock { progress(9001) == null }
    }
}
