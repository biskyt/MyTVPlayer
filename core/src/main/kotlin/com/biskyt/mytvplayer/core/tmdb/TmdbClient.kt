package com.biskyt.mytvplayer.core.tmdb

import com.biskyt.mytvplayer.core.vod.CastMember
import com.biskyt.mytvplayer.core.vod.TitleLink
import com.biskyt.mytvplayer.core.vod.VodTitles
import com.biskyt.mytvplayer.core.xtream.VodDetails
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.CacheControl
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** A TMDB request failed. [invalidKey] is true when TMDB refused the key. */
class TmdbException(message: String, val invalidKey: Boolean = false, cause: Throwable? = null) : IOException(message, cause)

/**
 * The TMDB API (https://developer.themoviedb.org), with the user's own [key]: a v3 API key, or a v4 read access token.
 * Text comes in [language] (a language tag such as "en-GB"), and English where TMDB has none in that language.
 */
class TmdbClient(
    private val http: OkHttpClient,
    private val key: String,
    private val language: String,
    private val base: String = "https://api.themoviedb.org/3/"
) {
    /** A v4 token is a long JWT; a v3 key is 32 hexadecimal characters. */
    private val bearer = key.length > V3_KEY_LENGTH

    /** True when TMDB accepts the key; throws a [TmdbException] when TMDB cannot be reached. */
    suspend fun keyWorks(): Boolean = try {
        // Never from a cache: a key may have been revoked since, and a v4 token is not part of the address.
        get("authentication", emptyList(), fresh = true)
        true
    } catch (e: TmdbException) {
        if (e.invalidKey) false else throw e
    }

    /**
     * The film (or, when [series], the series) the provider names [name]: by the TMDB id in [details] where the provider
     * gave one, else (or when TMDB has no title with that id) the best search match for the name without its tags, and
     * its year (from the name, else [details]). Null when TMDB has none.
     */
    suspend fun match(series: Boolean, name: String, details: VodDetails?): TmdbTitle? {
        val byId = details?.tmdbId?.let { title(series, it) }
        if (byId != null) return byId
        val clean = VodTitles.clean(name)
        val year = clean.year ?: details?.year
        val id = if (series) findSeries(clean.title, year) else findMovie(clean.title, year)
        return id?.takeIf { it != details?.tmdbId }?.let { title(series, it) }
    }

    private suspend fun title(series: Boolean, id: Int) = if (series) series(id) else movie(id)

    /** A film by its TMDB id, or null when TMDB has none with that id. */
    suspend fun movie(id: Int): TmdbTitle? = get("movie/$id", details)
        ?.let { decode(MovieJson.serializer(), it).toTitle() }

    /** A series by its TMDB id, or null when TMDB has none with that id. */
    suspend fun series(id: Int): TmdbTitle? = get("tv/$id", details)
        ?.let { decode(SeriesJson.serializer(), it).toTitle() }

    /** The episodes of [season] of series [seriesId]; empty when TMDB has none. */
    suspend fun season(seriesId: Int, season: Int): List<TmdbEpisode> =
        get("tv/$seriesId/season/$season", emptyList())?.let { decode(SeasonJson.serializer(), it).toEpisodes() }.orEmpty()

    /** The TMDB id of the film best matching [name] (and [year], when known); null when there is none. */
    suspend fun findMovie(name: String, year: Int?): Int? =
        search("search/movie", name, year?.let { "year" to it.toString() }) { it.releaseDate }

    /** The TMDB id of the series best matching [name] (and [year], when known); null when there is none. */
    suspend fun findSeries(name: String, year: Int?): Int? =
        search("search/tv", name, year?.let { "first_air_date_year" to it.toString() }) { it.firstAirDate }

    /**
     * Providers' years are often a year out (a festival year, or a series' latest season), so a search with [year] that
     * finds nothing is made again without it.
     */
    private suspend fun search(path: String, name: String, year: Pair<String, String>?, date: (SearchHit) -> String?): Int? {
        suspend fun hits(withYear: Boolean) = get(
            path,
            listOfNotNull(
                "query" to name,
                year?.takeIf {
                    withYear
                }
            )
        )?.let { decode(SearchJson.serializer(), it).results }.orEmpty()
        val hits = hits(withYear = true).ifEmpty { if (year != null) hits(withYear = false) else emptyList() }
        // TMDB orders by relevance; with a year, a hit from that year first.
        val y = year?.second
        return (hits.firstOrNull { y != null && date(it)?.startsWith(y) == true } ?: hits.firstOrNull())?.id
    }

    private fun <T> decode(serializer: KSerializer<T>, body: String): T = try {
        json.decodeFromString(serializer, body)
    } catch (e: SerializationException) {
        throw TmdbException("Unexpected answer from TMDB", cause = e)
    }

    /** What a title's page asks for besides its own fields: cast and crew, videos (in its language, English or none), ids. */
    private val details
        get() = listOf(
            "append_to_response" to "credits,videos,external_ids",
            "include_video_language" to "${language.substringBefore('-')},en,null"
        )

    /** The body of a GET of [path]; null for a 404 (not found). */
    private suspend fun get(path: String, query: List<Pair<String, String>>, fresh: Boolean = false): String? =
        withContext(Dispatchers.IO) {
            val url = (base + path).toHttpUrl().newBuilder().apply {
                addQueryParameter("language", language)
                query.forEach { (k, v) -> addQueryParameter(k, v) }
                if (!bearer) addQueryParameter("api_key", key)
            }.build()
            call(url, fresh)
        }

    private fun call(url: HttpUrl, fresh: Boolean): String? {
        val request = Request.Builder().url(url).header("Accept", "application/json")
            .apply { if (bearer) header("Authorization", "Bearer $key") }
            .apply { if (fresh) cacheControl(CacheControl.FORCE_NETWORK) }
            .build()
        try {
            http.newCall(request).execute().use { r ->
                return when {
                    r.isSuccessful -> r.body?.string().orEmpty()
                    r.code == NOT_FOUND -> null
                    r.code == UNAUTHORISED -> throw TmdbException("TMDB refused the key", invalidKey = true)
                    else -> throw TmdbException("TMDB answered ${r.code}")
                }
            }
        } catch (e: TmdbException) {
            throw e
        } catch (e: IOException) {
            throw TmdbException("Couldn't reach TMDB: ${e.message}", cause = e)
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        const val V3_KEY_LENGTH = 40
        const val NOT_FOUND = 404
        const val UNAUTHORISED = 401
    }
}

/** A film or series on TMDB. Text is in the client's language; any field may be missing. */
data class TmdbTitle(
    val id: Int,
    val series: Boolean,
    val imdbId: String?,
    val overview: String?,
    /** The users' score, 0 to 10; null with too few votes to mean much. */
    val score: Double?,
    val cast: List<CastMember>,
    val directors: List<String>,
    val writers: List<String>,
    /** The YouTube id of the best trailer. */
    val trailer: String?,
    val posterUrl: String?,
    val backdropUrl: String?,
    val genres: List<String>,
    val originalTitle: String?,
    val released: LocalDate?,
    val studios: List<String>,
    val countries: List<String>
) {
    val tmdbUrl get() = "https://www.themoviedb.org/${if (series) "tv" else "movie"}/$id"
    val imdbUrl get() = imdbId?.let { "https://www.imdb.com/title/$it/" }

    /** Its pages on IMDb (when TMDB knows its IMDb id) and TMDB, in that order. */
    val links get() = listOfNotNull(imdbUrl?.let { TitleLink("IMDb", it) }, TitleLink("TMDB", tmdbUrl))
}

data class TmdbEpisode(
    val season: Int,
    val number: Int,
    val name: String?,
    val overview: String?,
    val stillUrl: String?,
    val airDate: LocalDate?
)

/** A TMDB image path as a URL of [size] (such as "w185"). */
internal fun image(path: String?, size: String): String? =
    path?.takeIf { it.startsWith("/") }?.let { "https://image.tmdb.org/t/p/$size$it" }

internal fun dateOf(text: String?): LocalDate? = text?.takeIf { it.length >= 10 }?.let {
    runCatching { LocalDate.parse(it.substring(0, 10)) }.getOrNull()
}
