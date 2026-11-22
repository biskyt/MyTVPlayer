package com.biskyt.mytvplayer.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.data.db.CAT_MOVIE
import com.biskyt.mytvplayer.data.db.VOD_MOVIE
import com.biskyt.mytvplayer.data.db.WatchProgressEntity
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.FakeTmdb
import com.biskyt.mytvplayer.testutil.Navigators
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.addHomeTv
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.waitAdvancingClock
import com.biskyt.mytvplayer.testutil.waitForText
import com.biskyt.mytvplayer.ui.common.ItemAction
import com.biskyt.mytvplayer.ui.player.VodPlayerViewModel
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Home's cards offer what the title's page's More… offers from TMDB (the owner, 2026-11-22): cast and crew, More like
 * this and About, opening the same panels.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class TitleExtrasUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val provider = FakeProvider()
    private val tmdb = FakeTmdb()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var pid = 0L

    @Before
    fun setUp() = runBlocking {
        pid = app.container.addHomeTv(provider, vod = true)
        tmdb.use(app.container)
    }

    @After
    fun tearDown() {
        provider.close()
        tmdb.close()
    }

    private fun home() {
        val navigator = navigators.new(Screen.Home)
        compose.setContent { MyTvTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
    }

    private fun options(card: String) {
        compose.waitForText(card, stepMs = 50)
        val node = compose.onAllNodes(hasText(card, substring = true) and hasClickAction())[0]
        node.performSemanticsAction(SemanticsActions.RequestFocus)
        node.performSemanticsAction(SemanticsActions.OnLongClick)
    }

    private fun click(text: String) =
        compose.onAllNodes(hasText(text) and hasClickAction())[0].performSemanticsAction(SemanticsActions.OnClick)

    @Test
    fun `a Continue watching film offers its cast and crew, which opens the cast`() {
        runBlocking {
            app.container.vod.saveProgress(
                WatchProgressEntity(
                    pid,
                    VOD_MOVIE,
                    501,
                    positionMs = 600_000,
                    durationMs = 6_000_000,
                    updatedAt = System.currentTimeMillis(),
                    title = "Dune",
                    imageUrl = null
                )
            )
        }
        home()
        options("Dune")
        compose.waitForText("Resume", stepMs = 50)
        compose.waitForText("Cast and crew", stepMs = 50)
        compose.waitForText("More like this", stepMs = 50)
        click("Cast and crew")
        compose.waitForText("Timothée Chalamet", stepMs = 50)
    }

    @Test
    fun `a favourite film offers About this film`() {
        runBlocking { app.container.vod.setFavourite(pid, CAT_MOVIE, 501, true) }
        home()
        options("Dune")
        compose.waitForText("Remove from favourites", stepMs = 50)
        compose.waitForText("About this film", stepMs = 50)
        click("About this film")
        compose.waitForText("Legendary Pictures", stepMs = 50)
    }

    @Test
    fun `the player's menu offers the film's cast and crew, More like this and About`() {
        val navigator = navigators.new(Screen.VodPlayer(pid, 501))
        compose.setContent { MyTvTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
        val vm = ViewModelProvider(navigator.current)[VodPlayerViewModel::class.java]
        compose.waitAdvancingClock { vm.menuActions().any { it.label == "Cast and crew" } }
        val labels = vm.menuActions().map { it.label }
        assertTrue(labels.toString(), labels.containsAll(listOf("More like this", "About this film")))
        // Cast and crew opens its panel over the player.
        compose.runOnUiThread { (vm.menuActions().first { it.label == "Cast and crew" } as ItemAction.Run).run() }
        compose.waitForText("Timothée Chalamet", stepMs = 50)
    }
}
