package com.biskyt.mytvplayer.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.biskyt.mytvplayer.container
import com.biskyt.mytvplayer.data.db.ChannelProgramme
import kotlinx.coroutines.delay

/**
 * [p] with its description once read (null until then, or while there's no [p]), after [delayMs] (e.g. so moving on
 * through the guide doesn't read each one passed).
 */
@Composable
fun rememberDetails(playlistId: Long, p: ChannelProgramme?, delayMs: Long = 0): State<ChannelProgramme?> {
    val epg = LocalContext.current.container.epg
    return produceState<ChannelProgramme?>(null, playlistId, p) {
        // Not the last programme's while this one's is read.
        value = null
        delay(delayMs)
        value = p?.let { epg.withDetails(playlistId, it) }
    }
}

/**
 * What the guide says about [p]: its episode (subtitle, and season and episode) on one line, then its description in
 * up to [maxLines] lines. Nothing when the guide says nothing.
 */
@Composable
fun ProgrammeAbout(p: ChannelProgramme, maxLines: Int, modifier: Modifier = Modifier) {
    val season = p.season
    val number = p.episode
    val episode = listOfNotNull(p.subtitle, if (season != null && number != null) shortEpisodeLabel(season, number) else null)
        .joinToString(" · ")
    Column(modifier) {
        if (episode.isNotEmpty()) Muted(episode)
        p.description?.let { Muted(it, maxLines = maxLines) }
    }
}
