package com.biskyt.mytvplayer.data

import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.appString
import com.biskyt.mytvplayer.core.tmdb.TmdbSuggestion
import com.biskyt.mytvplayer.data.db.CAT_MOVIE
import com.biskyt.mytvplayer.data.db.CAT_SERIES
import com.biskyt.mytvplayer.data.db.VodCard
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A title that TMDB suggests, with the playlist's [card] for it; null when the playlist does not have it. */
data class SimilarTitle(val suggestion: TmdbSuggestion, val card: VodCard?)

/** A "More like this" page: the titles TMDB suggests, in TMDB's order. Empty when TMDB has no suggestions. */
data class SimilarPage(val titles: List<SimilarTitle>)

/** "More like this" pages, from TMDB. Nothing is saved: TMDB's answers are kept for days (see [TmdbRepository]). */
class SimilarRepository(private val vod: VodRepository, private val tmdb: TmdbRepository) {
    /**
     * The titles that TMDB suggests for film (or, when [series], series) [tmdbId], found in playlist [playlistId] as its
     * lists show it now (restricted titles are hidden while locked). Fails when TMDB cannot give them (no key, or out of
     * reach).
     */
    suspend fun page(playlistId: Long, series: Boolean, tmdbId: Int): SimilarPage {
        val suggestions = tmdb.recommendations(series, tmdbId) ?: throw IOException(appString(R.string.vod_similar_failed))
        if (suggestions.isEmpty()) return SimilarPage(emptyList())
        val cards = vod.cards(playlistId, if (series) CAT_SERIES else CAT_MOVIE)
        // A playlist can have tens of thousands of titles: they are matched away from the main thread.
        return withContext(Dispatchers.Default) { similarPage(suggestions, cards, series) }
    }
}

/**
 * Finds each of [suggestions] in [cards] (the playlist's films, or its [series]). A card goes with the first suggestion
 * that finds it only: two TMDB titles of the same name do not both open it.
 */
fun similarPage(suggestions: List<TmdbSuggestion>, cards: List<VodCard>, series: Boolean): SimilarPage {
    val playlist = PlaylistMatcher(cards, series)
    val used = HashSet<Long>()
    return SimilarPage(
        suggestions.map { s -> SimilarTitle(s, playlist.find(s.title, s.originalTitle, s.year)?.takeIf { used.add(it.id) }) }
    )
}
