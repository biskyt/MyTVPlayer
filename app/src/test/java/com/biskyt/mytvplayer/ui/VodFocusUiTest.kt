package com.biskyt.mytvplayer.ui

import android.content.pm.PackageManager
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.data.db.VOD_EPISODE
import com.biskyt.mytvplayer.data.db.WatchProgressEntity
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.Navigators
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.addHomeTv
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.focusedText
import com.biskyt.mytvplayer.testutil.pressAndRead
import com.biskyt.mytvplayer.testutil.waitAdvancingClock
import com.biskyt.mytvplayer.testutil.waitForText
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
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

/**
 * Focus on the Series screens (the owner, 2026-11-06): the tab opens on a series, back from one is on it again, and
 * Right from a series page's buttons goes into a short episode list, at its first unplayed episode.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class VodFocusUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val provider = FakeProvider()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var playlistId = 0L

    @Before
    fun setUp() = runBlocking<Unit> {
        // Two series in the same UK category (the UK categories are chosen to start with).
        provider.series = listOf(Triple(77L, "UK - Line of Duty", "20"), Triple(79L, "UK - Bodyguard", "20"))
        playlistId = app.container.addHomeTv(provider, vod = true)
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
    }

    @After
    fun tearDown() = provider.close()

    private fun show(start: Screen): Navigator {
        val navigator = navigators.new(start)
        compose.setContent { MyTvTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
        return navigator
    }

    @Test
    fun `the series tab opens on a series, not a category`() {
        show(Screen.Series())
        compose.waitForText("Bodyguard", stepMs = 50)
        compose.waitAdvancingClock(stepMs = 50) { compose.focusedText().isNotEmpty() }
        assertTrue("Focus is on: ${compose.focusedText()}", "Line of Duty" in compose.focusedText())
    }

    @Test
    fun `back from a series, focus is on it again`() {
        val navigator = show(Screen.Series())
        compose.waitForText("Bodyguard", stepMs = 50)
        compose.waitAdvancingClock(stepMs = 50) { compose.focusedText().isNotEmpty() }
        repeat(MAX_KEYS) { if ("Bodyguard" !in compose.focusedText()) compose.pressAndRead(Key.DirectionRight) }
        assertTrue("Focus is on: ${compose.focusedText()}", "Bodyguard" in compose.focusedText())
        compose.pressAndRead(Key.DirectionCenter)
        compose.waitAdvancingClock(stepMs = 50) { navigator.current.screen is Screen.SeriesDetails }
        compose.waitAdvancingClock(stepMs = 50) { compose.focusedText().isNotEmpty() }

        compose.runOnUiThread { navigator.pop() }
        compose.waitAdvancingClock(stepMs = 50) { compose.focusedText().isNotEmpty() }
        assertTrue("Back on Series, focus is on: ${compose.focusedText()}", "Bodyguard" in compose.focusedText())
    }

    @Test
    fun `right from the series page's buttons goes to the first unplayed episode`() {
        // Series 1 has two episodes: the first watched to the end.
        runBlocking {
            app.container.vod.saveProgress(
                WatchProgressEntity(
                    playlistId, VOD_EPISODE, 9001, HOUR_MS, HOUR_MS, System.currentTimeMillis(), "Episode 1", null, 77, 1, 1
                )
            )
        }
        show(Screen.SeriesDetails(playlistId, 77))
        compose.waitAdvancingClock(stepMs = 50) { "Continue" in compose.focusedText() }
        compose.pressAndRead(Key.DirectionRight) // the heart
        val right = compose.pressAndRead(Key.DirectionRight)
        assertTrue("Right from the heart went to: $right", "Episode 2" in right)
    }

    private companion object {
        const val HOUR_MS = 3_600_000L
        const val MAX_KEYS = 8
    }
}
