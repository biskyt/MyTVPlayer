package com.biskyt.mytvplayer.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.Navigators
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.addHomeTv
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.settle
import com.biskyt.mytvplayer.testutil.waitAdvancingClock
import com.biskyt.mytvplayer.testutil.waitForText
import com.biskyt.mytvplayer.ui.player.VodPlayerViewModel
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

/** The top bar (tabs, search, settings) is on every screen but playback and first-run set-up. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class TopBarScreensUiTest {
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

    private fun show(screen: Screen, hasPlaylist: Boolean = true): Navigator {
        val navigator = navigators.new(screen)
        compose.setContent { MyTvTheme { NavHost(remember { navigator }) { ScreenContent(it, hasPlaylist) } } }
        return navigator
    }

    private fun topBars() = compose.onAllNodes(hasContentDescription("Search")).fetchSemanticsNodes().size

    private fun hasTopBar(screen: Screen, text: String) {
        show(screen)
        compose.waitForText(text)
        compose.waitAdvancingClock { topBars() == 1 }
    }

    @Test
    fun `a film's page has the top bar`() = hasTopBar(Screen.MovieDetails(playlistId, 501), "Dune")

    @Test
    fun `a film's art goes behind the top bar, its details below it`() {
        hasTopBar(Screen.MovieDetails(playlistId, 501), "Dune")
        compose.waitAdvancingClock { compose.onAllNodes(hasTestTag("backdrop")).fetchSemanticsNodes().isNotEmpty() }
        val art = compose.onNode(hasTestTag("backdrop")).fetchSemanticsNode().boundsInRoot
        val bar = compose.onNode(hasContentDescription("Search")).fetchSemanticsNode().boundsInRoot
        val title = compose.onNode(hasText("Dune")).fetchSemanticsNode().boundsInRoot
        assertEquals("the art starts at the top", 0f, art.top)
        assertTrue("the details start below the bar", title.top > bar.bottom)
    }

    @Test
    fun `a series' page has the top bar`() = hasTopBar(Screen.SeriesDetails(playlistId, 77), "Line of Duty")

    @Test
    fun `settings has the top bar`() = hasTopBar(Screen.Settings, "Playlists")

    @Test
    fun `a playlist's settings have the top bar`() = hasTopBar(Screen.PlaylistEdit(playlistId), "Home TV")

    @Test
    fun `languages and categories have the top bar`() = hasTopBar(Screen.Categories(playlistId), "ENTERTAINMENT")

    @Test
    fun `appearance has the top bar`() = hasTopBar(Screen.Appearance, "Appearance")

    @Test
    fun `backup has the top bar`() = hasTopBar(Screen.Backup, "Back up")

    @Test
    fun `guide matching has the top bar`() = hasTopBar(Screen.GuideMatching(playlistId), "UK: CHANNEL ONE HD")

    @Test
    fun `first-run set-up has no top bar`() {
        show(Screen.PlaylistEdit(null, firstRun = true))
        compose.settle()
        assertEquals(0, topBars())
    }

    @Test
    fun `before there is a playlist there is no top bar`() {
        show(Screen.Backup, hasPlaylist = false)
        compose.settle()
        assertEquals(0, topBars())
    }

    @Test
    fun `playback has no top bar`() {
        val navigator = show(Screen.VodPlayer(playlistId, 501))
        val vm = ViewModelProvider(navigator.current)[VodPlayerViewModel::class.java]
        compose.waitAdvancingClock { vm.engine?.player?.currentMediaItem != null }
        compose.settle()
        assertEquals(0, topBars())
    }
}
