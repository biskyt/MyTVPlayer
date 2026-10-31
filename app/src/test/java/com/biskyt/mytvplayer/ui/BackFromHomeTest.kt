package com.biskyt.mytvplayer.ui

import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.addHomeTv
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.waitAdvancingClock
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Back on the home screen closes the app; on another screen it goes back first. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class BackFromHomeTest {
    @get:Rule val compose = createUiComposeRule<ComponentActivity>()

    private val provider = FakeProvider()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()

    @Before
    fun setUp() = runBlocking {
        app.container.addHomeTv(provider)
        Unit
    }

    @After
    fun tearDown() = provider.close()

    @Test
    fun `back on the home screen closes the app`() {
        compose.setContent { AppRoot() }
        compose.waitAdvancingClock { compose.onAllNodes(androidx.compose.ui.test.hasText("Live TV")).fetchSemanticsNodes().isNotEmpty() }
        assertFalse(compose.activity.isFinishing)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitAdvancingClock { compose.activity.isFinishing }
        assertTrue(compose.activity.isFinishing)
    }
}
