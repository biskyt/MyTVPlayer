package com.biskyt.mytvplayer.data

import com.biskyt.mytvplayer.data.db.AppDatabase
import com.biskyt.mytvplayer.data.db.CAT_LIVE
import com.biskyt.mytvplayer.data.db.ChannelEntity
import com.biskyt.mytvplayer.data.db.ProgrammeHit
import com.biskyt.mytvplayer.data.db.VodHit
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

data class SearchResults(
    val channels: List<ChannelEntity> = emptyList(),
    val programmes: List<ProgrammeHit> = emptyList(),
    val movies: List<VodHit> = emptyList(),
    val series: List<VodHit> = emptyList(),
    /** The name of each found channel's category, by its id (the owner, 2026-11-26: a result says where it is from). */
    val channelCategories: Map<String, String> = emptyMap()
) {
    val isEmpty: Boolean get() = channels.isEmpty() && programmes.isEmpty() && movies.isEmpty() && series.isEmpty()
}

/** Typed text for a LIKE match, taken literally ("%" and "_" aren't wildcards); the queries say ESCAPE '\'. */
fun likeArg(query: String): String = query.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

/** One search across channels, upcoming TV programmes, movies and series of a playlist. */
class SearchRepository(private val db: AppDatabase, private val clock: () -> Long = System::currentTimeMillis) {
    /**
     * Null when [query] is too short to search (a single letter would match nearly everything). Channels of [kinds]
     * only: radio stations too, unless they are hidden.
     */
    suspend fun search(playlistId: Long, query: String, kinds: ChannelKinds = ChannelKinds.ALL): SearchResults? = coroutineScope {
        val q = likeArg(query)
        when {
            // Digits alone are a channel number.
            q.isNotEmpty() &&
                q.all(
                    Char::isDigit
                ) &&
                q.length < MIN_LENGTH -> db.channels().searchNumber(playlistId, q, LIMIT, kinds.values).let {
                SearchResults(channels = it, channelCategories = categories(playlistId, it))
            }
            q.length < MIN_LENGTH -> null
            else -> {
                // The lists are independent; the database reads them side by side.
                val channels = async { db.channels().search(playlistId, q, LIMIT, kinds.values) }
                // Their categories' names as soon as they are found, beside the slower reads.
                val categories = async { categories(playlistId, channels.await()) }
                val movies = async { db.vod().searchMovies(playlistId, q, LIMIT) }
                val series = async { db.vod().searchSeries(playlistId, q, LIMIT) }
                // The guide is the slowest to scan, so it waits for a more specific query.
                val programmes = async {
                    if (q.length >= PROGRAMME_MIN_LENGTH) db.epg().searchProgrammes(playlistId, q, clock(), LIMIT) else emptyList()
                }
                SearchResults(channels.await(), programmes.await(), movies.await(), series.await(), categories.await())
            }
        }
    }

    /** The names of [channels]' categories, by id. */
    private suspend fun categories(playlistId: Long, channels: List<ChannelEntity>): Map<String, String> {
        if (channels.isEmpty()) return emptyMap()
        val ids = channels.mapTo(HashSet()) { it.categoryId }
        val found = db.categories().byIds(playlistId, CAT_LIVE, ids).associate { it.categoryId to it.label }
        // One with no category is named as Channel info names it.
        return ids.associateWith { found[it] ?: categoryLabel("", null) }
    }

    private companion object {
        const val MIN_LENGTH = 2
        const val PROGRAMME_MIN_LENGTH = 3
        const val LIMIT = 30
    }
}
