package com.biskyt.mytvplayer.core.vod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Episode names without what the series page shows already (VodTitles.episodeName). */
class EpisodeTitlesTest {
    private fun name(title: String, series: String? = "Masterchef USA (2010) Gordon Ramsey") = VodTitles.episodeName(title, series)

    @Test
    fun `the name after the episode code is kept`() {
        assertEquals("Auditions Day 1 (Europe)", name("EN: Masterchef USA (2010) Gordon Ramsey - S016E01 - Auditions Day 1  (Europe)"))
        assertEquals("Rose", name("Doctor Who 1x05 Rose", "Doctor Who"))
        assertEquals("Pilot", name("S01E01 - Pilot", "Show"))
    }

    @Test
    fun `real names from a provider`() {
        val names = mapOf(
            "EN - All Creatures Great & Small - S04E01 - Broodiness" to "Broodiness",
            "EN - MasterChef Australia (2009) (AU) - S18E20 - Mystery Box Challenge: Dim Sim Lim" to "Mystery Box Challenge: Dim Sim Lim",
            "EN - MasterChef USA (2010) Gordon Ramsay - S16E02 - Auditions Day 2 (Asia-Pacific)" to "Auditions Day 2 (Asia-Pacific)",
            "EN - Better Call Saul (2015-2022) - S01E01 - Uno" to "Uno",
            "NF - Berlin and the Lady with an Ermine (2026) (ES) - S01E01 - Episode 1" to "Episode 1",
            "Ghosts - S01E01 - Pilot" to "Pilot",
            "EN - Taskmaster: Champion Of Champions (2017) (GB) - S01E01 - Wiley Giraffe Blower" to "Wiley Giraffe Blower",
        )
        names.forEach { (title, shown) -> assertEquals(shown, name(title, "Any")) }
    }

    @Test
    fun `the parts can come in any order, with other separators`() {
        val series = "MasterChef USA (2010) Gordon Ramsay [1080p]"
        listOf(
            "EN - Auditions Day 1 - S16E01 - MasterChef USA (2010) Gordon Ramsay",
            "EN - S16E01 - Auditions Day 1 - MasterChef USA",
            "EN: MasterChef USA | Auditions Day 1 | S16E01",
            "MasterChef_USA_S16E01_Auditions Day 1",
            "MasterChef USA, S16E01, Auditions Day 1",
            "MasterChef USA S16E01 Auditions Day 1",
            "Auditions Day 1 (S16E01)",
            "MasterChef USA Season 16 Episode 1 - Auditions Day 1",
            "EN - Auditions Day 1 - MasterChef USA",
            "MasterChef USA – Auditions Day 1 – 2010",
        ).forEach { assertEquals(it, "Auditions Day 1", name(it, series)) }
    }

    @Test
    fun `a name that only starts with the series name, or has commas or dots, stays whole`() {
        assertEquals("Ghosts of Christmas Past", name("Ghosts of Christmas Past", "Ghosts"))
        assertEquals("Hello, Goodbye", name("Ghosts - S01E02 - Hello, Goodbye", "Ghosts"))
        assertEquals("To Be Continued...", name("Ghosts - S01E09 - To Be Continued...", "Ghosts"))
        assertEquals("eps1.0_hellofriend.mov", name("Mr. Robot - S01E01 - eps1.0_hellofriend.mov", "Mr. Robot"))
        // Only the start of the series' name, but nothing else: kept.
        assertEquals("Doctor Who", name("S01E01 - Doctor Who", "Doctor Who Confidential"))
        // A language or quality code at the end goes, as at the start.
        assertEquals("Pilot", name("Show - S01E01 - Pilot - EN", "Show"))
        assertEquals("Pilot", name("Show | Pilot | 4K | S01E01", "Show"))
    }

    @Test
    fun `a series name with brackets still matches`() {
        assertEquals(
            "Auditions Day 1",
            name("EN - MasterChef USA (2010) Gordon Ramsay: Auditions Day 1", "MasterChef USA (2010) Gordon Ramsay [1080p]")
        )
    }

    @Test
    fun `without an episode code, the tag and the series name go`() {
        assertEquals("Auditions Day 1", name("EN: Masterchef USA (2010) Gordon Ramsey - Auditions Day 1"))
        assertEquals("Auditions Day 1", name("|UK| Masterchef USA Gordon Ramsey: Auditions Day 1"))
        assertEquals("The Finale (Part 2)", name("The Finale (Part 2)"))
        // A name that only starts like the series stays whole.
        assertEquals("Masterchef Junior Special", name("Masterchef Junior Special", "Masterchef USA"))
    }

    @Test
    fun `nothing left is no name, so the app says Episode 1`() {
        assertNull(name("EN: Masterchef USA (2010) Gordon Ramsey - S16E01"))
        assertNull(name("Masterchef USA (2010) Gordon Ramsey"))
        assertNull(name("  "))
    }

    @Test
    fun `without the series name, only the tag goes`() {
        assertEquals("Line of Duty Episode One", name("EN - Line of Duty Episode One", null))
    }
}
