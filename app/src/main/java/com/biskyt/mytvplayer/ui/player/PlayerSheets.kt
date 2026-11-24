package com.biskyt.mytvplayer.ui.player

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Tracks
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.appString
import com.biskyt.mytvplayer.data.programmeTitle
import com.biskyt.mytvplayer.ui.LocalNavigator
import com.biskyt.mytvplayer.ui.common.BoardOption
import com.biskyt.mytvplayer.ui.common.BoardPanel
import com.biskyt.mytvplayer.ui.common.BoardPanelGap
import com.biskyt.mytvplayer.ui.common.BoardPanelSubtitle
import com.biskyt.mytvplayer.ui.common.BoardPanelText
import com.biskyt.mytvplayer.ui.common.BoardPanelTitle
import com.biskyt.mytvplayer.ui.common.ChannelLogo
import com.biskyt.mytvplayer.ui.common.ItemAction
import com.biskyt.mytvplayer.ui.common.ItemActionRow
import com.biskyt.mytvplayer.ui.common.LocalTouch
import com.biskyt.mytvplayer.ui.common.ProgrammeAbout
import com.biskyt.mytvplayer.ui.common.ScheduleList
import com.biskyt.mytvplayer.ui.common.TouchStepButtons
import com.biskyt.mytvplayer.ui.common.displayLanguage
import com.biskyt.mytvplayer.ui.common.formatRange
import com.biskyt.mytvplayer.ui.common.perform
import com.biskyt.mytvplayer.ui.common.rememberFirstFocus
import com.biskyt.mytvplayer.ui.common.rememberScheduleWithPast
import com.biskyt.mytvplayer.ui.theme.Palette
import com.biskyt.mytvplayer.ui.theme.bd
import kotlinx.coroutines.delay

/**
 * The channel guide beside the video (agreed side-overlay design); ◀ ▶ previews other channels' guides. A channel with
 * catch-up also lists what it showed, above what is on now: Up goes back in time, and OK replays one.
 */
@Composable
fun <T> GuidePanel(vm: T) where T : PlaybackViewModel, T : GuidePlayer {
    val guide = vm.guide
    val ch = guide.channel ?: return
    BoardPanel(onDismiss = vm::closeGuide, dim = false, scroll = false) {
        // Inside the panel's window: keys reach it there.
        Column(
            Modifier.panelKeys(vm).onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionLeft -> {
                        guide.step(-1)
                        true
                    }
                    Key.DirectionRight -> {
                        guide.step(1)
                        true
                    }
                    else -> false
                }
            },
            verticalArrangement = Arrangement.spacedBy(10.bd)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChannelLogo(ch.logoUrl, ch.name, 64.dp, 40.dp)
                Column(Modifier.padding(start = 12.dp)) {
                    BoardPanelTitle(ch.name, small = true)
                    if (!LocalTouch.current) BoardPanelSubtitle(stringResource(R.string.player_guide_hint))
                }
            }
            TouchStepButtons(stringResource(R.string.player_guide_previous), stringResource(R.string.player_guide_next), guide::step)
            // With catch-up, what it showed is there too, above what is on now: one of those replays from its start.
            ScheduleList(rememberScheduleWithPast(ch), onSelect = { p ->
                if (ch.archiveDays > 0 && p.stopMs <= System.currentTimeMillis()) vm.watchFromGuide(p.startMs) else vm.watchGuideChannel()
            })
        }
    }
}

@Composable
fun PlayerSheet(sheet: PlaybackViewModel.Sheet, vm: PlaybackViewModel) {
    val engine = vm.engine ?: return
    val nav = LocalNavigator.current
    val close = { vm.sheet = null }
    BoardPanel(onDismiss = close, dim = false, scroll = false) {
        // Again when More opens another sheet in its place.
        val first = rememberFirstFocus(sheet)
        LazyColumn(Modifier.panelKeys(vm), verticalArrangement = Arrangement.spacedBy(BoardPanelGap)) {
            when (sheet) {
                PlaybackViewModel.Sheet.AUDIO -> {
                    item { Header(stringResource(R.string.player_audio), vm.audioLabel) }
                    val options = trackOptions(vm.tracks, C.TRACK_TYPE_AUDIO)
                    items(options) { o ->
                        Choice(o.label, o.selected, if (o == options.first()) first else Modifier) {
                            close()
                            vm.chooseTrack(C.TRACK_TYPE_AUDIO, o.group, o.index, o.label)
                        }
                    }
                    if (options.isEmpty()) item { BoardPanelSubtitle(stringResource(R.string.player_no_audio_tracks)) }
                }
                PlaybackViewModel.Sheet.SUBTITLES -> {
                    item { Header(stringResource(R.string.player_subtitles), null) }
                    val options = trackOptions(vm.tracks, C.TRACK_TYPE_TEXT)
                    item {
                        Choice(stringResource(R.string.common_subtitles_off), options.none { it.selected }, first) {
                            engine.selectTrack(C.TRACK_TYPE_TEXT, null, 0)
                            close()
                            vm.flash(appString(R.string.player_subtitles_off))
                        }
                    }
                    items(options) { o ->
                        Choice(o.label, o.selected) {
                            close()
                            vm.chooseTrack(C.TRACK_TYPE_TEXT, o.group, o.index, o.label)
                        }
                    }
                }
                PlaybackViewModel.Sheet.MORE -> {
                    item { Header(vm.menuTitle() ?: stringResource(R.string.player_more), null) }
                    // Drawn as every options panel draws them (the series page's): headings, help lines, links.
                    val actions = vm.menuActions()
                    val firstOption = actions.indexOfFirst { it !is ItemAction.Heading }
                    itemsIndexed(actions) { i, a ->
                        // A screen pushed over the player stops it while it's covered.
                        ItemActionRow(a, if (i == firstOption) first else Modifier) { a.perform(nav, close, vm::beforeLeaving) }
                    }
                    // The player's own options, under their own heading (the owner, 2026-11-24).
                    item { ItemActionRow(ItemAction.Heading(stringResource(R.string.player_playback_heading))) {} }
                    item {
                        // What it says now, and what it flashes once pressed (the next mode).
                        val label = stringResource(R.string.player_picture, stringResource(vm.picture.label))
                        val next = vm.picture.next
                        val nextLabel = stringResource(R.string.player_picture, stringResource(next.label))
                        Choice(label, false, if (firstOption < 0) first else Modifier) {
                            vm.picture = next
                            vm.flash(nextLabel)
                        }
                    }
                    item { Choice(stringResource(R.string.player_stream_info), false) { vm.sheet = PlaybackViewModel.Sheet.STREAM_INFO } }
                    item { BoardPanelSubtitle(stringResource(R.string.player_audio_output, vm.audioLabel), Modifier.padding(top = 6.bd)) }
                    item { BoardPanelSubtitle(videoInfo(vm.tracks)) }
                }
                PlaybackViewModel.Sheet.STREAM_INFO -> {
                    item { Header(stringResource(R.string.player_stream_info), null) }
                    // Focusable, so the remote's keys (Back, play/pause) reach the panel. Long addresses wrap.
                    item {
                        // Read again each second while open: the buffer and the formats change as the stream plays.
                        val lines by produceState(streamInfo(engine, vm.audioLabel), engine) {
                            while (true) {
                                delay(INFO_REFRESH_MS)
                                value = streamInfo(engine, vm.audioLabel)
                            }
                        }
                        Column(first.focusable(), verticalArrangement = Arrangement.spacedBy(6.bd)) {
                            lines.forEach { BoardPanelText(it, maxLines = 6, color = Palette.textDim) }
                        }
                    }
                }
                PlaybackViewModel.Sheet.ABOUT -> vm.about?.let { p ->
                    item { Header(programmeTitle(p.title), formatRange(p.startMs, p.stopMs)) }
                    // Focusable, so the remote's keys (Back, play/pause) reach the panel.
                    item { Column(first.focusable()) { ProgrammeAbout(p, maxLines = 18) } }
                }
            }
        }
    }
}

@Composable
private fun Header(title: String, subtitle: String?) {
    Column(Modifier.padding(bottom = 6.bd)) {
        BoardPanelTitle(title)
        if (!subtitle.isNullOrEmpty()) BoardPanelSubtitle(subtitle)
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) =
    BoardOption(label, modifier = modifier, selected = selected, onClick = onClick)

private data class TrackOption(val label: String, val group: Tracks.Group, val index: Int, val selected: Boolean)

private fun trackOptions(tracks: Tracks, type: Int): List<TrackOption> = tracks.groups.filter { it.type == type }.flatMap { g ->
    (0 until g.length).filter { g.isTrackSupported(it) }.map { i ->
        val f = g.getTrackFormat(i)
        val lang = f.language?.let(::displayLanguage)
        val parts = listOfNotNull(
            f.label ?: lang ?: appString(R.string.player_track_number, i + 1),
            if ((f.selectionFlags and C.SELECTION_FLAG_FORCED) != 0) appString(R.string.player_track_forced) else null,
            f.channelCount.takeIf { type == C.TRACK_TYPE_AUDIO && it > 2 }?.let { appString(R.string.player_track_channels, it) }
        )
        TrackOption(parts.joinToString(" · "), g, i, g.isTrackSelected(i))
    }
}

private fun videoInfo(tracks: Tracks): String {
    val f =
        tracks.groups.firstOrNull {
            it.type == C.TRACK_TYPE_VIDEO && it.isSelected
        }?.let { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let(g::getTrackFormat) }
            ?: return appString(R.string.player_video_unknown)
    return appString(R.string.player_video_info, f.width, f.height, f.sampleMimeType?.substringAfter('/') ?: "?")
}

private const val INFO_REFRESH_MS = 1_000L
