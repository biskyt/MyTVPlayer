package com.biskyt.mytvplayer.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
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
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Taking a favourite off in Favourites: focus goes to the one in its place, not out of the list (the owner,
 * 2026-11-22), in Live TV and in Movies (Series is the same screen).
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

    private fun show(screen: Screen) {
        val navigator = navigators.new(screen)
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

    @Test
    fun `in Live TV, focus goes to the next favourite channel`() {
        show(Screen.Live(ChannelFilter.Favourites))
        compose.waitForText("ARENA", stepMs = 50)
        removeFavourite("ARENA")
        compose.waitAdvancingClock(stepMs = 50) { compose.focusedText().contains("KITE") }
    }

    @Test
    fun `in Movies, focus goes to the next favourite film`() {
        show(Screen.Movies(VodFilter.Favourites))
        compose.waitForText("Paddington 2", stepMs = 50)
        val before = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().size
        removeFavourite("Paddington 2")
        compose.waitAdvancingClock(stepMs = 50) {
            compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().size < before && compose.focusedText().isNotEmpty()
        }
        // The one after it in Favourites (in the order they were added: Dune, Paddington 2, Heat).
        compose.waitAdvancingClock(stepMs = 50) { compose.focusedText().contains("Heat") }
    }
}
