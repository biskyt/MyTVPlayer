package com.biskyt.mytvplayer.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.appViewModel
import com.biskyt.mytvplayer.ui.common.Muted
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
        ControlsBar(vm, buttons, root)
    }
}

/** Its own composable, so the once-a-second time update redraws only this row. */
@Composable
private fun Position(vm: VodPlayerViewModel) {
    val dur = vm.durationSec
    val scrub = vm.scrubMs
    // While scrubbing, the time shown is where playback will go.
    val pos = scrub?.div(1000) ?: vm.positionSec
    // Known once playback has started.
    val totalMs = (dur * 1000).takeIf { it > 0 }
    fun fraction(sec: Long) = totalMs?.let { sec * 1000f / it } ?: 0f
    // The bar the whole width; under it, how far in of how long, and how long is left.
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SeekBar(
            fraction(vm.positionSec),
            scrub?.let { fraction(it / 1000) },
            handle = LocalBarSelected.current,
            onSeek = totalMs?.let { t -> { vm.scrubTo(0L..t, it) } },
            modifier = Modifier.fillMaxWidth()
        )
        Row {
            Muted(formatDuration(pos * 1000) + totalMs?.let { " / " + formatDuration(it) }.orEmpty())
            Spacer(Modifier.weight(1f))
            totalMs?.let { Muted(stringResource(R.string.common_time_left, formatDuration((it - pos * 1000).coerceAtLeast(0)))) }
        }
    }
}
