package com.biskyt.mytvplayer.ui

import androidx.compose.ui.test.hasText
import com.biskyt.mytvplayer.data.db.ChannelProgramme
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.waitForText
import com.biskyt.mytvplayer.ui.common.ScheduleList
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A channel's guide (as over the player) shows every programme's description, not only the first ones' (the owner, 2026-11-15). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class ScheduleListUiTest {
    @get:Rule val compose = createUiComposeRule()

    @Test
    fun `every programme shows its description`() {
        val now = System.currentTimeMillis()
        val hour = 3_600_000L
        val programmes = (0 until 5).map { i ->
            ChannelProgramme(101, now + i * hour, now + (i + 1) * hour, "Show $i", null, "About show $i")
        }
        compose.setContent { MyTvTheme { ScheduleList(programmes) } }
        compose.waitForText("Show 4")
        val descriptions = (0 until 5).map { "About show $it" }
        assertEquals(descriptions, descriptions.filter { compose.onAllNodes(hasText(it)).fetchSemanticsNodes().isNotEmpty() })
    }
}
