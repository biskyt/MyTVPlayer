package com.biskyt.mytvplayer.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.data.db.ChannelEntity
import com.biskyt.mytvplayer.data.programmeTitle

/**
 * A channel as a card (on Home and in Search): its logo and name, what's on now and until when, what's next, and how
 * far through it is ([now]: the minute clock). [loader] fetches what's on once the card is shown. [detail] (search's
 * category) takes the name's second line, so that the card keeps its height.
 */
@Composable
fun ChannelCard(
    c: ChannelEntity,
    loader: NowNextLoader,
    now: Long,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    favourite: Boolean = false,
    detail: String? = null,
    onClick: () -> Unit
) {
    LaunchedEffect(c.streamId) { loader.request(c.streamId) }
    val nn = loader.entries[c.streamId]
    val ambient = modifier.ambientSource(LocalAmbience.current, AmbientSource.logo(c.logoUrl))
    TvCard(onClick = onClick, onLongClick = onLongClick, modifier = ambient.width(272.dp), glow = true) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChannelLogo(c.logoUrl, c.name, 64.dp, 38.dp, favourite)
                // Two lines fit beside the logo, so cards keep one height whatever their names.
                Column(Modifier.padding(start = 10.dp).weight(1f, fill = false)) {
                    Muted(c.name, maxLines = if (detail == null) 2 else 1)
                    detail?.let { Muted(it) }
                }
                CatchupMark(c, Modifier.padding(start = 6.dp))
            }
            Text(
                nn?.now?.let { programmeTitle(it.title) } ?: " ",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Muted(nn?.let { nowLine(it, NowLine.UNTIL_THEN) } ?: stringResource(R.string.common_no_guide))
            ProgressLine(nn?.progress(now) ?: 0f, Modifier.fillMaxWidth())
        }
    }
}

/** How [nowLine] tells what is on. */
enum class NowLine {
    /** Until when it runs, and what comes then: "Until 21:00, then News" (Home's cards). */
    UNTIL_THEN,

    /** What comes next and when, else until when it runs: "Next 21:00 News" (the TV's channel list). */
    NEXT,

    /** Its title and until when it runs: "News · Until 21:00" (the touch layouts' channel cards). */
    TITLE_UNTIL
}

/** The line about what is on now from [nn], in the [style] given; "No guide" with nothing on now. */
@Composable
fun nowLine(nn: NowNext, style: NowLine): String {
    val on = nn.now ?: return stringResource(R.string.common_no_guide)
    val until = formatTime(on.stopMs)
    val next = nn.next
    return when (style) {
        NowLine.UNTIL_THEN -> next?.let { stringResource(R.string.home_until_then, until, programmeTitle(it.title)) }
            ?: stringResource(R.string.home_until, until)
        NowLine.NEXT -> next?.let { stringResource(R.string.live_next, formatTime(it.startMs), programmeTitle(it.title)) }
            ?: stringResource(R.string.live_until, until)
        NowLine.TITLE_UNTIL -> programmeTitle(on.title) + " · " + stringResource(R.string.live_until, until)
    }
}
