package com.biskyt.mytvplayer.ui

import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.addHomeTv
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.settle
import com.biskyt.mytvplayer.testutil.waitAdvancingClock
import com.biskyt.mytvplayer.testutil.waitForText
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** An activity that records a call to [finishAndRemoveTask]: closing the app and taking it off the recent apps list. */
class BackRecordingActivity : ComponentActivity() {
    var removedTask = false

    override fun finishAndRemoveTask() {
        removedTask = true
        super.finishAndRemoveTask()
    }
}

/** Back on the home screen closes the app and takes it off the recent apps; on another screen it goes to Home first. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class BackFromHomeTest {
    private val provider = FakeProvider()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()

    init {
        // The test activity is not in the app's manifest: the rule below can only start a known activity.
        shadowOf(app.packageManager).addOrUpdateActivity(
            ActivityInfo().apply {
                name = BackRecordingActivity::class.java.name
                packageName = app.packageName
            }
        )
    }

    @get:Rule val compose = createUiComposeRule<BackRecordingActivity>()

    @Before
    fun setUp() = runBlocking {
        app.container.addHomeTv(provider)
        Unit
    }

    @After
    fun tearDown() = provider.close()

    private fun back() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

    private fun homeShown() = compose.onAllNodes(hasText(HOME)).fetchSemanticsNodes().isNotEmpty()

    private fun openHome() {
        compose.setContent { AppRoot() }
        compose.waitForText(HOME, stepMs = 50)
    }

    /** Back on the home screen: the app closes, and takes itself off the recent apps list. */
    private fun assertBackCloses() {
        back()
        compose.waitAdvancingClock(stepMs = 50) { compose.activity.removedTask }
        assertTrue(compose.activity.isFinishing)
    }

    @Test
    fun `back on the home screen closes the app and takes it off the recent apps`() {
        openHome()
        assertBackCloses()
    }

    @Test
    fun `back on another screen goes to the home screen first`() {
        openHome()
        compose.onNode(hasText("Live TV") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForText("UK: CHANNEL ONE HD", stepMs = 50)
        // The first Back moves the focus to the list's filter box; the second goes to the home screen.
        back()
        compose.settle()
        assertFalse(homeShown())
        back()
        compose.waitForText(HOME, stepMs = 50)
        assertFalse(compose.activity.isFinishing)
        assertBackCloses()
    }

    private companion object {
        /** Text that only the home screen shows. */
        const val HOME = "Your channels, on now"
    }
}
