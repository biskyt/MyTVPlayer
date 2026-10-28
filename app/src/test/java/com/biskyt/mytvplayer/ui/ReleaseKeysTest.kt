package com.biskyt.mytvplayer.ui

import android.view.KeyEvent
import androidx.test.core.app.ActivityScenario
import com.biskyt.mytvplayer.MainActivity
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.createUiEmptyComposeRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The release of an Options, OK or Guide press that a panel took (a window of its own, closed by the press) stays in the
 * app: the TV would act on it (Options: its own menu). Other releases are the TV's as ever.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class ReleaseKeysTest {
    @get:Rule val compose = createUiEmptyComposeRule()

    private fun up(code: Int) = KeyEvent(KeyEvent.ACTION_UP, code)

    @Test
    fun `a release whose press went to a panel is kept from the tv`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue("options", activity.dispatchKeyEvent(up(KeyEvent.KEYCODE_MENU)))
                // Held on after the panel closed: its repeats too.
                val repeat = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MENU, 1)
                assertTrue("options held", activity.dispatchKeyEvent(repeat))
                assertTrue("guide", activity.dispatchKeyEvent(up(KeyEvent.KEYCODE_GUIDE)))
                assertFalse("not the arrow keys", activity.dispatchKeyEvent(up(KeyEvent.KEYCODE_DPAD_LEFT)))
                // A press begun here whose release went to a panel (it opened one, which took the keys): the next
                // press a panel takes still keeps its release from the TV.
                activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MENU))
                activity.onWindowFocusChanged(false)
                assertTrue("options again", activity.dispatchKeyEvent(up(KeyEvent.KEYCODE_MENU)))
            }
        }
    }
}
