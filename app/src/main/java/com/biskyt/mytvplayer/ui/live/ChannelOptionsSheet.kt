package com.biskyt.mytvplayer.ui.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.core.epg.ProgrammeKind
import com.biskyt.mytvplayer.data.db.CAT_LIVE
import com.biskyt.mytvplayer.data.db.ChannelEntity
import com.biskyt.mytvplayer.data.programmeTitle
import com.biskyt.mytvplayer.ui.LocalNavigator
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.common.ChannelLogo
import com.biskyt.mytvplayer.ui.common.MenuOption
import com.biskyt.mytvplayer.ui.common.Muted
import com.biskyt.mytvplayer.ui.common.ProgrammeAbout
import com.biskyt.mytvplayer.ui.common.ScheduleList
import com.biskyt.mytvplayer.ui.common.SidePanel
import com.biskyt.mytvplayer.ui.common.collectIds
import com.biskyt.mytvplayer.ui.common.favouriteLabel
import com.biskyt.mytvplayer.ui.common.formatTime
import com.biskyt.mytvplayer.ui.common.numberLabel
import com.biskyt.mytvplayer.ui.common.rememberDetails
import com.biskyt.mytvplayer.ui.common.rememberFirstFocus
import com.biskyt.mytvplayer.ui.common.rememberSchedule

/** Long-press options for a channel (agreed "LiveMenu" design). */
@Composable
fun ChannelOptionsSheet(ch: ChannelEntity, vm: LiveViewModel, play: (ChannelEntity) -> Unit) {
    val nav = LocalNavigator.current
    val favs = vm.favouriteIds.collectIds()
    var showGuide by remember { mutableStateOf(false) }
    val close = vm::closeOptions
    val programme = vm.optionsProgramme
    val findKind by produceState<Int?>(null, ch, programme) { if (programme != null) value = vm.findKind(ch, programme) }
    SidePanel(onDismiss = { if (showGuide) showGuide = false else close() }) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                ChannelLogo(ch.logoUrl, ch.name, 72.dp, 44.dp)
                Column(Modifier.padding(start = 14.dp)) {
                    Text(ch.name, style = MaterialTheme.typography.titleLarge, maxLines = 2)
                    Muted(ch.numberLabel)
                }
            }
            if (programme != null) {
                Text(programmeTitle(programme.title), style = MaterialTheme.typography.titleMedium, maxLines = 2)
                Muted("${formatTime(programme.startMs)} – ${formatTime(programme.stopMs)}", Modifier.padding(bottom = 4.dp))
                // What the guide says about it, in full (as far as the panel has room, above its options).
                rememberDetails(ch.playlistId, programme).value?.let { ProgrammeAbout(it, maxLines = 5, Modifier.padding(bottom = 4.dp)) }
            }
            if (showGuide) {
                ScheduleList(rememberSchedule(ch.playlistId, ch.streamId), onSelect = {
                    close()
                    play(ch)
                })
            } else {
                MenuOption(stringResource(R.string.common_watch), modifier = rememberFirstFocus()) {
                    close()
                    play(ch)
                }
                val kind = findKind
                if (programme != null && kind != null) {
                    val label = when (kind) {
                        ProgrammeKind.SERIES -> stringResource(R.string.live_option_find_series)
                        ProgrammeKind.MOVIE -> stringResource(R.string.live_option_find_movie)
                        else -> stringResource(R.string.live_option_search_for, programme.title)
                    }
                    MenuOption(label, stringResource(R.string.live_option_find_help)) {
                        close()
                        // Only if the user is still here when the lookup finishes.
                        val from = nav.current
                        vm.find(ch, programme, kind) { if (nav.current === from) nav.push(it) }
                    }
                }
                // Catch-up channels (known from the channel itself: nothing is asked of the provider to show these).
                if (ch.archiveDays > 0) {
                    MenuOption(
                        stringResource(R.string.common_catchup),
                        pluralStringResource(R.plurals.common_catchup_days, ch.archiveDays, ch.archiveDays)
                    ) {
                        close()
                        nav.push(Screen.Catchup(ch.playlistId, ch.streamId))
                    }
                    MenuOption(stringResource(R.string.common_start_over), stringResource(R.string.live_option_start_over_help)) {
                        close()
                        nav.push(Screen.CatchupPlayer(ch.playlistId, ch.streamId, filter = vm.filter.value))
                    }
                }
                MenuOption(favouriteLabel(ch.streamId in favs)) { vm.toggleFavourite(ch) }
                MenuOption(stringResource(R.string.live_option_channel_guide), stringResource(R.string.live_option_channel_guide_help)) {
                    showGuide = true
                }
                MenuOption(
                    stringResource(if (vm.guideMode) R.string.live_option_switch_to_list else R.string.live_option_switch_to_grid),
                    stringResource(R.string.live_option_switch_help)
                ) {
                    close()
                    vm.showGuide(!vm.guideMode)
                }
                MenuOption(stringResource(R.string.common_change_guide_source), stringResource(R.string.common_change_guide_source_help)) {
                    close()
                    nav.push(Screen.GuideMatching(ch.playlistId, ch.streamId))
                }
                if (vm.canMoveFavourite) {
                    MenuOption(stringResource(R.string.common_move)) {
                        close()
                        vm.mover.start(CAT_LIVE, ch.streamId)
                    }
                }
                MenuOption(stringResource(R.string.live_option_hide_channel), stringResource(R.string.live_option_hide_channel_help)) {
                    vm.hide(ch)
                }
            }
        }
    }
}
