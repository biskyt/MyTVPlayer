package com.biskyt.mytvplayer.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.data.ChannelFilter
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.Navigators
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.addHomeTv
import com.biskyt.mytvplayer.testutil.createUiComposeRule
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

/**
 * A long channel name takes two lines (then is cut off): in the TV guide, the channel list and Home's cards. On Search's
 * cards it takes one, and its category the second (the owner, 2026-11-26).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class ChannelNameLinesUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val provider = FakeProvider()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()

    @Before
    fun setUp() = runBlocking {
        provider.streams = provider.streams.map { if (it.id == 101L) it.copy(name = LONG) else it }
        val playlistId = app.container.addHomeTv(provider)
        // Beside a short name (UK: KITE KIDZ), for the cards' heights.
        listOf(101L, 103L).forEach { app.container.channels.setFavourite(playlistId, it, true) }
    }

    @After
    fun tearDown() = provider.close()

    private fun show(screen: Screen) {
        val navigator = navigators.new(screen)
        compose.setContent { MyTvTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
        compose.waitForText(LONG, stepMs = 50)
    }

    /** The name's own text (not, say, the guide's line above the grid that also names the channel): [lines], no more. */
    private fun assertFullLines(lines: Int = 2) {
        // Unmerged: a card's texts are merged into it for accessibility, each keeping its own layout here.
        val layouts = compose.onAllNodes(hasText(LONG, substring = true), useUnmergedTree = true).fetchSemanticsNodes().flatMap { node ->
            val result = mutableListOf<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(result)
            result
        }
        // The narrowest: in the guide the line above the grid shows the name too, across the screen.
        val name = checkNotNull(layouts.filter { it.layoutInput.text.text == LONG }.minByOrNull { it.size.width }) {
            "No text of its own: ${layouts.map { it.layoutInput.text.text to it.lineCount }}"
        }
        assertEquals(lines, name.lineCount)
        assertTrue("cut off after $lines lines", name.hasVisualOverflow)
    }

    @Test
    fun `in the TV guide`() {
        show(Screen.Live(ChannelFilter.All, guide = true))
        assertFullLines()
    }

    @Test
    fun `in the channel list`() {
        show(Screen.Live(ChannelFilter.All))
        assertFullLines()
    }

    @Test
    fun `on Home's cards, which keep one height`() {
        show(Screen.Home)
        compose.waitForText("UK: KITE KIDZ", stepMs = 50)
        assertFullLines()
        fun height(name: String) = compose.onAllNodes(hasText(name, substring = true) and hasClickAction()).fetchSemanticsNodes()
            .single().boundsInRoot.height
        assertEquals(height(LONG), height("UK: KITE KIDZ"), 0.5f)
    }

    @Test
    fun `on Search's cards, one line, with the category on the second`() {
        show(Screen.Search("Northbridge"))
        compose.waitForText("UK | ENTERTAINMENT", stepMs = 50)
        assertFullLines(lines = 1)
    }

    private companion object {
        // Longer than two lines anywhere (whatever the test's fonts measure), so all three show two lines of it.
        const val LONG = "US: Northbridge News 4 (WNBN) Detroit HD East Coast Feed with Local Weather and Traffic (backup stream)"
    }
}
