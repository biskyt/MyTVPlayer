package com.biskyt.mytvplayer.core.xtream

import java.time.LocalDate
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class SeriesDetailsTest {
    @Test
    fun `a season listed twice is shown once`() {
        // The app lists seasons in a row keyed by number: the same number twice would crash it.
        val details = Json.parseToJsonElement(
            """{"seasons":[{"season_number":1,"name":"Season 1"},{"season_number":1,"name":"Series 1"},{"season_number":2}],
               "episodes":{"1":[{"id":11,"episode_num":1}],"2":[{"id":21,"episode_num":1}]}}"""
        ).toSeriesDetails()!!
        assertEquals(listOf(1, 2), details.seasons.map { it.number })
        assertEquals("Season 1", details.seasons.first().name) // the first one listed
    }

    @Test
    fun `an episode's air date is read, or else its release date`() {
        val details = Json.parseToJsonElement(
            """{"episodes":{"1":[
                 {"id":11,"episode_num":1,"info":{"air_date":"2023-01-15","releasedate":"2022-12-01"}},
                 {"id":12,"episode_num":2,"info":{"releasedate":"2023-01-22 21:00:00"}},
                 {"id":13,"episode_num":3,"info":{"air_date":"","releasedate":"soon"}},
                 {"id":14,"episode_num":4},
                 {"id":15,"episode_num":5,"info":{"air_date":"1970-01-01"}}]}}"""
        ).toSeriesDetails()!!
        assertEquals(
            listOf(LocalDate.of(2023, 1, 15), LocalDate.of(2023, 1, 22), null, null, null),
            details.episodes.map { it.airDate }
        )
    }
}
