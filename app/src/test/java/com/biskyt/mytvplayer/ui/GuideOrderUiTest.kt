package com.biskyt.mytvplayer.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.Navigators
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.addHomeTv
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.guideOf
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
 * The guides on, in their order, with Move up and Move down (the owner, 2026-11-13). A move is at once; the channels are
 * matched to the guides in the new order on leaving the screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class GuideOrderUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val provider = FakeProvider()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var pid = 0L
    private var providersName = ""
    private var providers = 0L
    private var extra = 0L

    @Before
    fun setUp() = runBlocking<Unit> {
        val c = app.container
        pid = c.addHomeTv(provider, guide = true)
        val source = c.db.epg().providerSource(pid)!!
        providersName = source.name
        providers = source.id
        extra = c.epg.addSource("Extra", provider.url + "xmltv.php?username=me&password=secret&extra=1", pid)
        c.epg.importAll(listOf(pid), only = extra)
    }

    @After
    fun tearDown() = provider.close()

    /** How far down the screen [name]'s guide is. */
    private fun top(name: String) = compose.onAllNodesWithText(name).fetchSemanticsNodes().first().boundsInRoot.top

    private fun moveButtons(label: String) = compose.onAllNodes(hasText(label) and hasClickAction())

    @Test
    fun `guides move at once, several places in a row, and the channels are matched again on leaving`() {
        val navigator = navigators.new(Screen.Settings).apply { push(Screen.GuideSources) }
        compose.setContent { MyTvTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
        compose.waitForText("Extra", stepMs = 50)
        assertTrue("the provider's guide first", top(providersName) < top("Extra"))
        assertEquals("one for each guide on", 2, moveButtons("Move up").fetchSemanticsNodes().size)

        // Up, down and up again, each one at once: none waits for the channels to be matched.
        moveButtons("Move up")[1].performSemanticsAction(SemanticsActions.OnClick) // the second guide's
        compose.waitAdvancingClock(stepMs = 50) { top("Extra") < top(providersName) }
        moveButtons("Move down")[0].performSemanticsAction(SemanticsActions.OnClick)
        compose.waitAdvancingClock(stepMs = 50) { top(providersName) < top("Extra") }
        moveButtons("Move up")[1].performSemanticsAction(SemanticsActions.OnClick)
        compose.waitAdvancingClock(stepMs = 50) { top("Extra") < top(providersName) }
        assertEquals("not matched while the screen shows", providers, app.container.guideOf(pid, 101))

        moveButtons("Move up")[0].performSemanticsAction(SemanticsActions.OnClick) // the first guide's: no further
        compose.waitForText("This guide is already at the end of the list.", stepMs = 50)

        compose.runOnIdle { navigator.pop() }
        compose.waitAdvancingClock(stepMs = 50) { app.container.guideOf(pid, 101) == extra }
    }
}
