package com.biskyt.mytvplayer.ui.phone

import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.addHomeTv
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.waitForText
import com.biskyt.mytvplayer.ui.AppRoot
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

/** A message in the corner of a phone's screen stays clear of the tab bar along the bottom (the owner, 2026-11-25). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "w390dp-h844dp-xxhdpi")
class PhoneToastUiTest {
    @get:Rule val compose = createUiComposeRule<ComponentActivity>()

    private val provider = FakeProvider()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var pid = 0L

    @Before
    fun setUp() = runBlocking<Unit> {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_TOUCHSCREEN, true)
        autoFindsTv = { false } // the phone layout (TestApp sets the TV's)
        pid = app.container.addHomeTv(provider)
    }

    @After
    fun tearDown() = provider.close()

    @Test
    fun `a failed update on Home is above the tab bar`() {
        compose.setContent { AppRoot() }
        compose.waitForText("Live TV")
        // The provider is gone: the update fails, and says so in the corner.
        provider.close()
        runBlocking { app.container.sync.refreshNow(pid) }
        compose.waitForText("The last update failed")
        val toast = compose.onNode(hasText("The last update failed")).fetchSemanticsNode().boundsInRoot
        val bar = compose.onNode(hasText("Live TV") and hasClickAction()).fetchSemanticsNode().boundsInRoot
        val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("$toast over the tab bar ($bar, screen $screen)", toast.bottom <= bar.top)
    }
}
