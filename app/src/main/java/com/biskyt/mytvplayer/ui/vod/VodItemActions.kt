package com.biskyt.mytvplayer.ui.vod

import androidx.compose.runtime.Composable
import com.biskyt.mytvplayer.AppContainer
import com.biskyt.mytvplayer.core.tmdb.TmdbCredits
import com.biskyt.mytvplayer.core.tmdb.TmdbTitle
import com.biskyt.mytvplayer.core.vod.DownloadNames
import com.biskyt.mytvplayer.core.vod.VodTitles
import com.biskyt.mytvplayer.core.xtream.EpisodeInfo
import com.biskyt.mytvplayer.core.xtream.SeriesDetails
import com.biskyt.mytvplayer.core.xtream.VodDetails
import com.biskyt.mytvplayer.data.db.MovieEntity
import com.biskyt.mytvplayer.data.db.SeriesEntity
import com.biskyt.mytvplayer.ui.common.ItemAction
import com.biskyt.mytvplayer.ui.common.markWatched
import com.biskyt.mytvplayer.ui.player.DescribedEpisode
import com.biskyt.mytvplayer.ui.player.episodeDescriptionAction
import java.io.IOException

// What a film's or an episode's options do, wherever they are offered: its page's More…, and a Continue watching card
// on Home (the owner, 2026-11-25), which has only its progress.

/**
 * Starts downloading film [m] of playlist [playlistId] (see Downloads): where it goes, or null if it can't be. It is
 * named with TMDB's title and year when TMDB has the film, the name media servers know it by: [known], or asked here
 * (with [details], which may give its TMDB id) if that look-up has not answered yet, so that the name does not depend
 * on when Download is pressed. Else with the saved name, cleaned again in case it was saved before names were cleaned
 * as they are now.
 */
suspend fun AppContainer.downloadMovie(
    playlistId: Long,
    m: MovieEntity,
    known: TmdbTitle?,
    details: VodDetails? = null
): DownloadNames.Place? {
    val p = playlists.get(playlistId) ?: return null
    val t = known ?: tmdb.movie(m.title, details)
    val saved = VodTitles.clean(m.title, series = false)
    val place = DownloadNames.movie(t?.title ?: saved.title, t?.released?.year ?: m.year ?: saved.year, m.extension)
    startDownload(vod.movieUrl(p, m), place)
    return place
}

/**
 * Starts downloading episode [e] of [show] (see Downloads): where it goes, or null if it can't be. Its series is named as
 * [downloadMovie] names a film, so that every episode goes to one folder.
 */
suspend fun AppContainer.downloadEpisode(
    playlistId: Long,
    show: SeriesEntity?,
    e: EpisodeInfo,
    known: TmdbTitle?,
    details: VodDetails? = null
): DownloadNames.Place? {
    val p = playlists.get(playlistId) ?: return null
    val saved = show?.title ?: e.title
    val t = known ?: tmdb.series(saved, details)
    val place = DownloadNames.episode(t?.title ?: VodTitles.clean(saved, series = true).title, e.season, e.number, e.extension)
    startDownload(vod.episodeUrl(p, e.id, e.extension), place)
    return place
}

/** As [downloadEpisode], for episode [episodeId] of series [seriesId], read from the provider first. */
suspend fun AppContainer.downloadEpisode(playlistId: Long, seriesId: Long, episodeId: Long, known: TmdbTitle?): DownloadNames.Place? {
    val (d, e) = episodeOf(playlistId, seriesId, episodeId) ?: return null
    return downloadEpisode(playlistId, vod.series(playlistId, seriesId), e, known, d.info)
}

/** Episode [episodeId] of series [seriesId] with the series' details, read from the provider; null if it has none. */
suspend fun AppContainer.episodeOf(playlistId: Long, seriesId: Long, episodeId: Long): Pair<SeriesDetails, EpisodeInfo>? {
    val p = playlists.get(playlistId) ?: return null
    val d = vod.seriesDetails(p, seriesId)
    return d.episodes.firstOrNull { it.id == episodeId }?.let { d to it }
}

/**
 * Marks episode [e] of [show] ([seriesId], with its [episodes]) watched, or with [watched] false forgets how far it got:
 * the episode after it is then the one to carry on with.
 */
@Suppress("LongParameterList") // what the progress saved for it needs
suspend fun AppContainer.setEpisodeWatched(
    playlistId: Long,
    seriesId: Long,
    show: SeriesEntity?,
    episodes: List<EpisodeInfo>,
    e: EpisodeInfo,
    watched: Boolean
) {
    if (watched) {
        vod.markEpisodeWatched(playlistId, seriesId, show, episodes, e)
    } else {
        val next = episodes.indexOfFirst { it.id == e.id }.takeIf { it >= 0 }?.let { episodes.getOrNull(it + 1) }
        vod.forgetEpisode(playlistId, e.id, next)
    }
}

/**
 * As [setEpisodeWatched], for episode [episodeId] of series [seriesId], read from the provider first: whether it was
 * saved (not when the provider cannot be reached, or no longer has the episode).
 */
suspend fun AppContainer.setEpisodeWatched(playlistId: Long, seriesId: Long, episodeId: Long, watched: Boolean): Boolean {
    val (d, e) = try {
        episodeOf(playlistId, seriesId, episodeId)
    } catch (_: IOException) {
        null
    } ?: return false
    setEpisodeWatched(playlistId, seriesId, vod.series(playlistId, seriesId), d.episodes, e, watched)
    return true
}

/**
 * An episode's own options, as its series page's More… has them: its description ([described]; [describe] opens it),
 * its own cast and crew ([credits]; [showCast]), Download ([download]), and Watched, unless [finished], or Not watched,
 * if [started] ([setWatched]).
 */
@Composable
@Suppress("LongParameterList") // one for each option
fun episodeOwnActions(
    playlistId: Long,
    described: DescribedEpisode?,
    credits: TmdbCredits?,
    finished: Boolean,
    started: Boolean,
    describe: () -> Unit,
    showCast: () -> Unit,
    download: suspend AppContainer.() -> DownloadNames.Place?,
    setWatched: (Boolean) -> Unit
): List<ItemAction> = listOfNotNull(
    described?.let { episodeDescriptionAction(it, describe) },
    episodeCastAction(credits, showCast),
    downloadAction(playlistId, download),
    if (!finished) markWatched(false, setWatched) else null,
    if (started) markWatched(true, setWatched) else null
)
