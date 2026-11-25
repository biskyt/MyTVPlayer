package com.biskyt.mytvplayer.ui.vod

import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.appString
import com.biskyt.mytvplayer.core.tmdb.TmdbTitle
import com.biskyt.mytvplayer.core.tmdb.TmdbVideo
import com.biskyt.mytvplayer.core.vod.CastMember
import com.biskyt.mytvplayer.core.xtream.VodDetails

/**
 * What a film's or series' page shows of it, from the provider's details and TMDB's ([tmdb], kept for More…'s Cast
 * and crew, About and links). Made by [merge]: the pages read these fields and choose nothing themselves.
 */
data class TitleInfo(
    val plot: String? = null,
    /** Two genres at most ("Drama, Mystery"), or the provider's genre text as it writes it. */
    val genre: String? = null,
    val year: Int? = null,
    val durationSec: Int? = null,
    /** The provider's rating (★), out of 10. */
    val rating: Double? = null,
    /** TMDB's users' score, out of 10. */
    val score: Double? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val directors: List<String> = emptyList(),
    val writers: List<String> = emptyList(),
    /** TMDB's cast, with photos: shown along the bottom of the film page. */
    val cast: List<CastMember> = emptyList(),
    /** The provider's cast as it writes it ("Tom Hale, Ines Moreau"), only when TMDB has no cast. */
    val castNames: String? = null,
    /** The trailer's page, on YouTube or Vimeo. */
    val trailer: String? = null,
    /** TMDB's trailers and teasers, best first, for Other trailers. */
    val trailers: List<TmdbVideo> = emptyList(),
    val tmdb: TmdbTitle? = null,
    /** Whether the provider's details and TMDB have both answered (or failed): what the page has is all it will have. */
    val settled: Boolean = false
)

/**
 * The page's data: the [provider]'s first, TMDB's where the provider has none. Directors come from TMDB first, as a list
 * of names (the provider's is one text, as it writes it); writers come from TMDB only. The trailer is TMDB's first: it
 * picks an official trailer from its own list, in the app's language where it has one, where the provider's id is
 * whatever its panel kept, often a video since taken down.
 */
fun merge(provider: VodDetails?, tmdb: TmdbTitle?): TitleInfo = TitleInfo(
    plot = provider?.plot ?: tmdb?.overview,
    genre = provider?.genre ?: genresOf(tmdb?.genres.orEmpty()),
    year = provider?.year ?: tmdb?.released?.year,
    durationSec = provider?.durationSec,
    rating = provider?.rating,
    score = tmdb?.score,
    posterUrl = provider?.posterUrl ?: tmdb?.posterUrl,
    backdropUrl = provider?.backdropUrl ?: tmdb?.backdropUrl,
    directors = tmdb?.directors.orEmpty().map { it.name }.ifEmpty { listOfNotNull(provider?.director) },
    writers = tmdb?.writers.orEmpty().map { it.name },
    cast = tmdb?.cast.orEmpty(),
    castNames = provider?.cast?.takeIf { tmdb?.cast.isNullOrEmpty() },
    trailer = tmdb?.trailer?.url ?: provider?.trailer?.let(TmdbVideo::youTubePage),
    trailers = tmdb?.trailers.orEmpty(),
    tmdb = tmdb
)

/** Two genres at most: "Drama, Mystery". */
fun genresOf(genres: List<String>): String? = genres.take(2).takeIf { it.isNotEmpty() }?.joinToString(", ")

/** "Directed by Ana Ruiz · Written by Ana Ruiz, Paul Okafor", where known. */
fun people(info: TitleInfo): String? = listOfNotNull(
    info.directors.takeIf { it.isNotEmpty() }?.let { appString(R.string.vod_directed_by, it.joinToString(", ")) },
    info.writers.takeIf { it.isNotEmpty() }?.let { appString(R.string.vod_written_by, it.joinToString(", ")) }
).joinToString(" · ").ifEmpty { null }
