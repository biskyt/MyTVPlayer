package com.biskyt.mytvplayer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.container
import com.biskyt.mytvplayer.data.ChannelKinds
import com.biskyt.mytvplayer.data.db.CAT_MOVIE
import com.biskyt.mytvplayer.data.db.CAT_SERIES
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.Navigators
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.waitForText
import com.biskyt.mytvplayer.ui.live.CatchupScreen
import com.biskyt.mytvplayer.ui.settings.PlaylistEditScreen
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
import com.biskyt.mytvplayer.ui.vod.MovieDetailsScreen
import com.biskyt.mytvplayer.ui.vod.SeriesDetailsScreen
import com.biskyt.mytvplayer.ui.vod.VodBrowseScreen
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** M3U playlists' movies, series and catch-up, through the screens. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class M3uUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val provider = FakeProvider()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private lateinit var navigator: Navigator

    @After
    fun tearDown() = provider.close()

    private fun show(start: Screen) {
        navigator = navigators.new(start)
        compose.setContent { MyTvTheme { NavHost(remember { navigator }) { Screens(it) } } }
    }

    @Composable
    private fun Screens(screen: Screen) {
        when (screen) {
            is Screen.Movies -> VodBrowseScreen(CAT_MOVIE, screen.filter)
            is Screen.Series -> VodBrowseScreen(CAT_SERIES, screen.filter)
            is Screen.MovieDetails -> MovieDetailsScreen(screen)
            is Screen.SeriesDetails -> SeriesDetailsScreen(screen)
            is Screen.PlaylistEdit -> PlaylistEditScreen(screen.playlistId, screen.firstRun)
            is Screen.Catchup -> CatchupScreen(screen)
            // The players need video hardware; a marker is enough to see where navigation went.
            else -> Text(screen.toString())
        }
    }

    private fun waitFor(text: String) = compose.waitForText(text)

    @Test
    fun `an M3U playlist's movie playlist fills Movies and Series, and its addresses can be edited`() {
        provider.vodM3u = """
            #EXTM3U
            #EXTINF:-1 group-title="Animation",Big Buck Bunny (2008)
            http://vod.example/bbb.mp4
            #EXTINF:-1 group-title="Animated Series",Caminandes S01E01 - Llama Drama
            http://vod.example/cam1.mp4
        """.trimIndent()
        val m3u = runBlocking {
            val c = app.container
            c.playlists.addM3u("Free", provider.url + "free.m3u", moviesUrl = provider.url + "vod.m3u").also {
                c.playlists.syncChannels(it)
                c.vod.sync(c.playlists.get(it)!!)
            }
        }
        show(Screen.Movies())
        waitFor("Big Buck Bunny")
        compose.onNode(hasText("Big Buck Bunny") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        waitFor("2008")
        compose.onNode(hasText("Play")).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertTrue(navigator.current.screen.toString(), navigator.current.screen is Screen.VodPlayer)

        compose.runOnUiThread { navigator.reset(Screen.Series()) }
        waitFor("Caminandes")
        compose.onNode(hasText("Caminandes") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        waitFor("Llama Drama")

        compose.runOnUiThread { navigator.reset(Screen.PlaylistEdit(m3u, firstRun = false)) }
        waitFor("Movies playlist (optional)")
        waitFor(provider.url + "vod.m3u")
        waitFor("Series playlist (optional)")
    }

    @Test
    fun `an M3U channel with catch-up offers the past by the hour`() {
        provider.m3u = """
            #EXTM3U
            #EXTINF:-1 tvg-id="One.uk" catchup="shift" catchup-days="2" group-title="United Kingdom",Channel One
            http://stream.example/one.m3u8
        """.trimIndent()
        val (m3u, one) = runBlocking {
            val c = app.container
            val id = c.playlists.addM3u("Free", provider.url + "free.m3u")
            c.playlists.syncChannels(id)
            id to c.db.channels().search(id, "Channel One", 1, ChannelKinds.ALL.values).single()
        }
        assertEquals(2, one.archiveDays)
        show(Screen.Catchup(m3u, one.streamId))
        // Slots of an hour at most ("Replay from 18:00"), as there is no guide here; OK plays from the chosen one.
        waitFor("Replay from")
        val slot = hasText("Replay from", substring = true) and hasClickAction()
        compose.onAllNodes(slot)[0].performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertTrue(navigator.current.screen.toString(), navigator.current.screen is Screen.CatchupPlayer)
    }
}
