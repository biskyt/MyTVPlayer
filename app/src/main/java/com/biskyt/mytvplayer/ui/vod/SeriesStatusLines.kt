package com.biskyt.mytvplayer.ui.vod

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.core.tmdb.SeriesStatus
import com.biskyt.mytvplayer.core.tmdb.TmdbTitle
import com.biskyt.mytvplayer.ui.common.formatDate

// What TMDB says of a series as a whole, for its library page and its TMDB page.

/** "Continuing · 2 seasons · 18 episodes": [t]'s status and counts on TMDB; null when TMDB gives none of them. */
@Composable
fun seriesStatusLine(t: TmdbTitle?): String? {
    if (t == null) return null
    return listOfNotNull(
        t.status?.let { stringResource(statusName(it)) },
        t.seasonCount?.let { pluralStringResource(R.plurals.vod_seasons, it, it) },
        t.episodeCount?.let { pluralStringResource(R.plurals.vod_episode_count, it, it) },
    ).joinToString(" · ").ifEmpty { null }
}

/** "Next episode: 4 Dec 2026 · S2E9"; null when TMDB knows of no next episode, or not its date. */
@Composable
fun nextEpisodeLine(t: TmdbTitle?): String? {
    val next = t?.nextEpisode ?: return null
    val date = next.airDate ?: return null
    val code = stringResource(R.string.home_episode_code, next.season, next.number)
    return stringResource(R.string.vod_next_episode, "${formatDate(date)} · $code")
}

private fun statusName(s: SeriesStatus) = when (s) {
    SeriesStatus.CONTINUING -> R.string.vod_status_continuing
    SeriesStatus.ENDED -> R.string.vod_status_ended
    SeriesStatus.CANCELLED -> R.string.vod_status_cancelled
    SeriesStatus.IN_PRODUCTION -> R.string.vod_status_in_production
    SeriesStatus.PLANNED -> R.string.vod_status_planned
    SeriesStatus.PILOT -> R.string.vod_status_pilot
}
