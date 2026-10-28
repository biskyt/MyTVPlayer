package com.biskyt.mytvplayer.ui.player

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
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
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.appString
import com.biskyt.mytvplayer.data.programmeTitle
import com.biskyt.mytvplayer.ui.LocalNavigator
import com.biskyt.mytvplayer.ui.common.ChannelLogo
import com.biskyt.mytvplayer.ui.common.LocalTouch
import com.biskyt.mytvplayer.ui.common.Muted
import com.biskyt.mytvplayer.ui.common.ProgrammeAbout
import com.biskyt.mytvplayer.ui.common.ScheduleList
import com.biskyt.mytvplayer.ui.common.SidePanel
import com.biskyt.mytvplayer.ui.common.TouchStepButtons
import com.biskyt.mytvplayer.ui.common.TvCard
import com.biskyt.mytvplayer.ui.common.cycle
import com.biskyt.mytvplayer.ui.common.displayLanguage
import com.biskyt.mytvplayer.ui.common.formatRange
import com.biskyt.mytvplayer.ui.common.perform
import com.biskyt.mytvplayer.ui.common.rememberFirstFocus
import com.biskyt.mytvplayer.ui.common.rememberSchedule
import com.biskyt.mytvplayer.ui.theme.LocalAccent
import com.biskyt.mytvplayer.ui.theme.Palette

/** The channel guide beside the video (agreed side-overlay design); ◀ ▶ previews other channels' guides. */
@Composable
fun GuidePanel(vm: PlayerViewModel) {
    val ch = vm.guideChannel ?: return
    SidePanel(onDismiss = vm::closeGuide, dim = false) {
        Column(
            Modifier.panelKeys(vm).onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionLeft -> {
                        vm.previewGuide(-1)
                        true
                    }
                    Key.DirectionRight -> {
                        vm.previewGuide(1)
                        true
                    }
                    else -> false
                }
            },
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChannelLogo(ch.logoUrl, ch.name, 64.dp, 40.dp)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(ch.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    if (!LocalTouch.current) Muted(stringResource(R.string.player_guide_hint))
                }
            }
            TouchStepButtons(stringResource(R.string.player_guide_previous), stringResource(R.string.player_guide_next), vm::previewGuide)
            ScheduleList(rememberSchedule(ch.playlistId, ch.streamId), onSelect = { vm.watchGuideChannel() })
        }
    }
}

@Composable
fun PlayerSheet(sheet: PlaybackViewModel.Sheet, vm: PlaybackViewModel) {
    val engine = vm.engine ?: return
    val nav = LocalNavigator.current
    val close = { vm.sheet = null }
    SidePanel(onDismiss = close, dim = false) {
        // Again when More opens another sheet in its place.
        val first = rememberFirstFocus(sheet)
        LazyColumn(Modifier.panelKeys(vm), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    if (options.isEmpty()) item { Muted(stringResource(R.string.player_no_audio_tracks)) }
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
                    item { Header(stringResource(R.string.player_more), null) }
                    val actions = vm.menuActions()
                    itemsIndexed(actions) { i, a ->
                        // A screen pushed over the player stops it while it's covered.
                        Choice(a.label, false, if (i == 0) first else Modifier) { a.perform(nav, close, vm::beforeLeaving) }
                    }
                    item {
                        // What it says now, and what it flashes once pressed (the next mode).
                        val label = stringResource(R.string.player_picture, stringResource(vm.picture.label))
                        val next = vm.picture.next
                        val nextLabel = stringResource(R.string.player_picture, stringResource(next.label))
                        Choice(label, false, if (actions.isEmpty()) first else Modifier) {
                            vm.picture = next
                            vm.flash(nextLabel)
                        }
                    }
                    item {
                        val next = listOf(0, 15, 30, 60, 90, 120).cycle(vm.sleepMinutes)
                        val sleep = if (vm.sleepMinutes == 0) {
                            stringResource(R.string.player_sleep_timer_off)
                        } else {
                            stringResource(R.string.player_sleep_timer_minutes, vm.sleepMinutes)
                        }
                        Choice(sleep, false) { vm.setSleepTimer(next) }
                    }
                    item { Muted(stringResource(R.string.player_audio_output, vm.audioLabel), maxLines = 2) }
                    item { Muted(videoInfo(vm.tracks), maxLines = 2) }
                }
                PlaybackViewModel.Sheet.ABOUT -> vm.about?.let { p ->
                    item { Header(programmeTitle(p.title), formatRange(p.startMs, p.stopMs)) }
                    // Focusable, so the remote's keys (Back, play/pause) reach the panel.
                    item { Column(first.focusable()) { ProgrammeAbout(p, maxLines = 18) } }
                }
                PlaybackViewModel.Sheet.DESCRIPTION -> vm.description?.let { text ->
                    item { Header(stringResource(R.string.player_description), null) }
                    item { Column(first.focusable()) { Muted(text, maxLines = 18) } }
                }
            }
        }
    }
}

@Composable
private fun Header(title: String, subtitle: String?) {
    Column(Modifier.padding(bottom = 8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        if (!subtitle.isNullOrEmpty()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = LocalAccent.current.soft)
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    TvCard(onClick = onClick, selected = selected, modifier = modifier.fillMaxWidth(), color = Palette.surface) {
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
    }
}

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
