package com.biskyt.mytvplayer.ui.player

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.appString
import com.biskyt.mytvplayer.core.xtream.EpisodeInfo
import com.biskyt.mytvplayer.ui.common.ItemAction
import com.biskyt.mytvplayer.ui.common.Muted
import com.biskyt.mytvplayer.ui.common.SidePanel
import com.biskyt.mytvplayer.ui.common.formatDate
import com.biskyt.mytvplayer.ui.common.rememberFirstFocus

/** An episode, and the show it is in (null when not known), as its description tells of them. */
data class DescribedEpisode(val show: String?, val episode: EpisodeInfo)

/**
 * The "Episode description" item that opens [e]'s description with [open]; null when there is nothing to show (no
 * description and no date).
 */
fun episodeDescriptionAction(e: EpisodeInfo, open: () -> Unit): ItemAction? =
    if (e.plot != null || e.airDate != null) ItemAction.Run(appString(R.string.player_description), run = open) else null

/**
 * An episode's description, the same on the series page and over the player (and in MyPlexPlayer): the show, the
 * episode's name, when it was first shown and what it is about. A line is left out when the provider doesn't say.
 */
@Composable
fun EpisodeDescription(d: DescribedEpisode, modifier: Modifier = Modifier) {
    val e = d.episode
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        d.show?.takeIf { it.isNotBlank() }?.let { Muted(it) }
        Text(episodeName(e), style = MaterialTheme.typography.titleLarge)
        e.airDate?.let { Muted(stringResource(R.string.vod_first_shown, formatDate(it))) }
        // At most what fits in a side panel: its text doesn't scroll (the remote has nothing in it to move to).
        e.plot?.let { Text(it, style = MaterialTheme.typography.bodyLarge, maxLines = PLOT_LINES, overflow = TextOverflow.Ellipsis) }
    }
}

/** [EpisodeDescription] in a side panel (the series page's), focused so that Back reaches the panel and closes it. */
@Composable
fun EpisodeDescriptionPanel(d: DescribedEpisode, onDismiss: () -> Unit) {
    SidePanel(onDismiss, dim = false) {
        EpisodeDescription(d, rememberFirstFocus().focusable())
    }
}

private const val PLOT_LINES = 18
