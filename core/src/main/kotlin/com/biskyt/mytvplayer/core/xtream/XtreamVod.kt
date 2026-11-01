package com.biskyt.mytvplayer.core.xtream

import java.time.LocalDate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A movie in the provider's list (the light fields; details come from [XtreamClient.movieDetails]). */
data class XtreamMovie(
    val streamId: Long,
    val number: Int,
    val name: String,
    val categoryId: String,
    val posterUrl: String?,
    val rating: Double?,
    /** When the provider added it (epoch seconds), for "Recently added". */
    val added: Long,
    val extension: String
)

data class XtreamSeries(
    val seriesId: Long,
    val number: Int,
    val name: String,
    val categoryId: String,
    val coverUrl: String?,
    val rating: Double?,
    val year: Int?,
    val lastModified: Long
)

/** What a details page shows for a movie or series. Any field may be missing: providers fill them unevenly. */
data class VodDetails(
    val plot: String?,
    val cast: String?,
    val director: String?,
    val genre: String?,
    val year: Int?,
    val durationSec: Int?,
    val backdropUrl: String?,
    val posterUrl: String?,
    val rating: Double?
)

/** A season; [name] is the provider's, null when it gives none (the app then names it by [number]). */
data class SeasonInfo(val number: Int, val name: String?)

data class EpisodeInfo(
    val id: Long,
    val season: Int,
    val number: Int,
    /** The provider's title; blank when it gave none (the app then names it, see episodeTitle). */
    val title: String,
    val extension: String,
    val plot: String?,
    val durationSec: Int?,
    val imageUrl: String?,
    /** When it was first shown, if the provider says. */
    val airDate: LocalDate? = null
)

data class SeriesDetails(val info: VodDetails, val seasons: List<SeasonInfo>, val episodes: List<EpisodeInfo>)

internal fun JsonObject.double(key: String): Double? = str(key)?.toDoubleOrNull()?.takeIf { it > 0 }

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

/** The first URL of a field that panels send either as a string or as an array of strings. */
private fun JsonObject.firstUrl(key: String): String? = when (val v = this[key]) {
    is JsonArray -> (v.firstOrNull() as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
    else -> str(key)
}

private val yearPattern = Regex("""(19|20)\d{2}""")

private fun yearOf(date: String?): Int? = date?.let { yearPattern.find(it)?.value?.toInt() }

/**
 * A date such as "2023-01-15" (or "2023-01-15 21:00:00"); null when it isn't one, or is 1 January 1970 (some panels'
 * placeholder for none).
 */
private fun dateOf(text: String?): LocalDate? = text?.trim()?.let(isoDate::find)?.takeIf { it.range.first == 0 }
    ?.let { runCatching { LocalDate.parse(it.value) }.getOrNull() } // a day that doesn't exist ("2023-02-30")
    // Not LocalDate.EPOCH: Android added that only in API 34, and it crashed series pages on older TVs (issue 94).
    ?.takeIf { it.toEpochDay() != 0L }

private val isoDate = Regex("""\d{4}-\d{2}-\d{2}""")

internal fun JsonElement.toMovie(): XtreamMovie? {
    val o = this as? JsonObject ?: return null
    val id = o.long("stream_id") ?: return null
    return XtreamMovie(
        streamId = id,
        number = o.int("num") ?: 0,
        name = o.str("name")?.trim() ?: "#$id", // none: its number, which needs no translating
        categoryId = o.str("category_id") ?: "",
        posterUrl = o.str("stream_icon"),
        rating = o.double("rating"),
        added = o.long("added") ?: 0,
        extension = o.str("container_extension") ?: "mp4"
    )
}

internal fun JsonElement.toSeries(): XtreamSeries? {
    val o = this as? JsonObject ?: return null
    val id = o.long("series_id") ?: return null
    return XtreamSeries(
        seriesId = id,
        number = o.int("num") ?: 0,
        name = o.str("name")?.trim() ?: "#$id",
        categoryId = o.str("category_id") ?: "",
        coverUrl = o.str("cover"),
        rating = o.double("rating"),
        year = yearOf(o.str("releaseDate") ?: o.str("release_date")),
        lastModified = o.long("last_modified") ?: 0
    )
}

/** The `info` block of get_vod_info / get_series_info (an empty array when the panel has nothing). */
private fun JsonObject?.toDetails(): VodDetails {
    val o = this ?: JsonObject(emptyMap())
    return VodDetails(
        plot = o.str("plot") ?: o.str("description"),
        cast = o.str("cast") ?: o.str("actors"),
        director = o.str("director"),
        genre = o.str("genre"),
        year = yearOf(o.str("releasedate") ?: o.str("releaseDate") ?: o.str("release_date")),
        durationSec = o.int("duration_secs") ?: o.int("episode_run_time")?.times(60),
        backdropUrl = o.firstUrl("backdrop_path"),
        posterUrl = o.str("movie_image") ?: o.str("cover"),
        rating = o.double("rating")
    )
}

internal fun JsonElement.toMovieDetails(): VodDetails? {
    val o = this as? JsonObject ?: return null
    return o.obj("info").toDetails()
}

internal fun JsonElement.toSeriesDetails(): SeriesDetails? {
    val o = this as? JsonObject ?: return null
    val seasons = (o["seasons"] as? JsonArray).orEmpty().mapNotNull { el ->
        val s = el as? JsonObject ?: return@mapNotNull null
        val n = s.int("season_number") ?: return@mapNotNull null
        SeasonInfo(n, s.str("name"))
    }
    // Episodes come grouped by season, as an object keyed by season number or (on some panels) an array of arrays;
    // the group's season counts when an episode doesn't say.
    val groups: List<Pair<Int?, JsonElement>> = when (val e = o["episodes"]) {
        is JsonObject -> e.entries.map { (key, value) -> key.toIntOrNull() to value }
        is JsonArray -> e.map { null to it }
        else -> emptyList()
    }
    val episodes = groups.flatMap { (season, list) ->
        (list as? JsonArray).orEmpty().map { season to it }
    }.mapNotNull { (groupSeason, el) ->
        val e = el as? JsonObject ?: return@mapNotNull null
        val id = e.long("id") ?: return@mapNotNull null
        val info = e.obj("info")
        val number = e.int("episode_num") ?: 0
        EpisodeInfo(
            id = id,
            season = e.int("season") ?: groupSeason ?: 1,
            number = number,
            title = e.str("title")?.trim().orEmpty(), // none: the app names it ("Episode 3", in its language)
            extension = e.str("container_extension") ?: "mp4",
            plot = info?.str("plot"),
            durationSec = info?.int("duration_secs"),
            imageUrl = info?.str("movie_image"),
            airDate = dateOf(info?.str("air_date")) ?: dateOf(info?.str("releasedate"))
        )
    }.sortedWith(compareBy({ it.season }, { it.number }))
    // Some panels list no seasons, only episodes: derive them.
    val allSeasons = seasons.ifEmpty { episodes.map { it.season }.distinct().map { SeasonInfo(it, null) } }
        .filter { s -> episodes.any { it.season == s.number } }
        .distinctBy { it.number } // some panels list a season twice
        .sortedBy { it.number }
    return SeriesDetails(o.obj("info").toDetails(), allSeasons, episodes)
}
