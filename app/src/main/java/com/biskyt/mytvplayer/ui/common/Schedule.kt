package com.biskyt.mytvplayer.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.container
import com.biskyt.mytvplayer.data.db.ChannelEntity
import com.biskyt.mytvplayer.data.db.ChannelProgramme
import com.biskyt.mytvplayer.data.programmeTitle
import com.biskyt.mytvplayer.hideSystemBars
import com.biskyt.mytvplayer.ui.theme.Palette
import com.biskyt.mytvplayer.ui.theme.bd

/** Programmes for one channel from now until tomorrow, loaded from the local guide. */
@Composable
fun rememberSchedule(playlistId: Long, streamId: Long): List<ChannelProgramme>? {
    val epg = LocalContext.current.container.epg
    val guideVersion by epg.changes.collectAsState()
    val list by produceState<List<ChannelProgramme>?>(null, playlistId, streamId, guideVersion) {
        val now = System.currentTimeMillis()
        value = epg.programmes(playlistId, listOf(streamId), now, now + 24 * 3_600_000L)[streamId].orEmpty()
    }
    return list
}

/**
 * [rememberSchedule] with, before it, what channel [ch] showed that its catch-up replays (the catch-up page's list:
 * the provider's, the guide's and hour slots; see CatchupRepository), when it has catch-up (the owner, 2026-11-22).
 * Without catch-up, or when the provider can't be reached, only the schedule.
 */
@Composable
fun rememberScheduleWithPast(ch: ChannelEntity): List<ChannelProgramme>? {
    val c = LocalContext.current.container
    val upcoming = rememberSchedule(ch.playlistId, ch.streamId)
    val past by produceState(emptyList<ChannelProgramme>(), ch.playlistId, ch.streamId, ch.archiveDays) {
        if (ch.archiveDays <= 0) return@produceState
        val p = c.playlists.get(ch.playlistId) ?: return@produceState
        value = runCatching { c.catchup.playable(c.catchup.listing(p, ch), ch) }.getOrDefault(emptyList())
            .map { ChannelProgramme(ch.streamId, it.startMs, it.stopMs, it.title, null, it.description) }
    }
    return upcoming?.let { list ->
        val from = list.firstOrNull()?.startMs ?: System.currentTimeMillis()
        past.filter { it.startMs < from } + list
    }
}

/**
 * Vertical list of programmes; the current one is marked and gets focus (the first, without one), again when the list
 * changes channel, so that programmes before it (catch-up, see [rememberScheduleWithPast]) are above it, Up from it.
 * Those that ended show the catch-up mark. With none, the message takes focus (so keys still reach the panel around it).
 */
@Composable
fun ScheduleList(programmes: List<ChannelProgramme>?, modifier: Modifier = Modifier, onSelect: (ChannelProgramme) -> Unit = {}) {
    val now by rememberMinuteClock()
    val first = rememberFirstFocus(programmes?.let { it.firstOrNull()?.streamId ?: NO_PROGRAMMES })
    when {
        programmes == null -> Unit
        programmes.isEmpty() -> BoardPanelSubtitle(stringResource(R.string.common_no_guide_for_channel), modifier.then(first).focusable())
        else -> {
            val onNow = programmes.indexOfFirst { it.stopMs > now }.coerceAtLeast(0)
            val state = rememberLazyListState(initialFirstVisibleItemIndex = onNow)
            LazyColumn(modifier, state = state, verticalArrangement = Arrangement.spacedBy(BoardPanelGap)) {
                itemsIndexed(programmes, key = { _, p -> p.startMs }) { i, p ->
                    ScheduleRow(p, now, if (i == onNow) first else Modifier) { onSelect(p) }
                }
            }
        }
    }
}

/** One programme of [ScheduleList]: its time (with the catch-up mark once it ended), title, On now, and a line about it. */
@Composable
private fun ScheduleRow(p: ChannelProgramme, now: Long, modifier: Modifier, onClick: () -> Unit) {
    val live = p.isLive(now)
    // A panel's row (see BoardPanel): filled with the accent colour while it has focus.
    BoardRow(onClick = onClick, modifier = modifier) {
        Row(Modifier.padding(horizontal = 14.bd, vertical = 8.bd), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.width(52.dp)) {
                Text(formatTime(p.startMs), style = MaterialTheme.typography.bodyMedium, color = cardColor(Palette.textMuted))
                if (p.stopMs <= now) {
                    Icon(
                        AppIcons.Catchup,
                        stringResource(R.string.common_catchup),
                        tint = cardColor(Palette.textDim),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(programmeTitle(p.title), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (live) OnNowLabel()
                val detail = p.subtitle ?: p.description
                if (detail != null) {
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = cardColor(Palette.textMuted),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/** [ScheduleList]'s focus key for an empty list (programmes' keys are their channel's stream id). */
private const val NO_PROGRAMMES = -1L

/** Marks a programme on now, wherever programmes are listed. */
@Composable
fun OnNowLabel() =
    Text(stringResource(R.string.common_on_now), style = MaterialTheme.typography.labelSmall, color = cardColor(Palette.live))

/**
 * A panel sliding in from the right over a dimmed screen. Shown as a dialog so focus stays inside it and Back closes it.
 */
@Composable
fun SidePanel(
    onDismiss: () -> Unit,
    width: Dp = 430.dp,
    dim: Boolean = true,
    padding: PaddingValues = PaddingValues(28.dp),
    content: @Composable () -> Unit
) {
    // A dialog is its own window, which resets the density to the system's and the context to the Activity's; keep
    // the app's scaling (see MyTvTheme) and language (see InAppLanguage) so panels match everything else.
    val density = LocalDensity.current
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // Not the screen's: an item in a panel must not become the one the screen gives focus back to.
        CompositionLocalProvider(
            LocalDensity provides density,
            LocalContext provides context,
            LocalConfiguration provides configuration,
            LocalReturnFocus provides null
        ) {
            // A dialog is its own window: on a phone its bars would come back over the app otherwise.
            if (LocalTouch.current) {
                val dialogView = LocalView.current
                SideEffect { (dialogView.parent as? DialogWindowProvider)?.window?.hideSystemBars() }
            }
            Box(
                Modifier.fillMaxSize().ignoreHeldButtons()
                    // On a touch screen, tapping beside the panel closes it.
                    .onPlainTap(onDismiss)
                    .background(if (dim) Palette.background.copy(alpha = 0.6f) else Color.Transparent),
                contentAlignment = Alignment.CenterEnd
            ) {
                Box(
                    Modifier.fillMaxHeight().width(width)
                        .onPlainTap {} // taps on the panel itself don't close it
                        .background(Palette.surface.copy(alpha = 0.97f)).padding(padding)
                ) { content() }
            }
        }
    }
}
