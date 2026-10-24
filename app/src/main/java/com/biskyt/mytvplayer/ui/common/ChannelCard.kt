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
 * far through it is ([now]: the minute clock). [loader] fetches what's on once the card is shown.
 */
@Composable
fun ChannelCard(
    c: ChannelEntity,
    loader: NowNextLoader,
    now: Long,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    favourite: Boolean = false,
    onClick: () -> Unit
) {
    LaunchedEffect(c.streamId) { loader.request(c.streamId) }
    val nn = loader.entries[c.streamId]
    TvCard(onClick = onClick, onLongClick = onLongClick, modifier = modifier.width(272.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChannelLogo(c.logoUrl, c.name, 64.dp, 38.dp, favourite)
                Muted(c.name, Modifier.padding(start = 10.dp).weight(1f, fill = false))
                CatchupMark(c, Modifier.padding(start = 6.dp))
            }
            Text(
                nn?.now?.let { programmeTitle(it.title) } ?: " ",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Muted(
                nn?.now?.let { on ->
                    val until = formatTime(on.stopMs)
                    nn.next?.let { stringResource(R.string.home_until_then, until, programmeTitle(it.title)) }
                        ?: stringResource(R.string.home_until, until)
                } ?: stringResource(R.string.common_no_guide)
            )
            ProgressLine(nn?.progress(now) ?: 0f, Modifier.fillMaxWidth())
        }
    }
}
