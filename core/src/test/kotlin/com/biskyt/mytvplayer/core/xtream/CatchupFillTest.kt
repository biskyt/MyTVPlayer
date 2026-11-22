package com.biskyt.mytvplayer.core.xtream

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatchupFillTest {
    private val zone: ZoneId = ZoneOffset.UTC

    private fun at(h: Int, m: Int = 0) = LocalDateTime.of(2026, 11, 22, h, m).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun p(from: Long, to: Long, title: String, archive: Boolean = true) = CatchupProgramme(from, to, title, null, archive)

    private fun fill(provider: List<CatchupProgramme>, guide: List<CatchupProgramme>, from: Long, now: Long) =
        CatchupFill.fill(provider, guide, from..now, zone) { "from ${(it / 60_000 % 1440) / 60}:${"%02d".format(it / 60_000 % 60)}" }

    @Test
    fun `with nothing from the provider or the guide, the past is hour slots up to the end of this hour`() {
        val list = fill(emptyList(), emptyList(), at(10), at(12, 20))
        assertEquals(listOf("from 10:00", "from 11:00", "from 12:00"), list.map { it.title })
        assertEquals(at(13), list.last().stopMs) // something is on now, for Start over
        assertTrue(list.all { it.hasArchive })
    }

    @Test
    fun `the provider's programmes stay, the guide fills its gaps, and slots what the guide does not cover`() {
        val provider = listOf(p(at(10), at(11), "News"))
        val guide = listOf(p(at(10, 30), at(11, 30), "Overlaps the news"), p(at(11, 30), at(12), "Quiz"))
        val list = fill(provider, guide, at(10), at(12, 40))
        assertEquals(listOf("News", "from 11:00", "Quiz", "from 12:00"), list.map { it.title })
        assertEquals(at(11, 30), list[1].stopMs)
    }

    @Test
    fun `a programme the provider lists without its archive is kept as it is, and guide programmes are replayable`() {
        val list =
            fill(listOf(p(at(10), at(11), "News", archive = false)), listOf(p(at(11), at(12), "Film", archive = false)), at(10), at(11, 30))
        assertEquals(listOf(false, true), list.map { it.hasArchive })
    }

    @Test
    fun `a gap of a few minutes gets no slot, and a short piece at an hour joins the next slot`() {
        val list = fill(listOf(p(at(9), at(10, 58), "Long"), p(at(11, 1), at(12), "Next")), emptyList(), at(9), at(11, 30))
        assertEquals(listOf("Long", "Next"), list.map { it.title }) // 10:58 to 11:01: too short

        val joined = fill(listOf(p(at(9), at(10, 58), "Long")), emptyList(), at(9), at(11, 30))
        assertEquals(listOf("Long", "from 10:58"), joined.map { it.title })
        assertEquals(at(12), joined.last().stopMs)
    }
}
