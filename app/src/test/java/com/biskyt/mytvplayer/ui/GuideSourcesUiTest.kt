package com.biskyt.mytvplayer.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.data.db.PlaylistEpgSourceEntity
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The guide sources screen shows the guides of the playlist in use, and switches them on and off for it alone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class GuideSourcesUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val provider = FakeProvider()
    private val other = FakeProvider()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var home = 0L
    private var otherTv = 0L

    @Before
    fun setUp() = runBlocking<Unit> {
        val c = app.container
        home = c.addHomeTv(provider, guide = true)
        otherTv = c.playlists.add("Other TV", other.url, "me", "secret") // and switched to
        c.playlists.syncChannels(otherTv)
        c.sync.updateOutOfDateGuides(otherTv).getOrThrow()
    }

    @After
    fun tearDown() {
        provider.close()
        other.close()
    }

    private fun links() = runBlocking { app.container.db.backup().guideLinks().toSet() }

    @Test
    fun `the playlist in use's guides, switched on for it alone`() {
        val homeGuide = runBlocking { app.container.db.epg().sourcesFor(listOf(home)).single().id }
        val otherGuide = runBlocking { app.container.db.epg().sourcesFor(listOf(otherTv)).single().id }
        val navigator = navigators.new(Screen.GuideSources)
        compose.setContent { MyTvTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
        compose.waitForText("TV guide sources · Other TV", stepMs = 50)
        compose.waitForText("Guides for Other TV", stepMs = 50)
        compose.waitForText("From your provider", stepMs = 50) // its own guide
        compose.waitForText("Home TV's provider", stepMs = 50)
        compose.waitForText("off here", stepMs = 50)

        compose.onAllNodes(hasText("Home TV guide") and hasClickAction())[0].performSemanticsAction(SemanticsActions.OnClick)
        val both =
            setOf(
                PlaylistEpgSourceEntity(home, homeGuide),
                PlaylistEpgSourceEntity(otherTv, otherGuide),
                PlaylistEpgSourceEntity(otherTv, homeGuide)
            )
        compose.waitAdvancingClock(stepMs = 50) { links() == both }
        compose.waitForText("also used by Home TV", stepMs = 50)

        compose.onAllNodes(hasText("Other TV guide") and hasClickAction())[0].performSemanticsAction(SemanticsActions.OnClick)
        compose.waitAdvancingClock(stepMs = 50) { links() == both - PlaylistEpgSourceEntity(otherTv, otherGuide) }
        compose.waitForText("not used by any playlist", stepMs = 50)
    }
}
