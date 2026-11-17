package com.biskyt.mytvplayer.data

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.biskyt.mytvplayer.AppText
import com.biskyt.mytvplayer.core.tmdb.TmdbClient
import com.biskyt.mytvplayer.core.tmdb.TmdbEpisode
import com.biskyt.mytvplayer.core.tmdb.TmdbException
import com.biskyt.mytvplayer.core.tmdb.TmdbPerson
import com.biskyt.mytvplayer.core.tmdb.TmdbSuggestion
import com.biskyt.mytvplayer.core.tmdb.TmdbTitle
import com.biskyt.mytvplayer.core.xtream.VodDetails
import java.io.File
import okhttp3.Cache
import okhttp3.OkHttpClient

/**
 * More about films and series from TMDB, with the user's own key (Settings › TMDB). Without a key it does nothing: no
 * requests, and not even its cache is made. Answers are kept for [KEEP_DAYS] days, so a page opened again costs no
 * request. A title is found by the provider's TMDB id, else by its name and year. A failure gives null: the page then
 * shows what the provider gave.
 */
class TmdbRepository(
    private val cacheDir: File,
    private val http: OkHttpClient,
    private val settings: SettingsRepository,
    /** TMDB's address: a test sets a server of its own. */
    @set:VisibleForTesting var base: String = "https://api.themoviedb.org/3/"
) {
    private val cached: OkHttpClient by lazy {
        http.newBuilder()
            .cache(Cache(File(cacheDir, "tmdb"), CACHE_BYTES))
            // TMDB's own answers are kept for a short time only: titles change little, so they are kept for days.
            .addNetworkInterceptor { chain ->
                chain.proceed(chain.request()).newBuilder()
                    .header("Cache-Control", "public, max-age=${KEEP_DAYS * SECONDS_A_DAY}")
                    .removeHeader("Pragma")
                    .build()
            }
            .build()
    }

    private suspend fun client(): TmdbClient? = settings.current().tmdbKey.trim().ifEmpty { null }?.let { client(it) }

    /** The client last made, and what it was made for (key, language and address). */
    private var last: Pair<List<String>, TmdbClient>? = null

    /** A client for [key] in the app's language: the last one, unless the key, the language or the address changed. */
    @Synchronized
    private fun client(key: String): TmdbClient {
        val made = listOf(key, AppText.locale.toLanguageTag(), base)
        last?.let { (forWhat, client) -> if (forWhat == made) return client }
        return TmdbClient(cached, made[0], made[1], made[2]).also { last = made to it }
    }

    /** The film named [name] on TMDB, by [details]' TMDB id where the provider gave one. */
    suspend fun movie(name: String, details: VodDetails?): TmdbTitle? = quietly { it.match(series = false, name, details) }

    /** The series named [name] on TMDB, by [details]' TMDB id where the provider gave one. */
    suspend fun series(name: String, details: VodDetails?): TmdbTitle? = quietly { it.match(series = true, name, details) }

    /** The episodes of [season] of the TMDB series [seriesId]. */
    suspend fun season(seriesId: Int, season: Int): List<TmdbEpisode> = quietly { it.season(seriesId, season) }.orEmpty()

    /** Film (or, when [series], series) [id]'s page on TMDB; null when TMDB cannot give it (no key, or out of reach). */
    suspend fun title(series: Boolean, id: Int): TmdbTitle? = quietly { if (series) it.series(id) else it.movie(id) }

    /** The films (or, when [series], the series) that TMDB suggests for its title [id]; null when TMDB cannot give them. */
    suspend fun recommendations(series: Boolean, id: Int): List<TmdbSuggestion>? = quietly { it.recommendations(series, id) }

    /** The person [id] on TMDB, with their credits. */
    suspend fun person(id: Long): TmdbPerson? = quietly { it.person(id) }

    /** Whether TMDB accepts [key]; null when TMDB cannot be reached. */
    suspend fun keyWorks(key: String): Boolean? = try {
        client(key.trim()).keyWorks()
    } catch (e: TmdbException) {
        Log.w(TAG, "TMDB key check failed: ${e.message}")
        null
    }

    private suspend fun <T> quietly(block: suspend (TmdbClient) -> T?): T? {
        val c = client() ?: return null
        return try {
            block(c)
        } catch (e: TmdbException) {
            Log.w(TAG, "TMDB: ${e.message}")
            null
        }
    }

    private companion object {
        const val TAG = "Tmdb"
        const val KEEP_DAYS = 7
        const val SECONDS_A_DAY = 86_400
        const val CACHE_BYTES = 10L * 1024 * 1024
    }
}
