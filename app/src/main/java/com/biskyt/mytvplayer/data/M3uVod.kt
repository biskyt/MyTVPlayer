package com.biskyt.mytvplayer.data

import androidx.room.withTransaction
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.appString
import com.biskyt.mytvplayer.core.m3u.M3uEntry
import com.biskyt.mytvplayer.core.m3u.M3uParser
import com.biskyt.mytvplayer.core.net.ProxySettings
import com.biskyt.mytvplayer.core.vod.EpisodeNames
import com.biskyt.mytvplayer.core.vod.VodTitles
import com.biskyt.mytvplayer.core.xtream.EpisodeInfo
import com.biskyt.mytvplayer.core.xtream.SeasonInfo
import com.biskyt.mytvplayer.core.xtream.SeriesDetails
import com.biskyt.mytvplayer.core.xtream.VodDetails
import com.biskyt.mytvplayer.data.db.AppDatabase
import com.biskyt.mytvplayer.data.db.CAT_MOVIE
import com.biskyt.mytvplayer.data.db.CAT_SERIES
import com.biskyt.mytvplayer.data.db.EpisodeEntity
import com.biskyt.mytvplayer.data.db.MovieEntity
import com.biskyt.mytvplayer.data.db.PlaylistEntity
import com.biskyt.mytvplayer.data.db.SeriesEntity
import com.biskyt.mytvplayer.data.db.proxy
import java.io.File
import java.io.IOException
import java.util.zip.CRC32

/**
 * Movies and series from an M3U playlist's movie and series playlists, kept in the same tables as an Xtream account's.
 * Either playlist may hold both: entries named like episodes ("Show S01E02") become series, the rest films; everything
 * in the series playlist alone is a series. Only included categories are kept, and playlists that haven't changed
 * since the last load (nor the categories chosen) aren't read again. Episodes are stored with their series; there are
 * no further details.
 */
internal class M3uVod(private val db: AppDatabase, private val playlists: PlaylistRepository, private val categoryStore: CategoryStore) {
    private val vod = db.vod()
    private val categories = db.categories()

    suspend fun seriesDetails(playlistId: Long, seriesId: Long): SeriesDetails {
        val episodes = vod.episodes(playlistId, seriesId).map {
            EpisodeInfo(it.episodeId, it.season, it.number, it.title, extension = "", plot = null, durationSec = null, imageUrl = null)
        }
        val seasons = episodes.map { it.season }.distinct().map { SeasonInfo(it, null) }
        return SeriesDetails(NO_DETAILS, seasons, episodes)
    }

    suspend fun episodeUrl(playlistId: Long, episodeId: Long): String =
        vod.episode(playlistId, episodeId)?.streamUrl ?: throw IOException(appString(R.string.data_episode_gone))

    suspend fun sync(p: PlaylistEntity, onProgress: (Int) -> Unit) {
        val movies = p.moviesUrl?.trim().orEmpty()
        val series = p.seriesUrl?.trim().orEmpty()
        val urls = listOf(movies, series).filter { it.isNotEmpty() }.distinct()
        if (urls.isEmpty()) return clear(p)
        withFeeds(urls, p.proxy) { files ->
            val feeds = urls.zip(files) { url, file -> Feed(file, seriesOnly = url == series && url != movies) }
            // Unchanged playlists and choices: nothing to do, and nothing parsed to find that out.
            val contents = checksum(movies, series, files)
            if (fingerprint(contents, included(p.id, CAT_MOVIE), included(p.id, CAT_SERIES)) == p.vodFeedHash) return@withFeeds
            storeGroups(p.id, feeds)
            val movieCats = included(p.id, CAT_MOVIE)
            val seriesCats = included(p.id, CAT_SERIES)
            load(p.id, feeds, movieCats, seriesCats, onProgress)
            // Of the choices loaded: one changed meanwhile then differs, so the next update loads again.
            db.playlists().setVodFeedHash(p.id, fingerprint(contents, movieCats, seriesCats))
        }
    }

    private class Feed(val file: File, val seriesOnly: Boolean)

    /** Runs [block] with every playlist downloaded (each kept until it ends). */
    private suspend fun <T> withFeeds(
        urls: List<String>,
        proxy: ProxySettings?,
        files: List<File> = emptyList(),
        block: suspend (List<File>) -> T
    ): T = if (files.size == urls.size) {
        block(files)
    } else {
        playlists.withM3u(urls[files.size], proxy = proxy) { withFeeds(urls, proxy, files + it, block) }
    }

    /** Each entry, and whether it's a series episode (else a film). */
    private suspend fun each(feed: Feed, onEntry: suspend (M3uEntry, Boolean) -> Unit) {
        feed.file.bufferedReader().use { reader ->
            M3uParser.parse(reader) { e -> onEntry(e, EpisodeNames.isEpisode(e, feed.seriesOnly)) }
        }
    }

    private suspend fun included(playlistId: Long, type: String) = categories.included(playlistId, type).mapTo(HashSet()) { it.categoryId }

    /** Records each kind's groups first, so new ones get their default inclusion before anything is kept. */
    private suspend fun storeGroups(playlistId: Long, feeds: List<Feed>) {
        val movieGroups = LinkedHashSet<String>()
        val seriesGroups = LinkedHashSet<String>()
        feeds.forEach { feed ->
            each(feed) { e, episode -> (if (episode) seriesGroups else movieGroups) += M3uCategories.idOf(e) }
        }
        store(playlistId, CAT_MOVIE, movieGroups)
        store(playlistId, CAT_SERIES, seriesGroups)
    }

    private suspend fun store(playlistId: Long, type: String, groups: Set<String>) = if (groups.isEmpty()) {
        categories.deleteAll(playlistId, type)
    } else {
        categoryStore.store(playlistId, type, groups.map(M3uCategories::category))
    }

    @Suppress("LongMethod") // one pass over the playlists, writing movies, series and episodes as it goes
    private suspend fun load(
        playlistId: Long,
        feeds: List<Feed>,
        movieCats: Set<String>,
        seriesCats: Set<String>,
        onProgress: (Int) -> Unit
    ) {
        val stamp = System.currentTimeMillis()
        val movies = Batch(BATCH, vod::insertMovies, onProgress)
        val series = Batch(BATCH, vod::insertSeries) { onProgress(movies.count + it) }
        val episodes = Batch(BATCH, vod::insertEpisodes)
        // Ids come from names, like M3U channels', so favourites and progress survive providers rewriting addresses.
        val ids = HashSet<Long>()
        val shows = HashMap<Long, Int>() // series id to episodes so far
        var position = 0
        feeds.forEach { feed ->
            each(feed) { e, episode ->
                val group = M3uCategories.idOf(e)
                // Every entry gets its id, so a repeated name's id doesn't depend on which categories are included.
                val id = playlists.uniqueId(if (episode) "episode|${e.name}" else "movie|${e.name}", ids)
                if (!episode) {
                    if (group !in movieCats) return@each
                    val clean = VodTitles.clean(e.name)
                    movies.add(
                        MovieEntity(playlistId, id, clean.title, e.year ?: clean.year, group, e.logo, null, 0, "", position++, stamp, e.url)
                    )
                    return@each
                }
                if (group !in seriesCats) return@each
                val ep = EpisodeNames.of(e, feed.seriesOnly) ?: return@each
                // The show's name as listed (with any language tag) and its group keep two languages, or a 4K copy, apart.
                // Keyed by the group's id before 1.1.0, which keeps favourites and progress: a show both with no group
                // and in a group called "Other" stays one series, as it was.
                val seriesId = playlists.stableId("series|${M3uCategories.oldId(group) ?: group}|${ep.show.lowercase()}")
                val before = shows[seriesId] ?: 0
                if (before == 0) {
                    val clean = VodTitles.clean(ep.show)
                    series.add(
                        SeriesEntity(playlistId, seriesId, clean.title, e.year ?: clean.year, group, e.logo, null, 0, position++, stamp)
                    )
                }
                shows[seriesId] = before + 1
                val number = ep.episode ?: (before + 1) // unnumbered: in listed order
                episodes.add(EpisodeEntity(playlistId, id, seriesId, ep.season, number, ep.title.orEmpty(), e.url, stamp))
            }
        }
        movies.flush()
        series.flush()
        episodes.flush()
        db.withTransaction {
            vod.deleteStaleMovies(playlistId, stamp)
            vod.deleteStaleSeries(playlistId, stamp)
            vod.deleteStaleEpisodes(playlistId, stamp)
            categories.refreshCounts(playlistId, CAT_MOVIE)
            categories.refreshCounts(playlistId, CAT_SERIES)
        }
    }

    /** No movie or series playlist (any more): nothing of either. */
    private suspend fun clear(p: PlaylistEntity) {
        if (p.vodFeedHash == null && vod.titleCount(p.id) == 0) return
        val dao = db.playlists()
        db.withTransaction {
            dao.deleteMovies(p.id)
            dao.deleteSeries(p.id)
            dao.deleteEpisodes(p.id)
            categories.deleteAll(p.id, CAT_MOVIE)
            categories.deleteAll(p.id, CAT_SERIES)
            dao.setVodFeedHash(p.id, null)
        }
    }

    /** Which address is which, and the playlists' contents (read as bytes only: much cheaper than parsing them). */
    private fun checksum(movies: String, series: String, files: List<File>): Long {
        val crc = CRC32()
        crc.update("movies|$movies|series|$series".toByteArray())
        files.forEach { f -> f.forEachBlock { buffer, n -> crc.update(buffer, 0, n) } }
        return crc.value
    }

    /** [contents] with the categories chosen, and how they're filed: if none changed, a load would change nothing. */
    private fun fingerprint(contents: Long, movieCats: Set<String>, seriesCats: Set<String>): Long {
        val crc = CRC32()
        crc.update("$FILING|$contents".toByteArray())
        mapOf(CAT_MOVIE to movieCats, CAT_SERIES to seriesCats).forEach { (type, cats) ->
            cats.sorted().forEach { crc.update("$type|$it".toByteArray()) }
        }
        return crc.value
    }

    companion object {
        /** What an M3U playlist says about a title beyond its name and picture: nothing. */
        val NO_DETAILS = VodDetails(null, null, null, null, null, null, null, null, null)
        private const val BATCH = 2_000

        /**
         * How titles are filed into categories and given ids: a change to that (M3uCategories, the ids made in load)
         * bumps it, so each playlist is loaded once more. 2: entries with no group apart from "Other" (1.1.0).
         */
        private const val FILING = 2
    }
}
