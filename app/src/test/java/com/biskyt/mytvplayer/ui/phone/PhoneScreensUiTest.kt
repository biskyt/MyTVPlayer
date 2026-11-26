package com.biskyt.mytvplayer.ui.phone

import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.FakeTmdb
import com.biskyt.mytvplayer.testutil.LocalMidday
import com.biskyt.mytvplayer.testutil.Navigators
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.addHomeTv
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.waitAdvancingClock
import com.biskyt.mytvplayer.testutil.waitForText
import com.biskyt.mytvplayer.ui.NavHost
import com.biskyt.mytvplayer.ui.Navigator
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.ScreenContent
import com.biskyt.mytvplayer.ui.common.Tab
import com.biskyt.mytvplayer.ui.hasPhoneLayout
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
import com.biskyt.mytvplayer.ui.theme.Palette
import com.biskyt.mytvplayer.ui.theme.autoFindsTv
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Search, a channel's catch-up and the pages fed from TMDB on a phone (the owner, 2026-11-26: "All screens should have
 * a phone layout"): a tap plays or opens, and the pages under a tab have a back button.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "w390dp-h844dp-xxhdpi")
class PhoneScreensUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    @get:Rule val midday = LocalMidday() // catch-up is filed by local day

    private val provider = FakeProvider()
    private val tmdb = FakeTmdb()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var pid = 0L
    private lateinit var navigator: Navigator

    @Before
    fun setUp() = runBlocking {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_TOUCHSCREEN, true)
        autoFindsTv = { false } // the phone layout (TestApp sets the TV's)
        // A second UK drama, which TMDB suggests for Line of Duty.
        provider.series += Triple(FakeTmdb.BODYGUARD, "UK - Bodyguard", "20")
        pid = app.container.addHomeTv(provider, vod = true)
    }

    @After
    fun tearDown() {
        provider.close()
        tmdb.close()
    }

    private fun show(vararg screens: Screen) {
        navigator = navigators.new(screens.first())
        screens.drop(1).forEach(navigator::push)
        compose.setContent {
            MyTvTheme {
                Box(Modifier.fillMaxSize().background(Palette.background)) { NavHost(remember { navigator }) { ScreenContent(it) } }
            }
        }
    }

    private fun tap(text: String) = compose.onAllNodes(hasText(text) and hasClickAction())[0].performClick()

    private fun tapBack() = compose.onNode(hasContentDescription("Back") and hasClickAction()).performClick()

    @Test
    fun `every screen has a phone layout`() {
        val screens = listOf(
            Screen.Search("du"),
            Screen.Live(guide = true),
            Screen.Catchup(pid, 101),
            Screen.Person(pid, FakeTmdb.PERSON, "Timothée Chalamet", null, null, "Dune", Tab.MOVIES),
            Screen.MoreLike(pid, 31917, series = true, "Line of Duty", Tab.SERIES),
            Screen.TmdbTitle(pid, FakeTmdb.HAPPY_VALLEY, series = true, "Happy Valley", null, Tab.SERIES),
        )
        screens.forEach { assertTrue(it.toString(), it.hasPhoneLayout) }
    }

    @Test
    fun `a search finds films, and a tap opens one`() {
        show(Screen.Search("du"))
        compose.waitForText("Dune", stepMs = 50)
        compose.waitForText("UK | MOVIES", stepMs = 50)
        tap("Dune")
        compose.waitAdvancingClock { navigator.current.screen is Screen.MovieDetails }
    }

    @Test
    fun `a search finds channels, and a tap plays one`() {
        show(Screen.Search("channel one"))
        compose.waitForText("UK: CHANNEL ONE HD", stepMs = 50)
        tap("UK: CHANNEL ONE HD")
        compose.waitAdvancingClock { navigator.current.screen is Screen.Player }
    }

    @Test
    fun `a channel's catch-up shows the days as chips, and a tap plays a programme`() {
        show(Screen.Live(), Screen.Catchup(pid, 101))
        compose.waitForText("Earlier Today", stepMs = 50)
        assertTrue(compose.onAllNodes(hasText("Yesterday") and hasClickAction()).fetchSemanticsNodes().isNotEmpty())
        tap("Earlier Today")
        compose.waitAdvancingClock { navigator.current.screen is Screen.CatchupPlayer }
    }

    @Test
    fun `Back on a channel's catch-up goes back`() {
        show(Screen.Live(), Screen.Catchup(pid, 101))
        compose.waitForText("Earlier Today", stepMs = 50)
        tapBack()
        compose.waitAdvancingClock { navigator.current.screen is Screen.Live }
    }

    @Test
    fun `More like this opens a title's TMDB page, which finds it in the playlist`() {
        tmdb.use(app.container)
        show(Screen.MoreLike(pid, 31917, series = true, "Line of Duty", Tab.SERIES))
        compose.waitForText("More like Line of Duty", stepMs = 50)
        compose.waitForText("Happy Valley", stepMs = 50)
        tap("Happy Valley")
        compose.waitAdvancingClock { navigator.current.screen is Screen.TmdbTitle }
        compose.waitForText("A sergeant in West Yorkshire.", stepMs = 50)
        tap("Find in your playlist")
        compose.waitAdvancingClock { navigator.current.screen is Screen.Search }
        assertEquals(Screen.Search("Happy Valley"), navigator.current.screen)
    }

    @Test
    fun `a person's page shows their biography, titles and credits`() {
        tmdb.use(app.container)
        show(Screen.Person(pid, FakeTmdb.PERSON, "Timothée Chalamet", null, "Paul Atreides", "Dune", Tab.MOVIES))
        compose.waitForText("An American actor.", stepMs = 50)
        compose.waitForText("In your library", stepMs = 50)
        // The page's own list is the first that scrolls (the rows of posters in it scroll too).
        compose.onAllNodes(hasScrollToNodeAction())[0].performScrollToNode(hasText("All credits"))
        compose.waitForText("All credits", stepMs = 50)
    }
}
