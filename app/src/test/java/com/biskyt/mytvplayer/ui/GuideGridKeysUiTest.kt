package com.biskyt.mytvplayer.ui

import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyChild
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.data.ChannelFilter
import com.biskyt.mytvplayer.data.db.CAT_LIVE
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.LocalMidday
import com.biskyt.mytvplayer.testutil.Navigators
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.addHomeTv
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.focusedText
import com.biskyt.mytvplayer.testutil.idleFrames
import com.biskyt.mytvplayer.testutil.settle
import com.biskyt.mytvplayer.testutil.topRoot
import com.biskyt.mytvplayer.testutil.waitAdvancingClock
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
 * The TV guide's highlight with a long channel list and a full guide: held keys move it like presses, it reaches any
 * time ahead, and it stays in the grid at the edges; the List and TV guide buttons keep focus.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class GuideGridKeysUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    @get:Rule val midday = LocalMidday() // the day test needs tomorrow to be hours away

    private val provider = FakeProvider().apply {
        // 40 channels in two categories, each with its own guide channel and twelve hours of programmes.
        streams = (1..CHANNELS).map { FakeProvider.Stream(1000L + it, it, "Channel %02d".format(it), "${1 + it % 2}", "c$it.uk") }
        guideChannels = (1..CHANNELS).map { "c$it.uk" to "Channel $it" }
        laterHours = 12
    }
    private val app = ApplicationProvider.getApplicationContext<TestApp>()

    private var playlistId = 0L

    @Before
    fun setUp() = runBlocking<Unit> {
        playlistId = app.container.addHomeTv(provider, guide = true)
    }

    @After
    fun tearDown() = provider.close()

    private fun guide() {
        compose.setContent {
            MyTvTheme { NavHost(remember { navigators.new(Screen.Live(ChannelFilter.All, guide = true)) }) { ScreenContent(it) } }
        }
        compose.waitAdvancingClock { compose.focusedText() == "Now on c1.uk" }
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.idleFrames()
    }

    /**
     * Presses [key] until the highlight is on [text]. A press moves it one programme at most (one made while the next
     * stretch of time is loading does nothing), so a press that skipped one would never get there.
     */
    private fun pressUntil(key: Key, text: String) = compose.waitAdvancingClock(50) {
        (compose.focusedText() == text).also { if (!it) press(key) }
    }

    /** The programme [title] is on the screen in full (not cut off at its edge): the highlight on it can be seen. */
    private fun assertOnScreen(title: String) {
        compose.settle()
        val cell = compose.onNode(hasAnyChild(hasText(title)) and hasClickAction()).fetchSemanticsNode()
        val shown = cell.boundsInRoot.width
        assertTrue("$title: $shown of its ${cell.size.width} shown", cell.size.width > 0 && shown >= cell.size.width - 1)
    }

    /** A remote button held down: the key and [repeats] repeats, as fast as a remote sends them (no frame between). */
    private fun hold(code: Int, repeats: Int) {
        for (r in 0..repeats) compose.topRoot().performKeyPress(KeyEvent(NativeKeyEvent(0, 0, NativeKeyEvent.ACTION_DOWN, code, r)))
        compose.topRoot().performKeyPress(KeyEvent(NativeKeyEvent(0, 0, NativeKeyEvent.ACTION_UP, code, 0)))
        compose.settle()
    }

    @Test
    fun `holding down moves one channel per repeat, and stays in the guide`() {
        guide()
        hold(NativeKeyEvent.KEYCODE_DPAD_DOWN, repeats = 29)
        compose.waitAdvancingClock(50) { compose.focusedText() == "Now on c31.uk" }
        // And back up again, held for longer: it stops on the first channel (leaving for the filter box takes a press of
        // its own).
        hold(NativeKeyEvent.KEYCODE_DPAD_UP, repeats = 39)
        compose.waitAdvancingClock(50) { compose.focusedText() == "Now on c1.uk" }
    }

    @Test
    fun `holding down at the last channel stays on it`() {
        guide()
        hold(NativeKeyEvent.KEYCODE_DPAD_DOWN, repeats = CHANNELS + 10)
        compose.waitAdvancingClock(50) { compose.focusedText() == "Now on c$CHANNELS.uk" }
    }

    @Test
    fun `right keeps going later, through the whole guide, and left comes back on the same channel`() {
        guide()
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        compose.waitAdvancingClock(50) { compose.focusedText() == "Now on c3.uk" }
        pressUntil(Key.DirectionRight, "Next on c3.uk")
        assertOnScreen("Next on c3.uk")
        for (k in 1..12) {
            pressUntil(Key.DirectionRight, "Later $k on c3.uk")
            assertOnScreen("Later $k on c3.uk")
        }
        // Back to the start, one programme at a time, without leaving the row.
        for (k in 11 downTo 1) pressUntil(Key.DirectionLeft, "Later $k on c3.uk")
        pressUntil(Key.DirectionLeft, "Next on c3.uk")
        pressUntil(Key.DirectionLeft, "Now on c3.uk")
    }

    @Test
    fun `left at the start of the guide stays in it, even held`() {
        guide()
        hold(NativeKeyEvent.KEYCODE_DPAD_LEFT, repeats = 10)
        assertEquals("Now on c1.uk", compose.focusedText())
        // Later, past the window's edge, then back to the start (each press once its programmes are in), and on.
        hold(NativeKeyEvent.KEYCODE_DPAD_RIGHT, repeats = 5)
        compose.waitAdvancingClock(50) { compose.focusedText() != "Now on c1.uk" }
        pressUntil(Key.DirectionLeft, "Now on c1.uk")
        hold(NativeKeyEvent.KEYCODE_DPAD_LEFT, repeats = 20)
        assertEquals("Now on c1.uk", compose.focusedText())
    }

    @Test
    fun `the time header says which day it is, and when a new one starts`() {
        guide()
        assertTrue(compose.onAllNodes(hasText("Today", substring = true)).fetchSemanticsNodes().isNotEmpty())
        // Later and later, until the header names the day starting (the guide goes three days ahead).
        compose.waitAdvancingClock(50) {
            compose.onAllNodes(hasText("Tomorrow")).fetchSemanticsNodes().isNotEmpty().also {
                if (!it) press(Key.DirectionRight)
            }
        }
    }

    @Test
    fun `the list and tv guide buttons keep focus`() {
        guide()
        compose.onNode(hasText("List") and hasClickAction()).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitAdvancingClock(50) { compose.focusedText() == "List" }
        press(Key.DirectionCenter)
        compose.waitAdvancingClock(50) { compose.onAllNodes(hasText("Channel 01")).fetchSemanticsNodes().isNotEmpty() }
        compose.settle()
        assertEquals("List", compose.focusedText())
        press(Key.DirectionRight)
        compose.waitAdvancingClock(50) { compose.focusedText() == "TV guide" }
        press(Key.DirectionCenter)
        compose.waitAdvancingClock(50) { compose.onAllNodes(hasText("Now on c1.uk")).fetchSemanticsNodes().isNotEmpty() }
        compose.settle()
        assertEquals("TV guide", compose.focusedText())
    }

    @Test
    fun `another category starts the highlight on its first channel`() {
        guide()
        hold(NativeKeyEvent.KEYCODE_DPAD_DOWN, repeats = 29)
        compose.waitAdvancingClock(50) { compose.focusedText() == "Now on c31.uk" }
        // Sport has the odd channels: twenty, so row 31 isn't one of its rows.
        val sport = hasText("SPORT", substring = true) and hasClickAction()
        compose.onNode(sport).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitAdvancingClock(50) { compose.onAllNodes(hasText("Channel 02")).fetchSemanticsNodes().isEmpty() }
        // Its first row in view (not the row Sport has the channel at the top before).
        compose.waitAdvancingClock(50) { compose.onAllNodes(hasText("Channel 01")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(sport).performSemanticsAction(SemanticsActions.RequestFocus)
        press(Key.DirectionRight)
        compose.waitAdvancingClock(50) { compose.focusedText() == "Now on c1.uk" }
    }

    @Test
    fun `another list shows its first row, wherever the channel on top before is in it`() {
        // Favourites: channels 2 to 30, then channel 1 (on top of All channels).
        val order = (2L..30L).map { 1000 + it } + 1001L
        runBlocking {
            order.forEach { app.container.channels.setFavourite(playlistId, it, true) }
            app.container.favourites.setOrder(playlistId, CAT_LIVE, order)
        }
        guide()
        compose.onNode(hasText("Favourites") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitAdvancingClock(50) { compose.onAllNodes(hasText("Channel 02")).fetchSemanticsNodes().isNotEmpty() }
        compose.settle()
        assertTrue(compose.onAllNodes(hasText("Channel 02")).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `all channels shows how many there are`() {
        guide()
        // (Each category has 20: only All channels can say 40.)
        compose.waitAdvancingClock(50) { compose.onAllNodes(hasText("$CHANNELS")).fetchSemanticsNodes().isNotEmpty() }
    }

    private companion object {
        const val CHANNELS = 40
    }
}
