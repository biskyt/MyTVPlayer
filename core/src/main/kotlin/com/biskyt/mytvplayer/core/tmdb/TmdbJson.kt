package com.biskyt.mytvplayer.core.tmdb

import com.biskyt.mytvplayer.core.vod.CastMember
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// TMDB's answers, as far as the app reads them.

@Serializable
internal data class Named(val name: String = "", val id: Long = 0)

@Serializable
internal data class CastJson(
    val id: Long = 0,
    val name: String = "",
    val character: String? = null,
    @SerialName("profile_path") val profilePath: String? = null
)

@Serializable
internal data class CrewJson(val id: Long = 0, val name: String = "", val job: String? = null, val department: String? = null)

@Serializable
internal data class CreditsJson(
    val cast: List<CastJson> = emptyList(),
    val crew: List<CrewJson> = emptyList(),
    /** An episode's: who is in it besides the show's own cast. */
    @SerialName("guest_stars") val guestStars: List<CastJson> = emptyList()
)

@Serializable
internal data class VideoJson(
    val key: String = "",
    val site: String = "",
    val type: String = "",
    val official: Boolean = false,
    val name: String? = null,
    /** The country it was made for ("US", "GB"): a studio's upload can be blocked in other countries. */
    @SerialName("iso_3166_1") val country: String? = null
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
    val videos: VideosJson = VideosJson(),
    val status: String? = null,
    @SerialName("number_of_seasons") val seasonCount: Int? = null,
    @SerialName("number_of_episodes") val episodeCount: Int? = null,
    @SerialName("next_episode_to_air") val nextEpisode: EpisodeJson? = null,
    val seasons: List<SeasonSummaryJson> = emptyList()
)

@Serializable
internal data class EpisodeJson(
    @SerialName("season_number") val season: Int = 0,
    @SerialName("episode_number") val number: Int = 0,
    val name: String? = null,
    val overview: String? = null,
    @SerialName("still_path") val stillPath: String? = null,
    @SerialName("air_date") val airDate: String? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    @SerialName("vote_count") val voteCount: Int = 0,
    /** A season's episodes have who made them, and their guest stars (not the show's own cast). */
    val crew: List<CrewJson> = emptyList(),
    @SerialName("guest_stars") val guestStars: List<CastJson> = emptyList()
)

@Serializable
internal data class SeasonSummaryJson(
    @SerialName("season_number") val number: Int = 0,
    val name: String? = null,
    @SerialName("episode_count") val episodeCount: Int = 0,
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

private val WRITING = setOf("Screenplay", "Writer", "Story", "Novel", "Author", "Teleplay")
private val DIRECTING = setOf("Director")

private fun CreditsJson.people(): List<CastMember> = cast.people()

private fun List<CastJson>.people(): List<CastMember> = take(CAST).filter { it.name.isNotBlank() }
    .map { CastMember(it.name, it.character?.ifBlank { null }, image(it.profilePath, "w185"), personId(it.id)) }

/** A TMDB person id, or null for none (TMDB's ids start at 1; a missing one reads as 0). */
private fun personId(id: Long): Long? = id.takeIf { it > 0 }

/** Someone of the crew: no part (the page names their job) and no photo. */
private fun crewMember(name: String, id: Long) = CastMember(name, null, null, personId(id))

/** The crew of [jobs], each once. */
private fun CreditsJson.crewFor(jobs: Set<String>) = crew.crewFor(jobs)

private fun List<CrewJson>.crewFor(jobs: Set<String>) = filter { it.job in jobs && it.name.isNotBlank() }.distinctBy { it.name }
    .map { crewMember(it.name, it.id) }

/** An episode's own cast and crew (its credits): the show's cast in it, its guest stars, who directed and wrote it. */
internal fun CreditsJson.toEpisodeCredits() = TmdbCredits(
    cast = cast.people(),
    guestStars = guestStars.people(),
    directors = crewFor(DIRECTING),
    writers = crewFor(WRITING)
)

/**
 * The trailers and teasers that the app can play (on YouTube or Vimeo), best first: trailers before teasers, then those
 * made for [region] (the TV's country: an upload for another country is often blocked here), then official ones.
 * Otherwise in TMDB's order.
 */
internal fun VideosJson.trailers(region: String?): List<TmdbVideo> = results
    .filter { it.key.isNotBlank() && it.site in PLAYABLE && it.type in KINDS }
    .sortedWith(
        compareBy<VideoJson>(
            { KINDS.indexOf(it.type) },
            { if (region != null && it.country.equals(region, ignoreCase = true)) 0 else 1 },
            { if (it.official) 0 else 1 },
        )
    )
    .map { TmdbVideo(it.name?.ifBlank { null }, it.site, it.key, it.country?.uppercase(), teaser = it.type == TEASER) }

private val PLAYABLE = setOf(TmdbVideo.YOUTUBE, TmdbVideo.VIMEO)
private const val TEASER = "Teaser"
private val KINDS = listOf("Trailer", TEASER)

internal fun score(average: Double, votes: Int) = average.takeIf { votes >= MIN_VOTES && it > 0 }

private fun List<Named>.names() = map { it.name }.filter { it.isNotBlank() }

internal fun MovieJson.toTitle(region: String? = null) = TmdbTitle(
    id = id,
    series = false,
    imdbId = imdbId?.ifBlank { null },
    overview = overview?.ifBlank { null },
    score = score(voteAverage, voteCount),
    cast = credits.people(),
    directors = credits.crewFor(DIRECTING),
    writers = credits.crewFor(WRITING),
    trailers = videos.trailers(region),
    posterUrl = image(posterPath, "w342"),
    backdropUrl = image(backdropPath, "w1280"),
    genres = genres.names(),
    originalTitle = originalTitle?.ifBlank { null },
    released = dateOf(releaseDate),
    studios = companies.names(),
    countries = countries.names()
)

internal fun SeriesJson.toTitle(region: String? = null) = TmdbTitle(
    id = id,
    series = true,
    imdbId = externalIds.imdbId?.ifBlank { null },
    overview = overview?.ifBlank { null },
    score = score(voteAverage, voteCount),
    cast = credits.people(),
    directors = emptyList(),
    writers = createdBy.filter { it.name.isNotBlank() }.map { crewMember(it.name, it.id) },
    trailers = videos.trailers(region),
    posterUrl = image(posterPath, "w342"),
    backdropUrl = image(backdropPath, "w1280"),
    genres = genres.names(),
    originalTitle = originalName?.ifBlank { null },
    released = dateOf(firstAirDate),
    studios = networks.names(),
    countries = countries.names(),
    status = SeriesStatus.of(status),
    seasonCount = seasonCount?.takeIf { it > 0 },
    episodeCount = episodeCount?.takeIf { it > 0 },
    nextEpisode = nextEpisode?.takeIf { it.number > 0 }?.toEpisode(),
    seasons = seasons.filter { it.number > 0 }.sortedBy { it.number }
        .map { TmdbSeason(it.number, it.name?.ifBlank { null }, it.episodeCount, dateOf(it.airDate)) },
)

internal fun SeasonJson.toEpisodes() = episodes.map { it.toEpisode() }

internal fun EpisodeJson.toEpisode() = TmdbEpisode(
    season,
    number,
    name?.ifBlank { null },
    overview?.ifBlank { null },
    image(stillPath, "w300"),
    dateOf(airDate),
    score = score(voteAverage, voteCount),
    directors = crew.crewFor(DIRECTING),
    writers = crew.crewFor(WRITING),
    guestStars = guestStars.people()
)
