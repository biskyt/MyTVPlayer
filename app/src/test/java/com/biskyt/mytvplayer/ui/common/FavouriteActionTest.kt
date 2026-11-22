package com.biskyt.mytvplayer.ui.common

import com.biskyt.mytvplayer.data.db.CAT_MOVIE
import com.biskyt.mytvplayer.data.db.CAT_SERIES
import com.biskyt.mytvplayer.testutil.PlainApp
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Every menu's favourite option has the heart, filled for a favourite: films' as series' (the owner, 2026-11-22). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = PlainApp::class)
class FavouriteActionTest {
    private fun heartOf(actions: List<ItemAction>) = (actions.last() as ItemAction.Run).icon

    @Test
    fun `films and series menus show the heart with their favourite option`() {
        assertEquals(AppIcons.HeartOutline, heartOf(vodActions(1, CAT_MOVIE, 501, favourite = false) {}))
        assertEquals(AppIcons.Heart, heartOf(vodActions(1, CAT_MOVIE, 501, favourite = true) {}))
        assertEquals(AppIcons.Heart, heartOf(vodActions(1, CAT_SERIES, 77, favourite = true) {}))
    }

    @Test
    fun `the option says what it does, and does it`() {
        var set: Boolean? = null
        val remove = favouriteAction(favourite = true) { set = it }
        assertEquals("Remove from favourites", remove.label)
        remove.run()
        assertEquals(false, set)
    }
}
