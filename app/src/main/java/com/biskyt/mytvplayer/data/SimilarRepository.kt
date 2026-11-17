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

/**
 * A "More like this" page: the titles TMDB suggests, in TMDB's order. [inPlaylist] are the ones in the playlist, each
 * with the playlist's card. [elsewhere] are the others. Both are empty when TMDB has no suggestions.
 */
data class SimilarPage(val inPlaylist: List<Pair<VodCard, TmdbSuggestion>>, val elsewhere: List<TmdbSuggestion>)

/** "More like this" pages, from TMDB. Nothing is saved: TMDB's answers are kept for days (see [TmdbRepository]). */
class SimilarRepository(private val vod: VodRepository, private val tmdb: TmdbRepository) {
    /**
     * The titles that TMDB suggests for film (or, when [series], series) [tmdbId], found in playlist [playlistId] as its
     * lists show it now (restricted titles are hidden while locked). Fails when TMDB cannot give them (no key, or out of
     * reach).
     */
    suspend fun page(playlistId: Long, series: Boolean, tmdbId: Int): SimilarPage {
        val suggestions = tmdb.recommendations(series, tmdbId) ?: throw IOException(appString(R.string.vod_similar_failed))
        if (suggestions.isEmpty()) return SimilarPage(emptyList(), emptyList())
        val cards = vod.cards(playlistId, if (series) CAT_SERIES else CAT_MOVIE)
        // A playlist can have tens of thousands of titles: they are matched away from the main thread.
        return withContext(Dispatchers.Default) { similarPage(suggestions, cards) }
    }
}

/**
 * Puts [suggestions] that are in [cards] (the playlist's films, or its series for a series' suggestions) in one list,
 * and the others in a second.
 */
fun similarPage(suggestions: List<TmdbSuggestion>, cards: List<VodCard>): SimilarPage {
    val playlist = PlaylistMatcher(cards)
    val found = suggestions.map { it to playlist.find(it.title, it.originalTitle, it.year) }
    return SimilarPage(
        inPlaylist = found.mapNotNull { (s, card) -> card?.let { it to s } }.distinctBy { it.first.id },
        elsewhere = found.filter { it.second == null }.map { it.first },
    )
}
