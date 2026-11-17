package com.biskyt.mytvplayer.core.tmdb

import com.biskyt.mytvplayer.core.vod.CastMember
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// TMDB's answers, as far as the app reads them.

@Serializable
internal data class Named(val name: String = "")

@Serializable
internal data class CastJson(
    val name: String = "",
    val character: String? = null,
    @SerialName("profile_path") val profilePath: String? = null
)

@Serializable
internal data class CrewJson(val name: String = "", val job: String? = null, val department: String? = null)

@Serializable
internal data class CreditsJson(val cast: List<CastJson> = emptyList(), val crew: List<CrewJson> = emptyList())

@Serializable
internal data class VideoJson(
    val key: String = "",
    val site: String = "",
    val type: String = "",
    val official: Boolean = false
)

@Serializable
internal data class VideosJson(val results: List<VideoJson> = emptyList())

@Serializable
internal data class ExternalIdsJson(@SerialName("imdb_id") val imdbId: String? = null)

@Serializable
internal data class MovieJson(
    val id: Int,
    val overview: String? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    @SerialName("vote_count") val voteCount: Int = 0,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    val genres: List<Named> = emptyList(),
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("production_companies") val companies: List<Named> = emptyList(),
    @SerialName("production_countries") val countries: List<Named> = emptyList(),
    @SerialName("imdb_id") val imdbId: String? = null,
    val credits: CreditsJson = CreditsJson(),
    val videos: VideosJson = VideosJson()
)

@Serializable
internal data class SeriesJson(
    val id: Int,
    val overview: String? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    @SerialName("vote_count") val voteCount: Int = 0,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    val genres: List<Named> = emptyList(),
    @SerialName("original_name") val originalName: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("created_by") val createdBy: List<Named> = emptyList(),
    val networks: List<Named> = emptyList(),
    @SerialName("production_countries") val countries: List<Named> = emptyList(),
    @SerialName("external_ids") val externalIds: ExternalIdsJson = ExternalIdsJson(),
    val credits: CreditsJson = CreditsJson(),
    val videos: VideosJson = VideosJson()
)

@Serializable
internal data class EpisodeJson(
    @SerialName("season_number") val season: Int = 0,
    @SerialName("episode_number") val number: Int = 0,
    val name: String? = null,
    val overview: String? = null,
    @SerialName("still_path") val stillPath: String? = null,
    @SerialName("air_date") val airDate: String? = null
)

@Serializable
internal data class SeasonJson(val episodes: List<EpisodeJson> = emptyList())

@Serializable
internal data class SearchHit(
    val id: Int,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null
)

@Serializable
internal data class SearchJson(val results: List<SearchHit> = emptyList())

/** At most this many of the cast: the main parts. */
private const val CAST = 20

/** Fewer votes than this make a score that means little. */
private const val MIN_VOTES = 10

private val WRITING = setOf("Screenplay", "Writer", "Story", "Novel", "Author")

private fun CreditsJson.people(): List<CastMember> = cast.take(CAST).filter { it.name.isNotBlank() }
    .map { CastMember(it.name, it.character?.ifBlank { null }, image(it.profilePath, "w185")) }

private fun CreditsJson.crewFor(jobs: Set<String>) = crew.filter { it.job in jobs }.map { it.name }.filter { it.isNotBlank() }.distinct()

/** The best YouTube trailer: an official trailer, then any trailer, then a teaser. */
internal fun VideosJson.trailer(): String? {
    val youTube = results.filter { it.site == "YouTube" && it.key.isNotBlank() }
    return (
        youTube.firstOrNull { it.type == "Trailer" && it.official }
            ?: youTube.firstOrNull { it.type == "Trailer" }
            ?: youTube.firstOrNull { it.type == "Teaser" }
        )?.key
}

private fun score(average: Double, votes: Int) = average.takeIf { votes >= MIN_VOTES && it > 0 }

private fun List<Named>.names() = map { it.name }.filter { it.isNotBlank() }

internal fun MovieJson.toTitle() = TmdbTitle(
    id = id,
    series = false,
    imdbId = imdbId?.ifBlank { null },
    overview = overview?.ifBlank { null },
    score = score(voteAverage, voteCount),
    cast = credits.people(),
    directors = credits.crewFor(setOf("Director")),
    writers = credits.crewFor(WRITING),
    trailer = videos.trailer(),
    posterUrl = image(posterPath, "w342"),
    backdropUrl = image(backdropPath, "w1280"),
    genres = genres.names(),
    originalTitle = originalTitle?.ifBlank { null },
    released = dateOf(releaseDate),
    studios = companies.names(),
    countries = countries.names()
)

internal fun SeriesJson.toTitle() = TmdbTitle(
    id = id,
    series = true,
    imdbId = externalIds.imdbId?.ifBlank { null },
    overview = overview?.ifBlank { null },
    score = score(voteAverage, voteCount),
    cast = credits.people(),
    directors = emptyList(),
    writers = createdBy.names(),
    trailer = videos.trailer(),
    posterUrl = image(posterPath, "w342"),
    backdropUrl = image(backdropPath, "w1280"),
    genres = genres.names(),
    originalTitle = originalName?.ifBlank { null },
    released = dateOf(firstAirDate),
    studios = networks.names(),
    countries = countries.names()
)

internal fun SeasonJson.toEpisodes() = episodes.map {
    TmdbEpisode(
        it.season,
        it.number,
        it.name?.ifBlank {
            null
        },
        it.overview?.ifBlank { null },
        image(it.stillPath, "w300"),
        dateOf(it.airDate)
    )
}
