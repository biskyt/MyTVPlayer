package com.biskyt.mytvplayer.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.data.ChannelFilter
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.Navigators
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.addHomeTv
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.waitAdvancingClock
import com.biskyt.mytvplayer.testutil.waitForText
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the guide says about a programme (its episode and description), and channel cards in search. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class ProgrammeInfoUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val provider = FakeProvider()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var playlistId = 0L

    @Before
    fun setUp() = runBlocking {
        provider.nowProgramme = mapOf(
            "one.uk" to "<title>The Garden Planners</title><sub-title>Spring Planting</sub-title>" +
                "<desc>Two neighbours turn a bare yard into a summer garden.</desc>" +
                "<episode-num system=\"onscreen\">S2E5</episode-num>"
        )
        playlistId = app.container.addHomeTv(provider, guide = true)
    }

    @After
    fun tearDown() = provider.close()

    private fun show(start: Screen) {
        val navigator = navigators.new(start)
        compose.setContent { MyTvTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
    }

    private fun waitFor(text: String) = compose.waitForText(text, stepMs = 50)

    private fun count(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().size

    @Test
    fun `while watching, the controls tell of the programme, and About tells all of it`() {
        show(Screen.Player(playlistId, 101, ChannelFilter.All))
        waitFor("Spring Planting · S2 E5")
        waitFor("Two neighbours turn a bare yard")
        compose.onAllNodes(hasText("About this programme") and hasClickAction())[0].performSemanticsAction(SemanticsActions.OnClick)
        // The panel too: the episode and description a second time.
        compose.waitAdvancingClock(stepMs = 50) { count("Two neighbours turn a bare yard") >= 2 && count("Spring Planting") >= 2 }
    }

    @Test
    fun `in the TV guide, a programme's description shows above the grid and in full in its options`() {
        show(Screen.Live(ChannelFilter.All, guide = true))
        waitFor("The Garden Planners")
        waitFor("Two neighbours turn a bare yard")
        compose.onAllNodes(hasText("The Garden Planners") and hasClickAction())[0].performSemanticsAction(SemanticsActions.OnLongClick)
        waitFor("Spring Planting · S2 E5")
        compose.waitAdvancingClock(stepMs = 50) { count("Two neighbours turn a bare yard") >= 2 }
    }

    @Test
    fun `moving on in the TV guide, the last programme's description goes at once`() {
        show(Screen.Live(ChannelFilter.All, guide = true))
        waitFor("Two neighbours turn a bare yard")
        // A frame or two only: the next one's is read once focus has rested there a moment.
        compose.mainClock.autoAdvance = false
        compose.onAllNodes(hasText("Next on one.uk") and hasClickAction())[0].performSemanticsAction(SemanticsActions.RequestFocus)
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        assertEquals(0, count("Two neighbours turn a bare yard"))
        compose.mainClock.autoAdvance = true
    }

    @Test
    fun `channels found in search show what's on, as on Home`() {
        show(Screen.Search("CHANNEL ONE"))
        waitFor("UK: CHANNEL ONE HD")
        waitFor("The Garden Planners")
        waitFor("then Next on one.uk")
    }
}
