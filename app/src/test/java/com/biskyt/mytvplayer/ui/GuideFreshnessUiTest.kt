package com.biskyt.mytvplayer.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.Navigators
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.addHomeTv
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.waitAdvancingClock
import com.biskyt.mytvplayer.testutil.waitForText
import com.biskyt.mytvplayer.ui.common.formatDateTime
import com.biskyt.mytvplayer.ui.common.formatRange
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * How current a guide is: each guide source says when its programmes end ("Programmes up to"), and guide matching shows
 * what is on now in the guide chosen for a channel.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class GuideFreshnessUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var provider = FakeProvider()

    @After
    fun tearDown() = provider.close()

    private fun show(screen: Screen) {
        val navigator = navigators.new(screen)
        compose.setContent { MyTvTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
    }

    /** [ms] to the second, as FakeProvider's guide times are. */
    private fun sec(ms: Long) = ms / 1000 * 1000

    /** The end of FakeProvider's last programme ("Next on …", until 90 minutes after its now). */
    private fun lastStop(providerNow: Long) = sec(providerNow + 90 * 60_000L)

    @Test
    fun `a guide source says when its programmes end`() {
        val now = System.currentTimeMillis()
        runBlocking { app.container.addHomeTv(provider, guide = true) }
        show(Screen.GuideSources)
        compose.waitForText("Programmes up to ${formatDateTime(lastStop(now))}", stepMs = 50)
    }

    @Test
    fun `a guide that the provider stopped updating a day ago still says when it ends`() {
        // The provider's guide is 26 hours old: its last programme ended 24.5 hours ago, long gone from the stored
        // programmes, but its end is kept with the guide.
        val old = System.currentTimeMillis() - 26 * 3_600_000L
        provider = FakeProvider(now = old)
        val until = runBlocking {
            val id = app.container.addHomeTv(provider, guide = true)
            app.container.db.epg().sourcesFor(listOf(id)).single().programmesUntil
        }
        assertEquals(lastStop(old), until)
        show(Screen.GuideSources)
        compose.waitForText("Programmes up to ${formatDateTime(until)}", stepMs = 50)
    }

    @Test
    fun `guide matching shows what is on now in the chosen guide, and after choosing another`() {
        val now = System.currentTimeMillis()
        val id = runBlocking { app.container.addHomeTv(provider, guide = true) }
        val range = formatRange(sec(now - 30 * 60_000L), sec(now + 30 * 60_000L))
        show(Screen.GuideMatching(id, 101))
        compose.waitForText("Currently: Channel One", stepMs = 50)
        compose.waitForText("On now: Now on one.uk ($range)", stepMs = 50)

        // Search every guide channel for another one, and choose it.
        compose.waitForText("Search all guide channels", stepMs = 50)
        compose.onNode(hasText("Search all guide channels")).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitAdvancingClock { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasSetTextAction()).onLast().performTextInput("kite")
        compose.waitAdvancingClock(stepMs = 50) {
            compose.onAllNodes(hasText("Kite Kids") and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodes(hasText("Kite Kids") and hasClickAction())[0].performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForText("Currently: Kite Kids", stepMs = 50)
        // Its programmes load shortly after the choice: what is on then shows without leaving the screen.
        compose.waitForText("On now: Now on kite.uk ($range)", stepMs = 250)
    }

    @Test
    fun `guide matching says when the chosen guide has nothing on now`() {
        provider = FakeProvider(now = System.currentTimeMillis() - 26 * 3_600_000L)
        val id = runBlocking { app.container.addHomeTv(provider, guide = true) }
        show(Screen.GuideMatching(id, 101))
        compose.waitForText("Currently: Channel One", stepMs = 50)
        compose.waitForText("Nothing on now in this guide", stepMs = 50)
    }
}
