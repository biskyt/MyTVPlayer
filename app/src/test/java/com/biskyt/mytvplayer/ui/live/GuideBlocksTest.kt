package com.biskyt.mytvplayer.ui.live

import com.biskyt.mytvplayer.data.db.ChannelProgramme
import org.junit.Assert.assertEquals
import org.junit.Test

/** Where the TV and phone guides draw a row's programmes (MyPlexPlayer's review of 1.40.0). */
class GuideBlocksTest {
    private fun p(start: Int, stop: Int) = ChannelProgramme(1, start * MIN, stop * MIN, "$start", null, null)

    private fun drawn(vararg programmes: ChannelProgramme) =
        blocks(programmes.toList(), 0, 240 * MIN).map { Triple(it.programme.title, it.from / MIN, it.to / MIN) }

    @Test
    fun `programmes in turn are drawn where they are on`() {
        assertEquals(listOf(Triple("0", 0L, 60L), Triple("90", 90L, 120L)), drawn(p(0, 60), p(90, 120)))
    }

    @Test
    fun `an overlap does not move the programmes after it`() {
        // 0:00 to 1:00 and 0:30 to 1:30 overlap: the one at 1:30 stays at 1:30.
        assertEquals(
            listOf(Triple("0", 0L, 60L), Triple("30", 60L, 90L), Triple("90", 90L, 120L)),
            drawn(p(0, 60), p(30, 90), p(90, 120))
        )
    }

    @Test
    fun `a programme inside the one before it, or outside the window, is not drawn`() {
        assertEquals(listOf(Triple("0", 0L, 120L)), drawn(p(-30, -10), p(0, 120), p(30, 60), p(250, 300)))
    }

    @Test
    fun `a programme that is not drawn highlights the one it is inside`() {
        val drawn = blocks(listOf(p(0, 120), p(30, 60), p(120, 180)), 0, 240 * MIN)
        assertEquals(listOf(0, 0, 2), (0..2).map { drawnCell(drawn, it) })
    }

    @Test
    fun `after the last programme, the highlight is on the drawn block, not one inside it`() {
        // 0:00 to 2:00, with 0:30 to 1:00 inside it and last: at 2:30, after both, the highlight is on the one that ends
        // last, which is drawn, not on the last in the list, which is not (MyPlexPlayer's review of 1.40.1).
        val visible = listOf(p(0, 120), p(30, 60))
        assertEquals(0, cellAt(visible, 150 * MIN))
        // Programmes in turn: still the last one.
        assertEquals(1, cellAt(listOf(p(0, 60), p(60, 90)), 150 * MIN))
    }

    private companion object {
        const val MIN = 60_000L
    }
}
