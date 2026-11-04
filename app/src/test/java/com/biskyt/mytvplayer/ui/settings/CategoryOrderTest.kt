package com.biskyt.mytvplayer.ui.settings

import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.data.db.CAT_LIVE
import com.biskyt.mytvplayer.data.db.CAT_MOVIE
import com.biskyt.mytvplayer.data.db.CategoryEntity
import com.biskyt.mytvplayer.testutil.TestApp
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The groups on the Categories page: ticked ones first when the page opens, then they stay in place. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class)
class CategoryOrderTest {
    private val container = ApplicationProvider.getApplicationContext<TestApp>().container

    private fun cat(id: String, lang: String, included: Boolean, type: String = CAT_LIVE) =
        CategoryEntity(1, id, "$lang | $id", lang, included, id.toInt(), type = type)

    private fun CategoriesViewModel.order(list: List<CategoryEntity>, q: String = "") = groups(list, q).map { it.code }

    @Test
    fun `a tick does not move a group while the page is open, and ticked groups come first when it opens`() {
        val vm = CategoriesViewModel(container, 1)
        // France (ticked) first, then Germany and Spain in A to Z order.
        assertEquals(listOf("FR", "DE", "ES"), vm.order(listOf(cat("1", "DE", false), cat("2", "ES", false), cat("3", "FR", true))))
        // Spain ticked and France unticked: no group moves.
        val changed = listOf(cat("1", "DE", false), cat("2", "ES", true), cat("3", "FR", false))
        assertEquals(listOf("FR", "DE", "ES"), vm.order(changed))
        // A group that comes later goes at the end; another type has its own order.
        assertEquals(listOf("FR", "DE", "ES", "IT"), vm.order(changed + cat("4", "IT", true)))
        assertEquals(listOf("IT", "DE"), vm.order(listOf(cat("5", "DE", false, CAT_MOVIE), cat("6", "IT", true, CAT_MOVIE))))

        // The page opens again: Spain, now ticked, comes first, then France and Germany in A to Z order.
        assertEquals(listOf("ES", "FR", "DE"), CategoriesViewModel(container, 1).order(changed))
    }
}
