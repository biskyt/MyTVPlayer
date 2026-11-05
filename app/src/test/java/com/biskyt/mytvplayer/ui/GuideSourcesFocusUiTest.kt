package com.biskyt.mytvplayer.ui

import android.content.pm.PackageManager
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.test.core.app.ApplicationProvider
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
 * Several guides, most of them off (the owner's set-up, 2026-11-05), on a screen too short for them all: the remote's
 * up key from the bottom of the list stops on each guide in turn, the top one too, before the buttons above them. The
 * top one was skipped: scrolled back into view, the "Add a guide" button took focus again as if the screen had just
 * opened (see rememberFirstFocus).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h360dp-tvdpi")
class GuideSourcesFocusUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val provider = FakeProvider()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()

    @Before
    fun setUp() = runBlocking<Unit> {
        val c = app.container
        // The provider's guide at the top, then five added guides: the first on and the others off.
        val home = c.addHomeTv(provider)
        (0..4).forEach { n ->
            val id = c.epg.addSource("Extra guide $n", "${provider.url}extra$n.xml", home)
            if (n > 0) c.epg.setSourceOn(home, checkNotNull(c.db.epg().source(id)), false)
        }
    }

    @After
    fun tearDown() = provider.close()

    @Test
    fun `up from the bottom stops on every guide, the top one too`() {
        // A TV: Compose then scrolls lists as on one (the focused item about a third of the way down).
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
        val navigator = navigators.new(Screen.GuideSources)
        compose.setContent { MyTvTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
        compose.waitForText("Extra guide 0", stepMs = 50)
        compose.waitAdvancingClock(stepMs = 50) { compose.focusedText().isNotEmpty() }

        // Down to the bottom: until focus stays where it is.
        var last = ""
        repeat(MAX_KEYS) { compose.pressAndRead(Key.DirectionDown).let { if (it == last) return@repeat else last = it } }
        assertTrue("At the bottom: $last", "Extra guide 4" in last)

        // Then up, noting each stop, to the buttons.
        val stops = mutableListOf<String>()
        repeat(MAX_KEYS) {
            val now = compose.pressAndRead(Key.DirectionUp)
            if (stops.lastOrNull() == now) return@repeat
            stops += now
        }
        val guides = stops.takeWhile { "Add a guide" !in it }
        assertTrue("Up from the bottom: $stops", listOf("Home TV guide", "Extra guide 0").all { name -> guides.any { name in it } })
    }

    private companion object {
        const val MAX_KEYS = 12
    }
}
