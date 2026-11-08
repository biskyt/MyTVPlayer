package com.biskyt.mytvplayer.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

/** Where channel up and down take focus in a list or grid (see pageKeys). */
class PageTargetTest {
    @Test
    fun `a list moves a page, and stops at its first and last rows`() {
        assertEquals(7, pageTarget(from = 0, rowSteps = 7, columns = 1, count = 40))
        assertEquals(39, pageTarget(from = 35, rowSteps = 7, columns = 1, count = 40))
        assertEquals(0, pageTarget(from = 3, rowSteps = -7, columns = 1, count = 40))
    }

    @Test
    fun `a grid moves whole rows in the same column`() {
        // 6 to a row: item 8 is in row 1, column 2.
        assertEquals(20, pageTarget(from = 8, rowSteps = 2, columns = 6, count = 40))
        assertEquals(2, pageTarget(from = 8, rowSteps = -3, columns = 6, count = 40))
    }

    @Test
    fun `in a short last row, the grid's last item`() {
        // 40 items: the last row (6) has items 36 to 39; column 5 has none there.
        assertEquals(39, pageTarget(from = 23, rowSteps = 5, columns = 6, count = 40))
        assertEquals(37, pageTarget(from = 1, rowSteps = 9, columns = 6, count = 40))
    }
}
