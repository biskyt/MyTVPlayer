package com.biskyt.mytvplayer.ui.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.AppContainer
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.appString
import com.biskyt.mytvplayer.core.xtream.CatchupProgramme
import com.biskyt.mytvplayer.data.db.ChannelEntity
import com.biskyt.mytvplayer.data.programmeTitle
import com.biskyt.mytvplayer.ui.LocalNavigator
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.appViewModel
import com.biskyt.mytvplayer.ui.common.CategoryColumn
import com.biskyt.mytvplayer.ui.common.CategoryEntry
import com.biskyt.mytvplayer.ui.common.CategoryFilter
import com.biskyt.mytvplayer.ui.common.Muted
import com.biskyt.mytvplayer.ui.common.PillButton
import com.biskyt.mytvplayer.ui.common.Tab
import com.biskyt.mytvplayer.ui.common.TabScaffold
import com.biskyt.mytvplayer.ui.common.TvCard
import com.biskyt.mytvplayer.ui.common.dateFormat
import com.biskyt.mytvplayer.ui.common.formatRuntime
import com.biskyt.mytvplayer.ui.common.formatTime
import com.biskyt.mytvplayer.ui.common.rememberFirstFocus
import com.biskyt.mytvplayer.ui.common.restoreFocusIf
import com.biskyt.mytvplayer.ui.phone.PhoneCatchup
import com.biskyt.mytvplayer.ui.theme.LocalFormFactor
import com.biskyt.mytvplayer.ui.theme.Palette
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.launch

/** A channel's catch-up: the programmes it showed in its catch-up days, by day (0 = today), newest first. */
class CatchupViewModel(private val c: AppContainer, private val screen: Screen.Catchup) : ViewModel() {
    var channel by mutableStateOf<ChannelEntity?>(null)
        private set
    var byDay by mutableStateOf<Map<Int, List<CatchupProgramme>>?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    val days = CategoryFilter(viewModelScope, 0)

    /** The programme last opened (its start), for focus to return to from the player. */
    var lastOpened: Long? = null

    /** Where the phone's list of programmes was scrolled to, for back from the player. */
    val phoneList = LazyListState()

    init {
        load()
    }

    fun load() = viewModelScope.launch {
        error = null
        val p = c.playlists.get(screen.playlistId) ?: return@launch
        val ch = c.channels.get(screen.playlistId, screen.streamId) ?: return@launch
        channel = ch
        try {
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            byDay = c.catchup.playable(c.catchup.listing(p, ch), ch)
                .groupBy { ChronoUnit.DAYS.between(Instant.ofEpochMilli(it.startMs).atZone(zone).toLocalDate(), today).toInt() }
                .mapValues { (_, list) -> list.sortedByDescending { it.startMs } }
        } catch (e: java.io.IOException) {
            error = e.message ?: appString(R.string.live_catchup_load_failed)
        }
    }
}

internal fun dayLabel(daysAgo: Int): String = when (daysAgo) {
    0 -> appString(R.string.common_today)
    1 -> appString(R.string.common_yesterday)
    else -> LocalDate.now().minusDays(daysAgo.toLong()).format(dateFormat)
}

@Composable
fun CatchupScreen(screen: Screen.Catchup) {
    val vm = appViewModel(key = "catchup-${screen.streamId}") { CatchupViewModel(it, screen) }
    if (!LocalFormFactor.current.isTv) return PhoneCatchup(screen, vm)
    val nav = LocalNavigator.current
    val ch = vm.channel
    val day by vm.days.value.collectAsState()
    val entries = remember(ch) { (0..(ch?.archiveDays ?: 0)).map { CategoryEntry(it, dayLabel(it)) } }
    var focused by remember { mutableStateOf<CatchupProgramme?>(null) }
    val programmes = vm.byDay?.get(day).orEmpty()
    // Focus starts on the programmes (the one last opened, back from the player), or on the days when there are none:
    // with nothing focused, the first key press would put it on the top bar. Right from the days goes to the
    // programmes (not the top bar beside them).
    val list = remember { FocusRequester() }
    val days = remember { FocusRequester() }
    var placed by remember { mutableStateOf(false) }
    val target = vm.lastOpened?.takeIf { s -> programmes.any { it.startMs == s } } ?: programmes.firstOrNull()?.startMs
    val noProgrammes = vm.byDay != null && programmes.isEmpty() && vm.error == null
    LaunchedEffect(noProgrammes) { if (noProgrammes && !placed && runCatching { days.requestFocus() }.isSuccess) placed = true }
    TabScaffold(Tab.LIVE) {
        Row(Modifier.fillMaxSize().padding(horizontal = 56.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            // Once the days are used, focus is the user's: the programmes no longer claim it as they appear.
            Box(Modifier.focusRequester(days).onFocusChanged { if (it.hasFocus) placed = true }) {
                CategoryColumn(
                    entries,
                    day,
                    vm.days::focus,
                    vm.days::select,
                    right = if (programmes.isEmpty()) FocusRequester.Default else list
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.live_catchup_title, ch?.name.orEmpty()),
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1
                )
                // The focused programme's description, in a fixed space so the list doesn't jump.
                Muted(focused?.description.orEmpty(), Modifier.height(44.dp), maxLines = 2)
                val byDay = vm.byDay
                when {
                    vm.error != null -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Muted(vm.error.orEmpty())
                        PillButton(stringResource(R.string.common_retry), { vm.load() }, rememberFirstFocus())
                    }
                    byDay == null -> Muted(stringResource(R.string.common_loading))
                    programmes.isEmpty() -> Muted(stringResource(R.string.live_catchup_empty_day))
                    else -> LazyColumn(
                        Modifier.focusRequester(list),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(bottom = 40.dp)
                    ) {
                        items(programmes, key = { it.startMs }) { p ->
                            TvCard(
                                onClick = {
                                    vm.lastOpened = p.startMs
                                    nav.push(Screen.CatchupPlayer(screen.playlistId, screen.streamId, p.startMs))
                                },
                                color = Palette.surfaceLow,
                                modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) focused = p }
                                    .restoreFocusIf(!placed && p.startMs == target) { placed = true }
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    Text(
                                        formatTime(p.startMs),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = Palette.textDim,
                                        modifier = Modifier.width(64.dp)
                                    )
                                    Text(
                                        programmeTitle(p.title),
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Muted(formatRuntime(((p.stopMs - p.startMs) / 1000).toInt()))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
