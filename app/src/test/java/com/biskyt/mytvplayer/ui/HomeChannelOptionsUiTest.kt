package com.biskyt.mytvplayer.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A favourite channel's options on Home are Live TV's (the owner, 2026-11-15), with Home's own: Move on Favourites. Live
 * TV's own (Switch to grid view) are not there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class HomeChannelOptionsUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val provider = FakeProvider()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()

    private var playlistId = 0L

    @Before
    fun setUp() = runBlocking {
        playlistId = app.container.addHomeTv(provider)
        app.container.channels.setFavourite(playlistId, 101, true)
    }

    @After
    fun tearDown() = provider.close()

    /** The panel's options' labels, from the top (each option's first text). */
    private fun options() = compose.onAllNodes(hasClickAction() and hasAnyAncestor(isDialog())).fetchSemanticsNodes()
        .sortedBy { it.boundsInRoot.top }
        .mapNotNull { node -> node.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text }

    /** Home, with the favourite channel's options open. */
    private fun openOptions() {
        val navigator = navigators.new(Screen.Home)
        compose.setContent { MyTvTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
        compose.waitForText("UK: CHANNEL ONE HD", stepMs = 50)
        val card = compose.onNode(hasText("UK: CHANNEL ONE HD", substring = true) and hasClickAction())
        card.performSemanticsAction(SemanticsActions.RequestFocus)
        card.performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForText("Channel info", stepMs = 50)
    }

    @Test
    fun `a favourite channel's options are Live TV's, with Move`() {
        openOptions()
        assertEquals(
            listOf(
                "Watch",
                "Catch-up",
                "Restart current show",
                "Remove from favourites",
                "What's on",
                "Channel info",
                "Change guide source…",
                "Move",
                "Hide channel"
            ),
            options()
        )
    }

    @Test
    fun `Hide channel hides it from the channel lists and closes the options, and its card stays`() {
        openOptions()
        compose.onAllNodes(hasText("Hide channel") and hasClickAction()).onLast().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitAdvancingClock(50) { options().isEmpty() }
        compose.waitAdvancingClock(50) { runBlocking { app.container.db.channels().get(playlistId, 101)?.hidden == true } }
        assertTrue(
            "its card stays on Favourites",
            compose.onAllNodes(hasText("UK: CHANNEL ONE HD", substring = true)).fetchSemanticsNodes().isNotEmpty()
        )
    }
}
