package com.biskyt.mytvplayer.ui.live

import com.biskyt.mytvplayer.data.db.ChannelProgramme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Left and right in the guide reach every programme a row shows, whatever the guide's times are like. */
class GuideStepTest {
    private fun p(start: Int, stop: Int) = ChannelProgramme(1, start * MIN, stop * MIN, "$start", null, null)

    /** Steps from the first programme to the end and back, as Right and Left do; the programmes visited. */
    private fun walk(visible: List<ChannelProgramme>): List<Int> {
        val seen = mutableListOf<Int>()
        var time = 0L
        for (dir in listOf(1, -1)) {
            while (true) {
                time = stepTime(visible, cellAt(visible, time), dir, windowStart = 0) ?: break
                seen += cellAt(visible, time)
            }
        }
        return seen
    }

    @Test
    fun `one after another`() {
        assertEquals(listOf(1, 2, 1, 0), walk(listOf(p(0, 60), p(60, 120), p(120, 180))))
    }

    @Test
    fun `programmes that overlap`() {
        assertEquals(listOf(1, 2, 1, 0), walk(listOf(p(0, 60), p(30, 90), p(90, 150))))
    }

    @Test
    fun `a programme that lasts no time is passed over`() {
        assertEquals(listOf(2, 0), walk(listOf(p(0, 60), p(60, 60), p(60, 120))))
    }

    @Test
    fun `at the edge there is nothing further`() {
        assertNull(stepTime(listOf(p(0, 60)), 0, 1, windowStart = 0))
        assertNull(stepTime(listOf(p(0, 60)), 0, -1, windowStart = 0))
    }

    private companion object {
        const val MIN = 60_000L
    }
}
