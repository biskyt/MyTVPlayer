package com.biskyt.mytvplayer.ui.player

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.appString
import com.biskyt.mytvplayer.core.tmdb.TmdbEpisode
import com.biskyt.mytvplayer.core.xtream.EpisodeInfo
import com.biskyt.mytvplayer.ui.common.BoardPanel
import com.biskyt.mytvplayer.ui.common.BoardPanelGap
import com.biskyt.mytvplayer.ui.common.BoardPanelSubtitle
import com.biskyt.mytvplayer.ui.common.BoardPanelText
import com.biskyt.mytvplayer.ui.common.BoardPanelTitle
import com.biskyt.mytvplayer.ui.common.ItemAction
import com.biskyt.mytvplayer.ui.common.formatDate
import com.biskyt.mytvplayer.ui.common.rememberFirstFocus
import com.biskyt.mytvplayer.ui.theme.bd
import com.biskyt.mytvplayer.ui.vod.TmdbScore
import com.biskyt.mytvplayer.ui.vod.episodePeople

/**
 * An episode, and the show it is in (null when not known), as its description tells of them; with TMDB's episode
 * ([tmdb], with a TMDB key) for its score and who directed and wrote it.
 */
data class DescribedEpisode(val show: String?, val episode: EpisodeInfo, val tmdb: TmdbEpisode? = null)

/**
 * The "Episode description" item that opens [d]'s description with [open]; null when there is nothing to show (no
 * description, no date and nothing from TMDB).
 */
fun episodeDescriptionAction(d: DescribedEpisode, open: () -> Unit): ItemAction? {
    val e = d.episode
    val tmdb = d.tmdb?.let { it.score != null || it.directors.isNotEmpty() || it.writers.isNotEmpty() } == true
    return if (e.plot != null || e.airDate != null || tmdb) ItemAction.Run(appString(R.string.player_description), run = open) else null
}

/**
 * An episode's description, the same on the series page and over the player (and in MyPlexPlayer): the show, the
 * episode's name, when it was first shown, with TMDB its score and who directed and wrote it, and what it is about. A
 * line is left out when neither the provider nor TMDB says.
 */
@Composable
fun EpisodeDescription(d: DescribedEpisode, modifier: Modifier = Modifier) {
    val e = d.episode
    val t = d.tmdb
    Column(modifier, verticalArrangement = Arrangement.spacedBy(BoardPanelGap)) {
        d.show?.takeIf { it.isNotBlank() }?.let { BoardPanelSubtitle(it) }
        BoardPanelTitle(episodeName(e, d.show), small = true)
        e.airDate?.let { BoardPanelSubtitle(stringResource(R.string.vod_first_shown, formatDate(it))) }
        t?.score?.let { TmdbScore(it) }
        t?.let { episodePeople(it) }?.let { BoardPanelSubtitle(it) }
        Spacer(Modifier.height(4.bd))
        // At most what fits in a side panel: its text doesn't scroll (the remote has nothing in it to move to).
        e.plot?.let { BoardPanelText(it, maxLines = PLOT_LINES) }
    }
}

/** [EpisodeDescription] in a side panel (the series page's), focused so that Back reaches the panel and closes it. */
@Composable
fun EpisodeDescriptionPanel(d: DescribedEpisode, onDismiss: () -> Unit) {
    BoardPanel(onDismiss, dim = false, scroll = false) {
        EpisodeDescription(d, rememberFirstFocus().focusable())
    }
}

/** What fits in a side panel (here, and a film's or series' plot): its text does not scroll. */
internal const val PLOT_LINES = 18
