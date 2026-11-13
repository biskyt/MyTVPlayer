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

/** The guides on, in their order, with Move up and Move down; moving one matches the channels again (the owner, 2026-11-13). */
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

    @Before
    fun setUp() = runBlocking<Unit> {
        val c = app.container
        pid = c.addHomeTv(provider, guide = true)
        providersName = c.db.epg().providerSource(pid)!!.name
        val extra = c.epg.addSource("Extra", provider.url + "xmltv.php?username=me&password=secret&extra=1", pid)
        c.epg.importAll(listOf(pid), only = extra)
    }

    @After
    fun tearDown() = provider.close()

    /** How far down the screen [name]'s guide is. */
    private fun top(name: String) = compose.onAllNodesWithText(name).fetchSemanticsNodes().first().boundsInRoot.top

    @Test
    fun `move up puts a guide above the other, and the channels are matched again`() {
        val navigator = navigators.new(Screen.GuideSources)
        compose.setContent { MyTvTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
        compose.waitForText("Extra", stepMs = 50)
        assertTrue("the provider's guide first", top(providersName) < top("Extra"))
        val moveUp = compose.onAllNodes(hasText("Move up") and hasClickAction())
        assertEquals("one for each guide on", 2, moveUp.fetchSemanticsNodes().size)

        moveUp[1].performSemanticsAction(SemanticsActions.OnClick) // the second guide's
        compose.waitForText("Channels matched to the guides in the new order", stepMs = 50)
        assertTrue("Extra first", top("Extra") < top(providersName))
    }
}
