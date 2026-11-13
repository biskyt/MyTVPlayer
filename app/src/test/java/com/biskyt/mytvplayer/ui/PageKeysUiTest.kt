package com.biskyt.mytvplayer.ui

import android.content.pm.PackageManager
import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performKeyPress
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.data.ChannelFilter
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.Navigators
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.addHomeTv
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.focusedText
import com.biskyt.mytvplayer.testutil.settle
import com.biskyt.mytvplayer.testutil.topRoot
import com.biskyt.mytvplayer.testutil.waitAdvancingClock
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The remote's channel up and down buttons move a page up or down (the owner, 2026-11-08): in the channel list, the TV
 * guide, the film grid and the categories (2026-11-13). Held, they go on a page at a time, and they stop at the first and last rows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class PageKeysUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val provider = FakeProvider().apply {
        // A category for each channel, so that the categories need pages too.
        categories = (1..COUNT).map { "$it" to "Group %02d".format(it) }
        streams = (1..COUNT).map { FakeProvider.Stream(1000L + it, it, "Channel %02d".format(it), "$it", "c$it.uk") }
        guideChannels = (1..COUNT).map { "c$it.uk" to "Channel $it" }
        movies = (1..COUNT).map { Triple(5000L + it, "UK - Film %02d".format(it), "10") }
    }
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var playlistId = 0L

    @Before
    fun setUp() = runBlocking<Unit> {
        playlistId = app.container.addHomeTv(provider, guide = true)
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
    }

    @After
    fun tearDown() = provider.close()

    private fun show(screen: Screen, first: String) {
        compose.setContent { MyTvTheme { NavHost(remember { navigators.new(screen) }) { ScreenContent(it) } } }
        compose.waitAdvancingClock(stepMs = 50) { first in compose.focusedText() }
    }

    /** Presses [code] once, or holds it for [repeats] repeats (as fast as a remote sends them). */
    private fun press(code: Int, repeats: Int = 0) {
        for (r in 0..repeats) compose.topRoot().performKeyPress(KeyEvent(NativeKeyEvent(0, 0, NativeKeyEvent.ACTION_DOWN, code, r)))
        compose.topRoot().performKeyPress(KeyEvent(NativeKeyEvent(0, 0, NativeKeyEvent.ACTION_UP, code, 0)))
        compose.settle()
    }

    /** The number in what has focus ("Channel 07", "Now on c7.uk", "Film 07"). */
    private fun focusedNumber(): Int = Regex("""(\d+)""").findAll(compose.focusedText()).last().value.toInt()

    @Test
    fun `channel down and up move a page in the channel list`() {
        show(Screen.Live(ChannelFilter.All), "Channel 01")
        press(NativeKeyEvent.KEYCODE_CHANNEL_DOWN)
        val paged = focusedNumber()
        assertTrue("A page down, more than one row: on $paged", paged > 2)
        press(NativeKeyEvent.KEYCODE_CHANNEL_DOWN)
        assertTrue("A second page: on ${focusedNumber()}", focusedNumber() > paged)
        press(NativeKeyEvent.KEYCODE_CHANNEL_UP)
        compose.waitAdvancingClock(stepMs = 50) { focusedNumber() == paged }

        // Held: to the last channel, and back to the first; focus stays in the list.
        press(NativeKeyEvent.KEYCODE_CHANNEL_DOWN, repeats = 10)
        compose.waitAdvancingClock(stepMs = 50) { "Channel $COUNT" in compose.focusedText() }
        press(NativeKeyEvent.KEYCODE_CHANNEL_UP, repeats = 10)
        compose.waitAdvancingClock(stepMs = 50) { "Channel 01" in compose.focusedText() }
    }

    @Test
    fun `channel down and up move a page in the TV guide`() {
        show(Screen.Live(ChannelFilter.All, guide = true), "Now on c1.uk")
        press(NativeKeyEvent.KEYCODE_CHANNEL_DOWN)
        val paged = focusedNumber()
        assertTrue("A page down, more than one row: on $paged", paged > 2)
        press(NativeKeyEvent.KEYCODE_CHANNEL_UP)
        compose.waitAdvancingClock(stepMs = 50) { compose.focusedText() == "Now on c1.uk" }

        press(NativeKeyEvent.KEYCODE_CHANNEL_DOWN, repeats = 10)
        compose.waitAdvancingClock(stepMs = 50) { compose.focusedText() == "Now on c$COUNT.uk" }
        press(NativeKeyEvent.KEYCODE_CHANNEL_UP, repeats = 10)
        compose.waitAdvancingClock(stepMs = 50) { compose.focusedText() == "Now on c1.uk" }
    }

    @Test
    fun `channel down and up move a page of rows in the film grid, in the same column`() {
        runBlocking { app.container.vod.sync(app.container.playlists.get(playlistId)!!) }
        show(Screen.Movies(), "Film 01")
        press(NativeKeyEvent.KEYCODE_CHANNEL_DOWN)
        val paged = focusedNumber()
        assertTrue("Whole rows down, in the first column: on $paged", paged > 1 && (paged - 1) % COLUMNS == 0)
        press(NativeKeyEvent.KEYCODE_CHANNEL_UP)
        compose.waitAdvancingClock(stepMs = 50) { "Film 01" in compose.focusedText() }

        // Held: to the last row (the first column's film there), and back.
        press(NativeKeyEvent.KEYCODE_CHANNEL_DOWN, repeats = 10)
        val last = (COUNT - 1) / COLUMNS * COLUMNS + 1
        compose.waitAdvancingClock(stepMs = 50) { "Film $last" in compose.focusedText() }
        press(NativeKeyEvent.KEYCODE_CHANNEL_UP, repeats = 10)
        compose.waitAdvancingClock(stepMs = 50) { "Film 01" in compose.focusedText() }
    }

    /** The number of the category that has focus ("Group 07"), or 0 for another entry. */
    private fun focusedGroup(): Int = Regex("""Group (\d+)""").find(compose.focusedText())?.groupValues?.get(1)?.toInt() ?: 0

    @Test
    fun `channel down and up move a page in the categories, and the channels shown follow`() {
        show(Screen.Live(ChannelFilter.All), "Channel 01")
        press(NativeKeyEvent.KEYCODE_DPAD_LEFT)
        compose.waitAdvancingClock(stepMs = 50) { "All channels" in compose.focusedText() }
        press(NativeKeyEvent.KEYCODE_CHANNEL_DOWN)
        val paged = focusedGroup()
        assertTrue("A page down, more than one row: on ${compose.focusedText()}", paged > 2)
        // The category's channel, as with up and down.
        compose.waitAdvancingClock(stepMs = 50) {
            compose.onAllNodesWithText("Channel %02d".format(paged)).fetchSemanticsNodes().isNotEmpty()
        }

        press(NativeKeyEvent.KEYCODE_CHANNEL_DOWN, repeats = 10)
        compose.waitAdvancingClock(stepMs = 50) { focusedGroup() == COUNT }
        press(NativeKeyEvent.KEYCODE_CHANNEL_UP, repeats = 10)
        compose.waitAdvancingClock(stepMs = 50) { "Favourites" in compose.focusedText() }
    }

    private companion object {
        const val COUNT = 40
        const val COLUMNS = 6 // VodBrowseScreen's
    }
}
