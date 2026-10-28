package com.biskyt.mytvplayer.ui.player

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.appViewModel
import com.biskyt.mytvplayer.ui.common.LocalTouch
import com.biskyt.mytvplayer.ui.common.Muted
import com.biskyt.mytvplayer.ui.common.PillButton
import com.biskyt.mytvplayer.ui.common.formatDuration

/** Movies and episodes. */
@Composable
fun VodPlayerScreen(screen: Screen.VodPlayer) {
    val appContext = LocalContext.current.applicationContext
    val vm = appViewModel { VodPlayerViewModel(it, appContext, screen) }
    PlayerFrame(vm, controls = { buttons, root -> VodControls(vm, buttons, root) })
}

@Composable
private fun VodControls(vm: VodPlayerViewModel, buttons: FocusRequester, root: FocusRequester) {
    ControlsPanel {
        Column {
            if (vm.subtitle.isNotEmpty()) Muted(vm.subtitle)
            Text(vm.title, style = MaterialTheme.typography.displaySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Position(vm)
        ControlsBar(vm, buttons, root) {
            PillButton(stringResource(R.string.common_from_start), vm::startOver)
            if (vm.nextEpisode != null) PillButton(stringResource(R.string.player_next_episode), vm::playNext)
        }
        if (!LocalTouch.current) Muted(stringResource(R.string.player_hint_skip), Modifier.align(Alignment.CenterHorizontally))
    }
}

/** Its own composable, so the once-a-second time update redraws only this row. */
@Composable
private fun Position(vm: VodPlayerViewModel) {
    val dur = vm.durationSec
    val scrub = vm.scrubMs
    // While scrubbing, the time shown is where playback will go.
    val pos = scrub?.div(1000) ?: vm.positionSec
    fun fraction(sec: Long) = if (dur > 0) sec.toFloat() / dur else 0f
    Timeline(
        formatDuration(pos * 1000),
        fraction(vm.positionSec),
        if (dur > 0) formatDuration(dur * 1000) else "",
        scrub?.let { fraction(it / 1000) },
        handle = LocalBarSelected.current,
        onSeek = if (dur > 0) ({ vm.scrubTo(0L..dur * 1000, it) }) else null
    )
}
