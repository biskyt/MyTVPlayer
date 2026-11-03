package com.biskyt.mytvplayer.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.data.ChannelFilter
import com.biskyt.mytvplayer.data.db.CAT_LIVE
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Parental controls on screen: a locked category asks for the PIN, and so does the Parental controls page. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class ParentalUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val provider = FakeProvider()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var playlistId = 0L

    @Before
    fun setUp() = runBlocking {
        val c = app.container
        playlistId = c.addHomeTv(provider)
        c.parental.setRestricted(playlistId, CAT_LIVE, "1", true) // Entertainment: channels 101 and 103
        c.parental.setPin("1234")
        c.parental.lock()
    }

    @After
    fun tearDown() = provider.close()

    private fun show(start: Screen) {
        val navigator = navigators.new(start)
        compose.setContent { MyTvTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
    }

    private fun waitFor(text: String) = compose.waitForText(text, stepMs = 50)

    private fun shown(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    /** Types [pin] on the PIN panel's number pad. */
    private fun typePin(pin: String, title: String = "Enter PIN") {
        waitFor(title)
        pin.forEach { d ->
            compose.onNode(hasText(d.toString()) and hasClickAction() and hasAnyAncestor(isDialog()))
                .performSemanticsAction(SemanticsActions.OnClick)
        }
    }

    @Test
    fun `a restricted category asks for the PIN, then shows its channels`() {
        show(Screen.Live(ChannelFilter.Category("1")))
        waitFor("This category is restricted.")
        assertFalse(shown("UK: CHANNEL ONE HD"))

        compose.onNode(hasText("Enter PIN") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        typePin("1111")
        waitFor("Wrong PIN. Try again.")
        typePin("1234")
        waitFor("UK: CHANNEL ONE HD")
        compose.waitAdvancingClock { !shown("This category is restricted.") }
    }

    @Test
    fun `parental controls ask for the PIN when one is set`() {
        show(Screen.Settings)
        waitFor("Playlists")
        // Below the first screenful.
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Parental controls"))
        waitFor("Parental controls")
        compose.onNode(hasText("Parental controls") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        typePin("1234")
        waitFor("Hide restricted channels from Recently watched")
        assertTrue(shown("Change PIN"))
    }

    @Test
    fun `a new PIN is typed twice, and must be the same both times`() {
        runBlocking { app.container.parental.setPin("") }
        show(Screen.Parental)
        waitFor("Set PIN")
        compose.onNode(hasText("Set PIN") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        typePin("2468", title = "Choose a 4-digit PIN")
        typePin("1357", title = "Enter the new PIN again")
        waitFor("The PINs are different. Choose a PIN again.")
        typePin("2468", title = "Choose a 4-digit PIN")
        typePin("2468", title = "Enter the new PIN again")
        compose.waitAdvancingClock {
            runBlocking { com.biskyt.mytvplayer.data.Pin.matches("2468", app.container.settings.current().pinHash) }
        }
        waitFor("Change PIN")
    }
}
