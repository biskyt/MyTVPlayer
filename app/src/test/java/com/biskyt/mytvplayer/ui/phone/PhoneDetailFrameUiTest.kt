package com.biskyt.mytvplayer.ui.phone

import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.Navigators
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.addHomeTv
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.waitAdvancingClock
import com.biskyt.mytvplayer.testutil.waitForText
import com.biskyt.mytvplayer.ui.NavHost
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.ScreenContent
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
import com.biskyt.mytvplayer.ui.theme.Palette
import com.biskyt.mytvplayer.ui.theme.autoFindsTv
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** A film's and a series' page on a phone (the owner, 2026-11-25): the tabs, and no empty space for a missing backdrop. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "w390dp-h844dp-xxhdpi")
class PhoneDetailFrameUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val provider = FakeProvider()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var pid = 0L

    @Before
    fun setUp() = runBlocking {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_TOUCHSCREEN, true)
        autoFindsTv = { false } // the phone layout (TestApp sets the TV's)
        pid = app.container.addHomeTv(provider, vod = true)
    }

    @After
    fun tearDown() = provider.close()

    private fun show(screen: Screen) {
        val navigator = navigators.new(screen)
        compose.setContent {
            MyTvTheme {
                Box(Modifier.fillMaxSize().background(Palette.background)) { NavHost(remember { navigator }) { ScreenContent(it) } }
            }
        }
    }

    @Test
    fun `a film's page has the tabs`() {
        show(Screen.MovieDetails(pid, 501))
        compose.waitForText("Live TV", stepMs = 50)
    }

    @Test
    fun `a series' page has the tabs`() {
        show(Screen.SeriesDetails(pid, 77))
        compose.waitForText("Live TV", stepMs = 50)
    }

    @Test
    fun `when its backdrop does not load, the poster and title come up under the back button`() {
        // The provider gives a backdrop's address, which fails at once, and TMDB is off: with no backdrop that loads, the
        // space goes once the page's look-ups are done.
        show(Screen.MovieDetails(pid, 501))
        compose.waitForText("Dune", stepMs = 50)
        compose.waitAdvancingClock(stepMs = 50) { titleBottom() < px(240) }
        assertTrue(compose.onAllNodes(hasTestTag("backdrop")).fetchSemanticsNodes().isEmpty())
    }

    private fun px(dp: Int) = with(compose.density) { dp.dp.toPx() }

    /** Where the title ends, down the page: under the back button (72 high), or under a 230 high backdrop. */
    private fun titleBottom() = compose.onAllNodes(hasText("Dune"))[0].fetchSemanticsNode().boundsInRoot.bottom
}
