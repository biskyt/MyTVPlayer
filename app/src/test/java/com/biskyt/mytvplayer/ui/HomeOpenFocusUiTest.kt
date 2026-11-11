package com.biskyt.mytvplayer.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.data.db.CAT_MOVIE
import com.biskyt.mytvplayer.data.db.VOD_MOVIE
import com.biskyt.mytvplayer.data.db.WatchProgressEntity
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.Navigators
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.addHomeTv
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.focusedText
import com.biskyt.mytvplayer.testutil.pressAndRead
import com.biskyt.mytvplayer.testutil.settle
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

/**
 * Each time the app opens, Home is at its top, on its first card: the first in Continue watching, or else the first
 * favourite channel (the owner, 2026-11-11).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class HomeOpenFocusUiTest {
    @get:Rule val compose = createUiComposeRule<ComponentActivity>()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val provider = FakeProvider()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var pid = 0L

    @Before
    fun setUp() = runBlocking {
        pid = app.container.addHomeTv(provider, vod = true)
        // A favourite channel on the first row; favourite movies far below it.
        app.container.channels.setFavourite(pid, 101, true)
        listOf(502L, 701L).forEach { app.container.vod.setFavourite(pid, CAT_MOVIE, it, true) }
    }

    @After
    fun tearDown() = provider.close()

    private var navigator: Navigator? = null

    /** Home, once its first card has focus: what that card says. */
    private fun home(content: (@androidx.compose.runtime.Composable () -> Unit) -> Unit = { compose.setContent(it) }): String {
        navigator = navigators.new(Screen.Home)
        content { MyTvTheme { NavHost(navigator!!) { ScreenContent(it) } } }
        compose.waitAdvancingClock(stepMs = 50) { compose.focusedText().isNotEmpty() }
        return compose.focusedText()
    }

    /** Focus on the favourite film "Heat", far down the page. */
    private fun onHeat() {
        compose.waitForText("Heat", stepMs = 50)
        compose.onNode(hasText("Heat") and hasClickAction()).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitAdvancingClock(stepMs = 50) { "Heat" in compose.focusedText() }
    }

    @Test
    fun `back from the background, Home is on its first card again`() {
        val first = home()
        onHeat()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitAdvancingClock(stepMs = 50) { compose.focusedText() == first }
        assertAtTop()
    }

    /** The page is at its top: the first row's title is on screen. */
    private fun assertAtTop() {
        compose.settle()
        compose.onNode(hasText("Your channels, on now")).assertIsDisplayed()
    }

    @Test
    fun `started again by Android from its saved state, Home is on its first card, not the one used last`() {
        val restoration = StateRestorationTester(compose)
        val first = home { restoration.setContent(it) }
        onHeat()
        compose.settle()
        // Android stopped the app and starts it again: a new run makes a new navigator, then gets the saved state back.
        navigator = navigators.new(Screen.Home)
        restoration.emulateSavedInstanceStateRestore()
        compose.waitAdvancingClock(stepMs = 50) { compose.focusedText() == first }
        compose.settle()
        assertEquals(first, compose.focusedText())
        assertAtTop()
    }

    @Test
    fun `Continue watching coming in after Home opened takes focus, until the remote is used`() {
        val first = home()
        film(501, "Dune")
        compose.waitAdvancingClock(stepMs = 50) { "Dune" in compose.focusedText() }

        // Once the remote has moved focus, a row coming in leaves it where it is.
        val moved = compose.pressAndRead(Key.DirectionDown)
        assertEquals("down to the channel Home opened on", first, moved)
        film(601, "Amélie")
        compose.waitForText("Amélie", stepMs = 50)
        compose.settle()
        assertEquals(moved, compose.focusedText())
    }

    /** A film started on another TV (a sync brings it in): it goes on Continue watching. */
    private fun film(id: Long, title: String) = runBlocking {
        app.container.vod.saveProgress(
            WatchProgressEntity(pid, VOD_MOVIE, id, 600_000, 6_000_000, System.currentTimeMillis() + id, title, null)
        )
    }
}
