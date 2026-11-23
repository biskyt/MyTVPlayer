package com.biskyt.mytvplayer.core.vod

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadNamesTest {
    @Test
    fun `a film goes in a folder of its own, named with its year`() {
        assertEquals(DownloadNames.Place("Movies/Dune (2021)", "Dune (2021).mkv"), DownloadNames.movie("Dune", 2021, "mkv"))
        assertEquals(DownloadNames.Place("Movies/Heat", "Heat.mp4"), DownloadNames.movie("Heat", null, ".MP4"))
    }

    @Test
    fun `an episode goes in its series' folder and its season's folder, with its season and episode`() {
        assertEquals(
            DownloadNames.Place("TV Shows/Line of Duty/Season 01", "Line of Duty S01E02.mkv"),
            DownloadNames.episode("Line of Duty", 1, 2, "mkv")
        )
        assertEquals(DownloadNames.Place("TV Shows/Show/Season 10", "Show S10E123.mp4"), DownloadNames.episode("Show", 10, 123, ""))
    }

    @Test
    fun `characters that file systems refuse are left out`() {
        assertEquals("Movies/Mission Impossible Part 1", DownloadNames.movie("Mission: Impossible / Part 1", null, "mp4").folder)
        assertEquals("Untitled.mp4", DownloadNames.movie("...", null, "mp4").file)
    }
}
