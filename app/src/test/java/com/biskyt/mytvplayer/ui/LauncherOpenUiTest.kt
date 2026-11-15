package com.biskyt.mytvplayer.ui

import android.content.ComponentName
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.MainActivity
import com.biskyt.mytvplayer.data.LauncherTarget
import com.biskyt.mytvplayer.data.VodFilter
import com.biskyt.mytvplayer.data.db.CAT_MOVIE
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A card on the TV's home screen opens the app on its channel, film, episode or page, or a row's list, if it still may
 * (see LauncherSync). Another app can also open the TV guide.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class LauncherOpenUiTest {
    @get:Rule val compose = createUiComposeRule<ComponentActivity>()

    private val provider = FakeProvider().apply { streamsTrickle = true }
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var pid = 0L

    @Before
    fun setUp() = runBlocking {
        pid = app.container.addHomeTv(provider, vod = true)
        app.container.channels.setFavourite(pid, 102, true)
        app.container.vod.setFavourite(pid, CAT_MOVIE, 701, true)
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

    /** The screens open now, from the first. */
    private fun screens() = ViewModelProvider(compose.activity)[RootViewModel::class.java].navigator?.stack?.map { it.screen }

    private fun opens(target: LauncherTarget, vararg screens: Screen) {
        app.container.launcherTarget.value = target
        shows(*screens)
    }

    private fun shows(vararg screens: Screen) {
        compose.setContent { AppRoot() }
        compose.waitAdvancingClock(50) { screens() == screens.toList() }
    }

    @Test
    fun `a favourite film's card opens its page over Home`() =
        opens(LauncherTarget(LauncherTarget.MOVIE_PAGE, pid, 701), Screen.Home, Screen.MovieDetails(pid, 701))

    @Test
    fun `the favourite movies' row opens their list`() =
        opens(LauncherTarget(LauncherTarget.FAVOURITE_MOVIES, pid), Screen.Movies(VodFilter.Favourites))

    @Test
    fun `another app opens the TV guide over Home, as the remote's Guide button there`() {
        // It finds the guide by its action, and opens it by that or by its name.
        val action = Intent("${app.packageName}.action.TV_GUIDE")
        val entry = app.packageManager.queryIntentActivities(action, 0).single().activityInfo
        assertEquals(MainActivity.GUIDE_ENTRY, entry.name)
        assertTrue(entry.exported)
        assertTrue(MainActivity.asksForGuide(app, action))
        assertTrue(MainActivity.asksForGuide(app, Intent().setComponent(ComponentName(app.packageName, entry.name))))
        assertFalse(MainActivity.asksForGuide(app, Intent(app, MainActivity::class.java)))

        app.container.openGuide.value = true
        shows(Screen.Home, Screen.Live(guide = true))
        assertFalse("taken", app.container.openGuide.value)
    }
}
