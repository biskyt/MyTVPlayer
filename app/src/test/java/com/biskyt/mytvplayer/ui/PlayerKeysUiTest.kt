package com.biskyt.mytvplayer.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.data.ChannelFilter
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.LocalMidday
import com.biskyt.mytvplayer.testutil.Navigators
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.addHomeTv
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.topRoot
import com.biskyt.mytvplayer.testutil.waitAdvancingClock
import com.biskyt.mytvplayer.testutil.waitForText
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Remote buttons on the player and the live screen, through the app's own screens. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class PlayerKeysUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    @get:Rule val midday = LocalMidday() // catch-up is filed by local day

    private val provider = FakeProvider()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var playlistId = 0L
    private lateinit var navigator: Navigator

    @Before
    fun setUp() = runBlocking {
        playlistId = app.container.addHomeTv(provider, guide = true)
    }

    @After
    fun tearDown() = provider.close()

    private fun show(start: Screen) {
        navigator = navigators.new(start)
        compose.setContent { MyTvTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
    }

    private fun waitFor(text: String) = compose.waitForText(text)

    private fun waitGone(text: String) =
        compose.waitAdvancingClock { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isEmpty() }

    /** The text and descriptions of what has focus. */
    private fun focused(): String = compose.onAllNodes(isFocused()).fetchSemanticsNodes().joinToString(" | ") { node ->
        (
            node.config.getOrNull(
                SemanticsProperties.Text
            ).orEmpty() + node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            )
            .joinToString()
    }

    /** A remote button, to the topmost window (a panel over the video is a window of its own). */
    private fun press(key: Key) {
        compose.topRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun waitForFocus(text: String) = try {
        compose.waitAdvancingClock { focused().contains(text) }
    } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
        throw AssertionError("'$text' hasn't focus: ${focused()}", e)
    }

    /** The remote's Options button brings up the controls on More; OK opens it. */
    private fun openMore() {
        press(Key.Menu)
        waitForFocus("More")
        press(Key.DirectionCenter)
        waitFor("Picture:")
    }

    @Test
    fun `the controls come up with focus on play-pause, and up goes back to the video`() {
        show(Screen.Player(playlistId, 101, ChannelFilter.All))
        waitFor("Channel guide")
        compose.waitAdvancingClock { focused().contains("Pause") || focused().contains("Play") }
        press(Key.DirectionUp)
        assertTrue(focused(), !focused().contains("Pause") && !focused().contains("Play"))
    }

    @Test
    fun `live tv's controls say how long the programme has left, with no key hints`() {
        show(Screen.Player(playlistId, 101, ChannelFilter.All))
        waitFor("Channel guide")
        waitFor(" left")
        assertTrue(compose.onAllNodes(hasText("Back hides this", substring = true)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `options again puts focus back on more, and takes the place of the subtitles panel`() {
        show(Screen.Player(playlistId, 101, ChannelFilter.All))
        waitFor("Channel guide")
        press(Key.Menu)
        waitForFocus("More")
        press(Key.DirectionLeft)
        waitForFocus("Subtitles")
        press(Key.Menu)
        waitForFocus("More")
        press(Key.DirectionLeft)
        waitForFocus("Subtitles")
        press(Key.DirectionCenter) // the Subtitles panel
        waitFor("Off")
        press(Key.Menu)
        waitGone("Off")
        waitForFocus("More")
    }

    @Test
    fun `the media buttons work with a panel open over the video`() {
        show(Screen.Live())
        compose.runOnIdle { navigator.push(Screen.Player(playlistId, 101, ChannelFilter.All)) }
        waitFor("Channel guide")
        openMore()
        press(Key.MediaFastForward)
        // The panel closes and the player answers (live TV here can't skip).
        waitFor("Skipping isn't available here")
        waitGone("Picture:")
        openMore()
        press(Key.MediaStop)
        compose.waitAdvancingClock { navigator.current.screen is Screen.Live } // stopped, back where it was opened from
    }

    @Test
    fun `the channel guide previews channel after channel, whatever their programme times`() {
        // Its programmes start at other times than channel one's: the one focused there isn't in this list.
        provider.guideShiftMin = mapOf("kite.uk" to 10)
        runBlocking {
            val epg = app.container.epg
            epg.setManual(playlistId, 103, app.container.db.epg().sourcesFor(listOf(playlistId)).first().id, "kite.uk", offsetMinutes = 0)
            epg.importAll(listOf(playlistId))
        }
        show(Screen.Player(playlistId, 101, ChannelFilter.All))
        waitFor("Channel guide")
        press(Key.Guide)
        waitForFocus("Now on one.uk")
        press(Key.DirectionLeft) // round to the last channel, Kite Kids
        waitFor("Now on kite.uk")
        press(Key.DirectionLeft)
        waitFor("UK: ARENA SPRTS 1 FHD") // which has no guide
        press(Key.DirectionLeft)
        waitFor("Now on one.uk")
    }

    @Test
    fun `the guide button with a panel open over the video opens the channel guide`() {
        show(Screen.Player(playlistId, 101, ChannelFilter.All))
        waitFor("Channel guide")
        openMore()
        press(Key.Guide)
        // The panel gives way to the channel guide, as with no panel open (not the TV's own guide).
        // Its hint for the remote, or on a touch screen its buttons.
        compose.waitAdvancingClock {
            compose.onAllNodes(hasText("other channels", substring = true) or hasText("Previous", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(compose.onAllNodes(hasText("Picture:", substring = true)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `the guide and menu buttons close the panels they open`() {
        show(Screen.Player(playlistId, 101, ChannelFilter.All))
        waitFor("Channel guide")
        press(Key.Guide)
        waitForFocus("Now on one.uk")
        press(Key.Guide)
        waitGone("Now on one.uk")
        openMore()
        press(Key.Menu)
        waitGone("Picture:")
        assertTrue(navigator.current.screen is Screen.Player) // still playing
    }

    @Test
    fun `menu with the channel guide open brings up the controls on More in its place`() {
        show(Screen.Player(playlistId, 101, ChannelFilter.All))
        waitFor("Channel guide")
        press(Key.Guide)
        waitForFocus("Now on one.uk")
        press(Key.Menu)
        waitForFocus("More")
        waitGone("Now on one.uk")
        assertTrue("the panel isn't open yet", compose.onAllNodes(hasText("Picture:", substring = true)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `a channel's catch-up keeps focus on its programmes on opening, right from the days, and back from one`() {
        show(Screen.Live(ChannelFilter.Catchup))
        waitForFocus("CHANNEL ONE")
        press(Key.DirectionCenter)
        waitFor("Earlier Today")
        // Today's programmes, newest first (not nothing, which would send the first key press to the top bar).
        waitForFocus("On Now")
        press(Key.DirectionLeft)
        waitForFocus("Today")
        press(Key.DirectionRight)
        waitForFocus("On Now")
        // Back from playing one, focus is on it again.
        press(Key.DirectionDown)
        waitForFocus("Earlier Today")
        press(Key.DirectionCenter)
        compose.waitAdvancingClock { navigator.current.screen is Screen.CatchupPlayer }
        compose.runOnIdle { navigator.pop() }
        waitForFocus("Earlier Today")
    }

    @Test
    fun `the guide button works on the live screen with focus on the tabs`() {
        show(Screen.Live())
        waitForFocus("CHANNEL ONE")
        press(Key.DirectionUp) // the filter box
        press(Key.DirectionUp) // the tabs
        assertTrue(focused(), listOf("Home", "Live TV", "Movies", "Series", "Catch-up").any { focused().contains(it) })
        press(Key.Guide)
        waitFor("No guide information")
    }

    @Test
    fun `up from the guide's first row goes to the filter box`() {
        show(Screen.Live(guide = true))
        waitForFocus("Now on one.uk")
        press(Key.DirectionDown)
        press(Key.DirectionUp)
        waitForFocus("on one.uk") // back on the first row
        press(Key.DirectionUp)
        waitForFocus("Filter by name or number")
    }
}
