package com.biskyt.mytvplayer.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.data.ChannelFilter
import com.biskyt.mytvplayer.data.VodFilter
import com.biskyt.mytvplayer.data.db.CAT_MOVIE
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.Navigators
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.addHomeTv
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.focusedText
import com.biskyt.mytvplayer.testutil.waitAdvancingClock
import com.biskyt.mytvplayer.testutil.waitForText
import com.biskyt.mytvplayer.ui.live.LiveViewModel
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Taking a favourite off in Favourites: focus goes to the one in its place, not out of the list (the owner,
 * 2026-11-22), in Live TV and in Movies (Series is the same screen). Taken off in the TV guide, nothing is kept for
 * the list to move focus by later.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class FavouriteRemovalUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val provider = FakeProvider()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var pid = 0L

    @Before
    fun setUp() = runBlocking {
        pid = app.container.addHomeTv(provider, vod = true)
        listOf(101L, 102L, 103L).forEach { app.container.channels.setFavourite(pid, it, true) }
        listOf(501L, 502L, 701L).forEach { app.container.vod.setFavourite(pid, CAT_MOVIE, it, true) }
    }

    @After
    fun tearDown() = provider.close()

    private lateinit var navigator: Navigator

    private fun show(screen: Screen) {
        navigator = navigators.new(screen)
        compose.setContent { MyTvTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
    }

    /** Opens [card]'s options with focus on it, and takes it off favourites. */
    private fun removeFavourite(card: String) {
        val node = compose.onAllNodes(hasText(card, substring = true) and hasClickAction())[0]
        node.performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitAdvancingClock(stepMs = 50) { compose.focusedText().contains(card) }
        node.performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForText("Remove from favourites", stepMs = 50)
        compose.onAllNodes(hasText("Remove from favourites") and hasClickAction())[0].performSemanticsAction(SemanticsActions.OnClick)
    }

    /**
     * Of [titles], the one after [removed] as the list shows them (by place on screen), or the one before it if it is
     * last: where focus goes once it is gone. (Favourites added in the same millisecond can come in either order.)
     */
    private fun nextAfter(removed: String, titles: List<String>): String {
        val shown = titles.map { t ->
            t to
                compose.onAllNodes(hasText(t, substring = true) and hasClickAction())[0].fetchSemanticsNode().boundsInRoot
        }
            .sortedWith(compareBy({ it.second.top }, { it.second.left })).map { it.first }
        val i = shown.indexOf(removed)
        return shown.getOrNull(i + 1) ?: shown[i - 1]
    }

    @Test
    fun `in Live TV, focus goes to the next favourite channel`() {
        show(Screen.Live(ChannelFilter.Favourites))
        compose.waitForText("ARENA", stepMs = 50)
        compose.waitForText("KITE", stepMs = 50)
        compose.waitForText("CHANNEL ONE", stepMs = 50)
        val next = nextAfter("ARENA", listOf("CHANNEL ONE", "ARENA", "KITE"))
        removeFavourite("ARENA")
        compose.waitAdvancingClock(stepMs = 50) { compose.focusedText().contains(next) }
    }

    @Test
    fun `in Movies, focus goes to the next favourite film`() {
        show(Screen.Movies(VodFilter.Favourites))
        compose.waitForText("Paddington 2", stepMs = 50)
        compose.waitForText("Heat", stepMs = 50)
        compose.waitForText("Dune", stepMs = 50)
        val next = nextAfter("Paddington 2", listOf("Dune", "Paddington 2", "Heat"))
        removeFavourite("Paddington 2")
        compose.waitAdvancingClock(stepMs = 50) { compose.focusedText().contains(next) }
    }

    @Test
    fun `in the TV guide, a removal is not kept for the list to act on later`() {
        show(Screen.Live(ChannelFilter.Favourites))
        compose.waitForText("ARENA", stepMs = 50)
        val vm = ViewModelProvider(navigator.current)[LiveViewModel::class.java]
        compose.runOnUiThread { vm.showGuide(true, moveFocus = false) }
        val ch = runBlocking { app.container.channels.get(pid, 102)!! }
        compose.runOnUiThread { vm.setFavourite(ch, false) }
        compose.waitAdvancingClock { runBlocking { 102L !in app.container.channels.observeFavouriteIds(pid).first() } }
        assertNull(vm.removedStreamId)
        assertNull(vm.removedAt)
    }
}
