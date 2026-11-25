package com.biskyt.mytvplayer.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.data.programmeTitle
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.appViewModel
import com.biskyt.mytvplayer.ui.common.PillButton
import com.biskyt.mytvplayer.ui.common.formatRuntime
import com.biskyt.mytvplayer.ui.common.formatTime
import com.biskyt.mytvplayer.ui.phone.PhoneCatchupControls
import com.biskyt.mytvplayer.ui.theme.LocalFormFactor

/** Catch-up: a channel as it was earlier, running on to the present; live TV, only earlier. */
@Composable
fun CatchupPlayerScreen(screen: Screen.CatchupPlayer) {
    val appContext = LocalContext.current.applicationContext
    val vm = appViewModel { CatchupPlayerViewModel(it, appContext, screen) }
    ChannelPlayerFrame(vm) { buttons, root -> CatchupControls(vm, buttons, root) }
}

@Composable
private fun CatchupControls(vm: CatchupPlayerViewModel, buttons: FocusRequester, root: FocusRequester) {
    if (!LocalFormFactor.current.isTv) return PhoneCatchupControls(vm)
    // While scrubbing: the programme and time playback will go to.
    val scrub = vm.scrubMs
    val at = scrub ?: vm.at
    val p = scrub?.let(vm::programmeAt) ?: vm.programme
    val behind = ((System.currentTimeMillis() - at) / 1000).toInt()
    ChannelControls(
        vm,
        buttons,
        root,
        title = p?.let { programmeTitle(it.title) },
        next = vm.upNext?.let { it.startMs to programmeTitle(it.title) },
        line = stringResource(R.string.player_catchup_behind_live, formatRuntime(behind)),
        timeline = {
            if (p != null) {
                val bar = vm.catchupBar(p.startMs..p.stopMs)
                Timeline(
                    formatTime(p.startMs),
                    bar.fraction,
                    formatTime(p.stopMs),
                    bar.scrub,
                    handle = LocalBarSelected.current,
                    onSeek = bar.onSeek
                )
            }
        }
    ) {
        PillButton(stringResource(R.string.common_from_start), vm::fromStart)
        PillButton(stringResource(R.string.player_watch_live), vm::watchLive)
    }
}
