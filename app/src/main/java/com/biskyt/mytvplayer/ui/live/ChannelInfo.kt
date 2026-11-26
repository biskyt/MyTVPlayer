package com.biskyt.mytvplayer.ui.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.biskyt.mytvplayer.AppContainer
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.container
import com.biskyt.mytvplayer.data.categoryLabel
import com.biskyt.mytvplayer.data.db.CAT_LIVE
import com.biskyt.mytvplayer.data.db.ChannelEntity
import com.biskyt.mytvplayer.data.db.MappingInfo
import com.biskyt.mytvplayer.data.label
import com.biskyt.mytvplayer.ui.common.BoardFact
import com.biskyt.mytvplayer.ui.common.formatDayTime
import com.biskyt.mytvplayer.ui.common.rememberFirstFocus
import com.biskyt.mytvplayer.ui.theme.bd

/**
 * What Channel info shows about a channel (the owner, 2026-11-15). The rest is on the channel itself: its number and
 * catch-up days.
 */
data class ChannelInfo(val category: String, val playlist: String?, val guide: MappingInfo?, val lastWatched: Long?)

/**
 * The channel options' Channel info: its category, playlist, TV guide, guide ID, catch-up and when it was last watched.
 * Read only when it opens (nothing is read for the channel list or the options themselves).
 */
@Composable
fun ChannelInfoView(ch: ChannelEntity) {
    val c = LocalContext.current.container
    val info by produceState<ChannelInfo?>(null, ch) { value = c.channelInfo(ch) }
    val i = info ?: return
    // Each fact takes the remote (so that Back reaches the panel), the first with focus.
    Column(verticalArrangement = Arrangement.spacedBy(4.bd)) {
        BoardFact(stringResource(R.string.live_info_category), i.category, LABEL_WIDTH, rememberFirstFocus())
        i.playlist?.let { BoardFact(stringResource(R.string.live_info_playlist), it, LABEL_WIDTH) }
        BoardFact(
            stringResource(R.string.live_info_guide),
            i.guide?.let {
                guideText(it)
            } ?: stringResource(R.string.live_guide_no_guide),
            LABEL_WIDTH
        )
        ch.providerEpgId?.takeIf { it.isNotBlank() }?.let { BoardFact(stringResource(R.string.live_info_guide_id), it, LABEL_WIDTH) }
        val catchup = if (ch.archiveDays > 0) {
            pluralStringResource(R.plurals.common_catchup_days, ch.archiveDays, ch.archiveDays)
        } else {
            stringResource(R.string.live_info_none)
        }
        BoardFact(stringResource(R.string.common_catchup), catchup, LABEL_WIDTH)
        // Known only while it is among the recent channels (see RecentEntity).
        i.lastWatched?.let { BoardFact(stringResource(R.string.live_info_last_watched), formatDayTime(it), LABEL_WIDTH) }
    }
}

/** What Channel info shows about [ch]: read only when it opens (see ChannelInfoView). */
private suspend fun AppContainer.channelInfo(ch: ChannelEntity) = ChannelInfo(
    category = db.categories().get(ch.playlistId, CAT_LIVE, ch.categoryId)?.label ?: categoryLabel("", null),
    playlist = playlists.get(ch.playlistId)?.name,
    guide = epg.mappingInfo(ch.playlistId, listOf(ch.streamId)).firstOrNull()?.takeIf { it.xmlId != null },
    lastWatched = db.favourites().watchedAt(ch.playlistId, ch.streamId),
)

/** The guide channel and the guide it comes from, who chose it, and its time shift. */
@Composable
private fun guideText(m: MappingInfo): String = listOfNotNull(
    stringResource(R.string.live_info_guide_value, m.displayName ?: m.xmlId.orEmpty(), m.sourceName.orEmpty()),
    stringResource(if (m.manual) R.string.live_guide_tab_manual else R.string.live_guide_tab_auto),
    m.offsetMinutes.takeIf { it != 0 }?.let { stringResource(R.string.live_guide_time_shift, it / 60) },
).joinToString(" · ")

private val LABEL_WIDTH = 110.bd
