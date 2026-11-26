package com.biskyt.mytvplayer.ui.phone

import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.data.ChannelFilter
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
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.ScreenContent
import com.biskyt.mytvplayer.ui.common.Tab
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
import com.biskyt.mytvplayer.ui.theme.Palette
import com.biskyt.mytvplayer.ui.theme.autoFindsTv
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Search, the TV guide, catch-up and the pages fed from TMDB on a phone held upright and unfolded, recorded to look at. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "w390dp-h844dp-xxhdpi")
class PhoneScreensScreenshotTest {
    @get:Rule val compose = createUiComposeRule<ComponentActivity>()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    @get:Rule val midday = LocalMidday() // catch-up is filed by local day

    private val provider = FakeProvider()
    private val tmdb = FakeTmdb()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var pid = 0L

    @Before
    fun setUp() = runBlocking {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_TOUCHSCREEN, true)
        autoFindsTv = { false } // the phone layout (TestApp sets the TV's)
        pid = app.container.addHomeTv(provider, vod = true, guide = true)
        tmdb.use(app.container)
    }

    @After
    fun tearDown() {
        provider.close()
        tmdb.close()
    }

    /** Each page in turn, recorded as screenshots/<prefix>-<page>.png. */
    private fun pages(prefix: String) {
        val pages = listOf(
            Triple("search", Screen.Search("du"), "UK | MOVIES"),
            Triple("guide", Screen.Live(ChannelFilter.All, guide = true), "Now on one.uk"),
            Triple("catchup", Screen.Catchup(pid, 101), "Earlier Today"),
            Triple("more-like", Screen.MoreLike(pid, 31917, series = true, "Line of Duty", Tab.SERIES), "Happy Valley"),
            Triple(
                "tmdb-title",
                Screen.TmdbTitle(pid, FakeTmdb.HAPPY_VALLEY, series = true, "Happy Valley", null, Tab.SERIES),
                "A sergeant in West Yorkshire."
            ),
            Triple(
                "person",
                Screen.Person(pid, FakeTmdb.PERSON, "Timothée Chalamet", null, "Paul Atreides", "Dune", Tab.MOVIES),
                "In your library"
            )
        )
        val navigator = navigators.new(Screen.Home)
        compose.setContent {
            MyTvTheme {
                Box(Modifier.fillMaxSize().background(Palette.background)) { NavHost(remember { navigator }) { ScreenContent(it) } }
            }
        }
        // Each page once its [shows] text is there: TMDB and catch-up answer on another thread.
        for ((name, screen, shows) in pages) {
            compose.runOnUiThread { navigator.reset(screen) }
            compose.waitForText(shows, stepMs = 50)
            repeat(PAUSES) { compose.waitAdvancingClock(stepMs = 100) { true } }
            compose.onRoot().captureRoboImage("screenshots/$prefix-$name.png")
        }
    }

    @Test
    fun `the pages on a phone`() = pages("phone")

    @Test
    @Config(qualifiers = "w840dp-h720dp-xhdpi")
    fun `the pages unfolded`() = pages("unfolded")

    private companion object {
        /** Enough for each page to load from the database and the stand-in TMDB. */
        const val PAUSES = 20
    }
}
