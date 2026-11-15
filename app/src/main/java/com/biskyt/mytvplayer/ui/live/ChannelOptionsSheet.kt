package com.biskyt.mytvplayer.ui.live

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.core.epg.ProgrammeKind
import com.biskyt.mytvplayer.data.db.CAT_LIVE
import com.biskyt.mytvplayer.data.db.ChannelEntity
import com.biskyt.mytvplayer.data.programmeTitle
import com.biskyt.mytvplayer.ui.LocalNavigator
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.common.AppIcons
import com.biskyt.mytvplayer.ui.common.BoardOption
import com.biskyt.mytvplayer.ui.common.BoardPanel
import com.biskyt.mytvplayer.ui.common.BoardPanelSubtitle
import com.biskyt.mytvplayer.ui.common.BoardPanelTitle
import com.biskyt.mytvplayer.ui.common.ChannelLogo
import com.biskyt.mytvplayer.ui.common.LocalReturnFocus
import com.biskyt.mytvplayer.ui.common.ProgrammeAbout
import com.biskyt.mytvplayer.ui.common.ScheduleList
import com.biskyt.mytvplayer.ui.common.collectIds
import com.biskyt.mytvplayer.ui.common.favouriteLabel
import com.biskyt.mytvplayer.ui.common.formatTime
import com.biskyt.mytvplayer.ui.common.numberLabel
import com.biskyt.mytvplayer.ui.common.rememberDetails
import com.biskyt.mytvplayer.ui.common.rememberFirstFocus
import com.biskyt.mytvplayer.ui.common.rememberReturnFocus
import com.biskyt.mytvplayer.ui.common.rememberSchedule
import com.biskyt.mytvplayer.ui.common.returnsFocus
import com.biskyt.mytvplayer.ui.theme.bd

/** The options panel's views: the options, the channel's guide (What's on), More info. */
private enum class View { OPTIONS, GUIDE, INFO }

/**
 * Long-press options for a channel (agreed "LiveMenu" design). What's on and More info show in the same panel: Back
 * returns from them to the options, on the option that opened them.
 */
@Composable
fun ChannelOptionsSheet(ch: ChannelEntity, vm: LiveViewModel, play: (ChannelEntity) -> Unit) {
    val favs = vm.favouriteIds.collectIds()
    var view by remember { mutableStateOf(View.OPTIONS) }
    // The options keep their state while another view shows (as NavHost does for screens), so that they come back with
    // the option used last focused (see ReturnFocus).
    val options = rememberSaveableStateHolder()
    val close = vm::closeOptions
    val programme = vm.optionsProgramme
    // Looked up once for the panel (not again as the options come back).
    val kind by produceState<Int?>(null, ch, programme) { if (programme != null) value = vm.findKind(ch, programme) }
    val findKind = kind
    // The channel's guide is a list of its own, which does not go in a scrolling column.
    BoardPanel(onDismiss = { if (view != View.OPTIONS) view = View.OPTIONS else close() }, scroll = view != View.GUIDE) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.bd)) {
            ChannelLogo(ch.logoUrl, ch.name, 72.dp, 44.dp)
            Column(Modifier.padding(start = 14.dp)) {
                BoardPanelTitle(ch.name)
                BoardPanelSubtitle(ch.numberLabel)
            }
        }
        if (programme != null) {
            BoardPanelTitle(programmeTitle(programme.title), small = true)
            BoardPanelSubtitle("${formatTime(programme.startMs)} – ${formatTime(programme.stopMs)}", Modifier.padding(bottom = 4.bd))
            // What the guide says about it, in full (as far as the panel has room, above its options).
            rememberDetails(ch.playlistId, programme).value?.let { ProgrammeAbout(it, maxLines = 5, Modifier.padding(bottom = 4.bd)) }
        }
        when (view) {
            // Opened afresh each time, on the programme on now.
            View.GUIDE -> ScheduleList(rememberSchedule(ch.playlistId, ch.streamId), onSelect = {
                close()
                play(ch)
            })
            View.INFO -> ChannelInfoView(ch, vm::channelInfo)
            View.OPTIONS -> options.SaveableStateProvider(View.OPTIONS) {
                CompositionLocalProvider(LocalReturnFocus provides rememberReturnFocus()) {
                    Options(ch, vm, play, findKind, ch.streamId in favs) { view = it }
                }
            }
        }
    }
}

@Composable
private fun Options(
    ch: ChannelEntity,
    vm: LiveViewModel,
    play: (ChannelEntity) -> Unit,
    findKind: Int?,
    favourite: Boolean,
    open: (View) -> Unit,
) {
    val nav = LocalNavigator.current
    val close = vm::closeOptions
    val programme = vm.optionsProgramme
    BoardOption(stringResource(R.string.common_watch), modifier = rememberFirstFocus().returnsFocus()) {
        close()
        play(ch)
    }
    if (programme != null && findKind != null) {
        val label = when (findKind) {
            ProgrammeKind.SERIES -> stringResource(R.string.live_option_find_series)
            ProgrammeKind.MOVIE -> stringResource(R.string.live_option_find_movie)
            else -> stringResource(R.string.live_option_search_for, programme.title)
        }
        BoardOption(label, stringResource(R.string.live_option_find_help)) {
            close()
            // Only if the user is still here when the lookup finishes.
            val from = nav.current
            vm.find(ch, programme, findKind) { if (nav.current === from) nav.push(it) }
        }
    }
    // Catch-up channels (known from the channel itself: nothing is asked of the provider to show these).
    if (ch.archiveDays > 0) {
        BoardOption(
            stringResource(R.string.common_catchup),
            pluralStringResource(R.plurals.common_catchup_days, ch.archiveDays, ch.archiveDays)
        ) {
            close()
            nav.push(Screen.Catchup(ch.playlistId, ch.streamId))
        }
        BoardOption(stringResource(R.string.live_option_restart), stringResource(R.string.live_option_start_over_help)) {
            close()
            nav.push(Screen.CatchupPlayer(ch.playlistId, ch.streamId, filter = vm.filter.value))
        }
    }
    BoardOption(
        favouriteLabel(favourite),
        modifier = Modifier.returnsFocus(),
        icon = if (favourite) AppIcons.Heart else AppIcons.HeartOutline
    ) { vm.toggleFavourite(ch) }
    BoardOption(
        stringResource(R.string.live_option_channel_guide),
        stringResource(R.string.live_option_channel_guide_help),
        Modifier.returnsFocus()
    ) { open(View.GUIDE) }
    BoardOption(
        stringResource(R.string.live_option_more_info),
        stringResource(R.string.live_option_more_info_help),
        Modifier.returnsFocus()
    ) { open(View.INFO) }
    BoardOption(stringResource(R.string.common_change_guide_source), stringResource(R.string.common_change_guide_source_help)) {
        close()
        nav.push(Screen.GuideMatching(ch.playlistId, ch.streamId))
    }
    if (vm.canMoveFavourite) {
        BoardOption(stringResource(R.string.common_move)) {
            close()
            vm.mover.start(CAT_LIVE, ch.streamId)
        }
    }
    BoardOption(stringResource(R.string.live_option_hide_channel), stringResource(R.string.live_option_hide_channel_help)) {
        vm.hide(ch)
    }
    // Last (the owner, 2026-11-15).
    BoardOption(
        stringResource(if (vm.guideMode) R.string.live_option_switch_to_list else R.string.live_option_switch_to_grid),
        stringResource(R.string.live_option_switch_help)
    ) {
        close()
        vm.showGuide(!vm.guideMode, moveFocus = true)
    }
}
