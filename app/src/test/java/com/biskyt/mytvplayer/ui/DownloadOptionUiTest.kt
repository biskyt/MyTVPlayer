package com.biskyt.mytvplayer.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.core.vod.DownloadNames
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.Navigators
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.addHomeTv
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.waitAdvancingClock
import com.biskyt.mytvplayer.testutil.waitForText
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** With the setting Downloads on, a film's More… offers Download, which starts it (the owner, 2026-11-22). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class DownloadOptionUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val provider = FakeProvider()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var pid = 0L
    private val started = mutableListOf<Pair<String, DownloadNames.Place>>()

    @Before
    fun setUp() = runBlocking {
        pid = app.container.addHomeTv(provider, vod = true)
        app.container.startDownload = { _, url, place -> started += url to place }
    }

    @After
    fun tearDown() = provider.close()

    private fun more() {
        val navigator = navigators.new(Screen.MovieDetails(pid, 501))
        compose.setContent { MyTvTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
        compose.waitForText("More…")
        compose.onAllNodes(hasText("More…") and hasClickAction())[0].performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForText("Add to favourites")
    }

    private fun shown(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `off, there is no Download`() {
        more()
        assertTrue(!shown("Download"))
    }

    @Test
    fun `on, Download starts the film's download, to its own folder`() {
        runBlocking { app.container.settings.update { it.copy(downloads = true) } }
        more()
        compose.waitForText("Download")
        compose.onAllNodes(hasText("Download") and hasClickAction())[0].performSemanticsAction(SemanticsActions.OnClick)
        val m = runBlocking { app.container.vod.movie(pid, 501)!! }
        val place = DownloadNames.movie(m.title, m.year, m.extension)
        compose.waitAdvancingClock { started.isNotEmpty() }
        assertEquals(place, started.single().second)
        assertTrue(started.single().first, started.single().first.contains("/movie/me/secret/501."))
    }
}
