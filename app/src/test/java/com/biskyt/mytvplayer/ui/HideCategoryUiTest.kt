package com.biskyt.mytvplayer.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.data.ChannelFilter
import com.biskyt.mytvplayer.data.db.CAT_LIVE
import com.biskyt.mytvplayer.data.db.CategoryEntity
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.Navigators
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.addHomeTv
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.focusedText
import com.biskyt.mytvplayer.testutil.screen
import com.biskyt.mytvplayer.testutil.waitAdvancingClock
import com.biskyt.mytvplayer.testutil.waitForText
import com.biskyt.mytvplayer.ui.common.CategoryColumn
import com.biskyt.mytvplayer.ui.common.categoryEntries
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Hiding a category from the column keeps the place: the one after it (or before, for the last) is chosen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class HideCategoryUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val provider = FakeProvider()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var playlistId = 0L

    /** The playlist's live categories, as the column lists them. */
    private lateinit var shown: List<String>

    @Before
    fun setUp() = runBlocking {
        playlistId = app.container.addHomeTv(provider)
        shown = app.container.db.categories().observeIncluded(playlistId, CAT_LIVE).first().map { it.categoryId }
    }

    @After
    fun tearDown() = provider.close()

    private fun label(id: String) = provider.categories.first { it.first == id }.second

    /** Opens Live TV on [id] and hides it from its options in the column. */
    private fun hide(id: String) {
        val navigator = navigators.new(Screen.Live(ChannelFilter.Category(id)))
        compose.setContent { MyTvTheme { NavHost(remember { navigator }) { ScreenContent(it) } } }
        hideFromColumn(id)
    }

    /** Hides [id] from its options in the column. */
    private fun hideFromColumn(id: String) {
        compose.waitForText(label(id), stepMs = 50)
        compose.onAllNodes(hasText(label(id)) and hasClickAction())[0].performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForText("Hide category", stepMs = 50)
        compose.onNode(hasText("Hide category")).performSemanticsAction(SemanticsActions.OnClick)
    }

    /** [hidden] has gone, and [label] is the category shown (in the column and above its list), with focus. */
    private fun chosen(label: String, hidden: String, shownTwice: Boolean = true) = waitFor(
        "hidden gone" to { compose.onAllNodes(hasText(label(hidden))).fetchSemanticsNodes().isEmpty() },
        "'$label' chosen" to { compose.onAllNodes(hasText(label)).fetchSemanticsNodes().size >= if (shownTwice) 2 else 1 },
        "focused" to { compose.focusedText().contains(label) }
    )

    /** Waits for all of [checks]; on a timeout, says which never happened and where focus is. */
    private fun waitFor(vararg checks: Pair<String, () -> Boolean>) = try {
        compose.waitAdvancingClock(stepMs = 50) { checks.all { it.second() } }
    } catch (e: ComposeTimeoutException) {
        val state = checks.joinToString { (name, check) -> "$name: ${check()}" }
        throw AssertionError("$state (focus on '${compose.focusedText()}'). Screen:\n${compose.screen()}", e)
    }

    @Test
    fun `hiding a category chooses the one after it`() {
        hide(shown.first())
        chosen(label(shown[1]), hidden = shown.first())
    }

    @Test
    fun `hiding the last category chooses the one before it`() {
        hide(shown.last())
        chosen(label(shown[shown.size - 2]), hidden = shown.last())
    }

    @Test
    fun `focus waits for a screen slow to choose the next category`() {
        val categories = shown.mapIndexed { i, id -> CategoryEntity(playlistId, id, label(id), null, true, i) }
        val selected = MutableStateFlow(shown.first())
        val listed = MutableStateFlow(categories)
        compose.setContent {
            CompositionLocalProvider(LocalNavigator provides navigators.new(Screen.Home)) {
                MyTvTheme {
                    val scope = rememberCoroutineScope()

                    // A screen whose choice and list arrive frames later (the app's come through flows it collects).
                    fun later(change: () -> Unit) = scope.launch {
                        repeat(2) { withFrameNanos { } }
                        change()
                    }
                    val entries = categoryEntries(listed.collectAsState().value) { it.categoryId }
                    CategoryColumn(
                        entries,
                        selected.collectAsState().value,
                        onFocus = {},
                        onSelect = { f -> later { selected.value = f } },
                        onHide = { c -> later { listed.value = listed.value - c } }
                    )
                }
            }
        }
        hideFromColumn(shown.first())
        // Only the column: this screen has no list above.
        chosen(label(shown[1]), hidden = shown.first(), shownTwice = false)
    }
}
