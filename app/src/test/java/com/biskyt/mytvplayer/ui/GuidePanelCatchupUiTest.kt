package com.biskyt.mytvplayer.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.data.ChannelFilter
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.Navigators
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.addHomeTv
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.focusedText
import com.biskyt.mytvplayer.testutil.waitAdvancingClock
import com.biskyt.mytvplayer.testutil.waitForText
import com.biskyt.mytvplayer.ui.player.PlayerViewModel
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
 * The player's channel guide panel, on a channel with catch-up: what it showed is above what is on now, and choosing
 * one replays it from its start (the owner, 2026-11-22).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class GuidePanelCatchupUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    // A stream that loads without end (no error panel over the player).
    private val provider = FakeProvider().apply { streamsTrickle = true }
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var playlistId = 0L
    private lateinit var navigator: Navigator

    @Before
    fun setUp() = runBlocking {
        playlistId = app.container.addHomeTv(provider, guide = true)
    }

    @After
    fun tearDown() = provider.close()

    /** Channel One (3 days of catch-up) playing, with its guide panel open. */
    private fun guidePanel() {
        navigator = navigators.new(Screen.Player(playlistId, 101, ChannelFilter.All))
        compose.setContent { MyTvTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
        compose.waitForText("Channel guide")
        val vm = ViewModelProvider(navigator.current)[PlayerViewModel::class.java]
        compose.runOnUiThread { vm.openGuide() }
        compose.waitForText("Earlier Today")
    }

    @Test
    fun `it opens on what is on now, with what was on before it above`() {
        guidePanel()
        compose.waitAdvancingClock { compose.focusedText().contains("Now on one.uk") }
        val earlier = compose.onAllNodes(hasText("Earlier Today"))[0].fetchSemanticsNode().boundsInRoot.top
        val now = compose.onAllNodes(hasText("Now on one.uk"))[0].fetchSemanticsNode().boundsInRoot.top
        assertTrue(earlier < now)
    }

    @Test
    fun `a programme that was on replays from its start`() {
        guidePanel()
        compose.onAllNodes(hasText("Earlier Today") and hasClickAction())[0].performSemanticsAction(SemanticsActions.OnClick)
        compose.waitAdvancingClock { navigator.current.screen is Screen.CatchupPlayer }
        val screen = navigator.current.screen as Screen.CatchupPlayer
        assertEquals(101L, screen.streamId)
        val earlierStart = runBlocking {
            val c = app.container
            c.catchup.listing(c.playlists.get(playlistId)!!, c.channels.get(playlistId, 101)!!).programmes.first {
                it.title ==
                    "Earlier Today"
            }.startMs
        }
        assertEquals(earlierStart, screen.startMs)
    }
}
