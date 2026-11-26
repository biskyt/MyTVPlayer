package com.biskyt.mytvplayer.ui.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.core.xtream.CatchupProgramme
import com.biskyt.mytvplayer.data.programmeTitle
import com.biskyt.mytvplayer.ui.LocalNavigator
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.common.Muted
import com.biskyt.mytvplayer.ui.common.Tab
import com.biskyt.mytvplayer.ui.common.formatRuntime
import com.biskyt.mytvplayer.ui.common.formatTime
import com.biskyt.mytvplayer.ui.live.CatchupViewModel
import com.biskyt.mytvplayer.ui.live.dayLabel
import com.biskyt.mytvplayer.ui.theme.Palette
import kotlinx.coroutines.launch

// A channel's catch-up on a phone or an unfolded foldable (the owner, 2026-11-26): the TV's column of days becomes a
// row of chips, over the day's programmes. A tap plays one.

/** "Catch-up: BBC One", the days, then the day's programmes, newest first, from the TV's [CatchupViewModel]. */
@Composable
fun PhoneCatchup(screen: Screen.Catchup, vm: CatchupViewModel) {
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val ch = vm.channel
    val day by vm.days.value.collectAsState()
    val days = remember(ch) { (0..(ch?.archiveDays ?: 0)).toList() }
    val byDay = vm.byDay
    val programmes = byDay?.get(day).orEmpty()
    PhoneTabFrame(Tab.CATCHUP) {
        Column(Modifier.fillMaxSize().background(Palette.background)) {
            PageTop(stringResource(R.string.live_catchup_title, ch?.name.orEmpty()))
            PhoneChipRow(days, { it == day }, { it }) { d, chosen ->
                PhoneChoiceChip(dayLabel(d), chosen) {
                    // Another day starts at its newest programme.
                    if (d != day) scope.launch { vm.phoneList.scrollToItem(0) }
                    vm.days.select(d)
                }
            }
            val below = Modifier.padding(horizontal = PhoneGutter, vertical = 12.dp)
            val error = vm.error
            when {
                error != null || byDay == null -> PhoneLoadState(error, loaded = false, below) { vm.load() }
                programmes.isEmpty() -> Muted(stringResource(R.string.live_catchup_empty_day), below)
                // The list stays where it was, back from the player.
                else -> LazyColumn(
                    state = vm.phoneList,
                    contentPadding = PaddingValues(start = PhoneGutter, end = PhoneGutter, top = 4.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(programmes, key = { it.startMs }) { p ->
                        ProgrammeRow(p) { nav.push(Screen.CatchupPlayer(screen.playlistId, screen.streamId, p.startMs)) }
                    }
                }
            }
        }
    }
}

/** A programme: its start time, title and length, with its description under it. */
@Composable
private fun ProgrammeRow(p: CatchupProgramme, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = PhoneTouch).clip(RoundedCornerShape(14.dp)).background(PhoneCard)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            formatTime(p.startMs),
            color = Palette.textDim,
            fontSize = 14.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.width(48.dp)
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                programmeTitle(p.title),
                color = Palette.text,
                fontSize = 15.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            p.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = Palette.textDim, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(formatRuntime(((p.stopMs - p.startMs) / 1000).toInt()), color = Palette.textDim, fontSize = 13.sp)
    }
}
