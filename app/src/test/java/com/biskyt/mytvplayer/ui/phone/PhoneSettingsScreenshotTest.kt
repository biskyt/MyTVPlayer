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

/** The settings pages on a phone held upright and on an unfolded foldable, recorded to look at. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "w390dp-h844dp-xxhdpi")
class PhoneSettingsScreenshotTest {
    @get:Rule val compose = createUiComposeRule<ComponentActivity>()

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

    /** Each settings page in turn, recorded as screenshots/<prefix>-settings-<page>.png. */
    private fun pages(prefix: String) {
        val pages = listOf(
            "main" to Screen.Settings,
            "playlists" to Screen.Playlists,
            "playlist-edit" to Screen.PlaylistEdit(pid),
            "categories" to Screen.Categories(pid),
            "playback" to Screen.PlaybackSettings,
            "appearance" to Screen.Appearance,
            "guide-sources" to Screen.GuideSources,
            "guide-matching" to Screen.GuideMatching(pid),
            "backup" to Screen.Backup,
            "about" to Screen.About,
            "welcome" to Screen.Welcome
        )
        val navigator = navigators.new(Screen.Settings)
        compose.setContent {
            MyTvTheme {
                Box(Modifier.fillMaxSize().background(Palette.background)) { NavHost(remember { navigator }) { ScreenContent(it) } }
            }
        }
        for ((name, screen) in pages) {
            compose.runOnUiThread { navigator.reset(screen) }
            // Playback waits for the Dolby Digital library, which loads on another thread.
            if (screen == Screen.PlaybackSettings) compose.waitForText("Skip back", stepMs = 50)
            repeat(PAUSES) { compose.waitAdvancingClock(stepMs = 100) { true } }
            compose.onRoot().captureRoboImage("screenshots/$prefix-settings-$name.png")
        }
    }

    @Test
    fun `the settings pages on a phone`() = pages("phone")

    @Test
    @Config(qualifiers = "w840dp-h720dp-xhdpi")
    fun `the settings pages unfolded`() = pages("unfolded")

    private companion object {
        /** Enough for each page's lists to load from the database. */
        const val PAUSES = 20
    }
}
