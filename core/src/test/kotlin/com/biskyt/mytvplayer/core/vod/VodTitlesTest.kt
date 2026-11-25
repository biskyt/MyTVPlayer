package com.biskyt.mytvplayer.core.vod

import org.junit.Assert.assertEquals
import org.junit.Test

class VodTitlesTest {
    @Test
    fun `provider decorations are removed and the year kept`() {
        val cases = mapOf(
            "EN - The Matrix (1999)" to CleanTitle("The Matrix", 1999),
            "|UK| Line of Duty" to CleanTitle("Line of Duty", null),
            "[4K] Dune [2021]" to CleanTitle("Dune", 2021),
            "|EN| [4K] Oppenheimer - 2023" to CleanTitle("Oppenheimer", 2023),
            "FR: Amélie (2001)" to CleanTitle("Amélie", 2001),
            "Blade Runner 2049" to CleanTitle("Blade Runner 2049", null),
            "1917 (2019)" to CleanTitle("1917", 2019),
            "Up" to CleanTitle("Up", null),
            "Star Wars: A New Hope" to CleanTitle("Star Wars: A New Hope", null),
        )
        cases.forEach { (input, expected) -> assertEquals(input, expected, VodTitles.clean(input, series = false)) }
    }

    @Test
    fun `what a provider adds in brackets after a name goes, and a film keeps its round brackets`() {
        val films = mapOf(
            "Dune [4K] [MULTI]" to CleanTitle("Dune", null),
            "EN - Dune (2021) [4K HDR]" to CleanTitle("Dune", 2021),
            "Dune [2021] [MULTI SUB]" to CleanTitle("Dune", 2021),
            "(500) Days of Summer [HD]" to CleanTitle("(500) Days of Summer", null),
            "The Office (US) [4K]" to CleanTitle("The Office (US)", null),
            // A title that starts with brackets keeps them.
            "[REC] 2 [FHD]" to CleanTitle("[REC] 2", null),
            "[REC]" to CleanTitle("[REC]", null),
        )
        films.forEach { (input, expected) -> assertEquals(input, expected, VodTitles.clean(input, series = false)) }
        val series = mapOf(
            // A country's code tells two series of one name apart: it stays.
            "The Office (US) [4K]" to CleanTitle("The Office (US)", null),
            "|EN| Line of Duty (2012) (Dubbed) [HEVC]" to CleanTitle("Line of Duty", 2012),
            "Show [2019] Remastered [2023]" to CleanTitle("Show Remastered", 2023),
            "Dune [4K]: Part Two (Subbed)" to CleanTitle("Dune: Part Two", null),
            "Doctor Who (2005)" to CleanTitle("Doctor Who", 2005),
            "Show [2019] (Dubbed)" to CleanTitle("Show", 2019),
            "Line of Duty" to CleanTitle("Line of Duty", null),
        )
        series.forEach { (input, expected) -> assertEquals(input, expected, VodTitles.clean(input, series = true)) }
    }

    @Test
    fun `a name cleaned again stays the same`() {
        listOf("Dune", "(500) Days of Summer", "[REC] 2", "Star Wars: A New Hope", "Blade Runner 2049").forEach {
            assertEquals(it, VodTitles.clean(it, series = false).title)
            assertEquals(it, VodTitles.clean(VodTitles.clean(it, series = false).title, series = false).title)
        }
        listOf("Line of Duty (2012) (Dubbed) [HEVC]", "The Office (US) [4K]", "Doctor Who (2005)", "[REC] Files (Subbed)").forEach {
            val once = VodTitles.clean(it, series = true).title
            assertEquals(it, once, VodTitles.clean(once, series = true).title)
        }
    }

    @Test
    fun `a programme and a series that differ only in brackets compare as the same`() {
        assertEquals(VodTitles.key("The Office (US)"), VodTitles.key("The Office (US) [4K]"))
        assertEquals(VodTitles.key("Line of Duty"), VodTitles.key("Line of Duty (Dubbed)"))
    }
}
