package com.biskyt.mytvplayer.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.data.LauncherTarget
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.addHomeTv
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.settle
import com.biskyt.mytvplayer.testutil.waitAdvancingClock
import com.biskyt.mytvplayer.testutil.waitForText
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

/** A card on the TV's home screen opens the app on its channel, film or episode, if it still may (see LauncherSync). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class LauncherOpenUiTest {
    @get:Rule val compose = createUiComposeRule<ComponentActivity>()

    private val provider = FakeProvider().apply { streamsTrickle = true }
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var pid = 0L

    @Before
    fun setUp() = runBlocking {
        pid = app.container.addHomeTv(provider)
        app.container.channels.setFavourite(pid, 102, true)
    }

    @After
    fun tearDown() = provider.close()

    private fun playerShown() =
        compose.onAllNodes(hasContentDescription("Skip back"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `a favourite channel's card opens the app on that channel`() {
        app.container.launcherTarget.value = LauncherTarget(LauncherTarget.CHANNEL, pid, 102)
        compose.setContent { AppRoot() }
        compose.waitAdvancingClock(50) { playerShown() }
        assertEquals("taken", null, app.container.launcherTarget.value)
    }

    @Test
    fun `a card for a channel no longer a favourite opens Home`() {
        app.container.launcherTarget.value = LauncherTarget(LauncherTarget.CHANNEL, pid, 103)
        compose.setContent { AppRoot() }
        compose.waitForText("Home", stepMs = 50)
        compose.waitAdvancingClock(50) { app.container.launcherTarget.value == null }
        compose.settle()
        assertTrue("no player", !playerShown())
        assertTrue(compose.onAllNodes(hasText("Home")).fetchSemanticsNodes().isNotEmpty())
    }
}
