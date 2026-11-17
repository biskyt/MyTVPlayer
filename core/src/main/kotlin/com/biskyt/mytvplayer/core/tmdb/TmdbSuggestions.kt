package com.biskyt.mytvplayer.core.tmdb

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// "More like this": the films or series TMDB suggests for a title (its recommendations, else its similar titles).

@Serializable
internal data class SuggestionJson(
    val id: Int = 0,
    /** A film's title. */
    val title: String? = null,
    /** A series' name. */
    val name: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("original_name") val originalName: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    @SerialName("vote_count") val voteCount: Int = 0
)

@Serializable
internal data class SuggestionsJson(val results: List<SuggestionJson> = emptyList())

/**
 * A film or a [series] that TMDB suggests. [title] is in the client's language, and [originalTitle] is the title in
 * its own language when it is different. [score] is the users' score, 0 to 10, or null with too few votes.
 */
data class TmdbSuggestion(
    val id: Int,
    val series: Boolean,
    val title: String,
    val originalTitle: String?,
    val year: Int?,
    val posterUrl: String?,
    val score: Double?
)

/** The suggestions in TMDB's order, without [source] itself and without a title that has no id or no name. */
internal fun SuggestionsJson.toSuggestions(series: Boolean, source: Int): List<TmdbSuggestion> = results.mapNotNull {
    val name = (it.title ?: it.name)?.trim()
    if (it.id <= 0 || it.id == source || name.isNullOrEmpty()) return@mapNotNull null
    TmdbSuggestion(
        id = it.id,
        series = series,
        title = name,
        originalTitle = (it.originalTitle ?: it.originalName)?.trim()?.takeIf { o -> o.isNotEmpty() && o != name },
        year = dateOf(it.releaseDate ?: it.firstAirDate)?.year,
        posterUrl = image(it.posterPath, "w342"),
        score = score(it.voteAverage, it.voteCount)
    )
}.distinctBy { it.id }
