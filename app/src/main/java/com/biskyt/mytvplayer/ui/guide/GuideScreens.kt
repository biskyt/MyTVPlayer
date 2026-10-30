package com.biskyt.mytvplayer.ui.guide

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.AppContainer
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.appPlural
import com.biskyt.mytvplayer.appString
import com.biskyt.mytvplayer.core.epg.EpgCandidate
import com.biskyt.mytvplayer.core.epg.EpgMatch
import com.biskyt.mytvplayer.data.FreeGuide
import com.biskyt.mytvplayer.data.FreeGuides
import com.biskyt.mytvplayer.data.GuideError
import com.biskyt.mytvplayer.data.db.ChannelEntity
import com.biskyt.mytvplayer.data.db.EpgSourceEntity
import com.biskyt.mytvplayer.data.db.GuideCoverage
import com.biskyt.mytvplayer.data.db.MappingInfo
import com.biskyt.mytvplayer.data.db.MatchCounts
import com.biskyt.mytvplayer.data.db.MatchRow
import com.biskyt.mytvplayer.data.db.PlaylistEntity
import com.biskyt.mytvplayer.data.db.PlaylistEpgSourceEntity
import com.biskyt.mytvplayer.ui.appViewModel
import com.biskyt.mytvplayer.ui.common.AppIcons
import com.biskyt.mytvplayer.ui.common.ChannelLogo
import com.biskyt.mytvplayer.ui.common.FilterField
import com.biskyt.mytvplayer.ui.common.Muted
import com.biskyt.mytvplayer.ui.common.PillButton
import com.biskyt.mytvplayer.ui.common.SidePanel
import com.biskyt.mytvplayer.ui.common.StatusPanel
import com.biskyt.mytvplayer.ui.common.Toggle
import com.biskyt.mytvplayer.ui.common.TvCard
import com.biskyt.mytvplayer.ui.common.TvTextField
import com.biskyt.mytvplayer.ui.common.WorkRunner
import com.biskyt.mytvplayer.ui.common.WorkStatus
import com.biskyt.mytvplayer.ui.common.formatCount
import com.biskyt.mytvplayer.ui.common.formatDateTime
import com.biskyt.mytvplayer.ui.common.rememberFirstFocus
import com.biskyt.mytvplayer.ui.settings.SettingsPage
import com.biskyt.mytvplayer.ui.theme.LocalAccent
import com.biskyt.mytvplayer.ui.theme.Palette
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// ---------------------------------------------------------------- sources

/**
 * Every guide as [shown] sees it, given which playlists use which ([links]): its own provider guide first, then as
 * added, so a row doesn't move when it's switched.
 */
private fun guideRows(
    sources: List<EpgSourceEntity>,
    links: List<PlaylistEpgSourceEntity>,
    playlists: List<PlaylistEntity>,
    shown: PlaylistEntity?
): List<GuideRow> {
    val byId = playlists.associateBy { it.id }
    val usedBy = links.groupBy({ it.sourceId }, { it.playlistId })
    return sources.map { s ->
        val users = usedBy[s.id].orEmpty()
        val others = users.filter { it != shown?.id }.mapNotNull { byId[it]?.name }
        GuideRow(s, on = shown?.id in users, others = others, provider = s.playlistId?.let(byId::get))
    }.sortedByDescending { shown != null && it.provider?.id == shown.id }
}

/**
 * A guide as the playlist shown sees it: [on] for it, the [others] using it too, and the playlist it's the
 * provider's own guide of ([provider]), if it is one.
 */
data class GuideRow(val source: EpgSourceEntity, val on: Boolean, val others: List<String>, val provider: PlaylistEntity?)

class GuideSourcesViewModel(private val c: AppContainer) : ViewModel() {
    private val playlists = c.playlists.observePlaylists().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** The playlist whose guides are shown: the one in use. */
    val playlist = combine(c.settings.activePlaylistId, playlists) { id, all -> all.firstOrNull { it.id == id } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Every guide as the playlist shown sees it (see [guideRows]). */
    val sources = combine(c.epg.observeSources(), c.epg.observeLinks(), playlists, playlist) { s, l, p, shown ->
        guideRows(s, l, p, shown)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    var name by mutableStateOf("")
    var url by mutableStateOf("")
    private val work = WorkRunner(c.appScope, viewModelScope, c.sync.state)

    /** Progress and the outcome of the last add, update or match, all shown in the one place. */
    val status: WorkStatus? get() = work.status

    /** Adds the typed address; false if it can't start yet (nothing typed, or something else is running). */
    fun add(): Boolean {
        if (url.isBlank() || !addAndLoad(name, url)) return false
        name = ""
        url = ""
        return true
    }

    fun addFree(g: FreeGuide) = addAndLoad(g.sourceName, g.url)

    private fun addAndLoad(name: String, url: String): Boolean {
        val pid = playlist.value?.id ?: return false
        val shown = name.trim().ifEmpty { url.trim() }
        return work.run(appString(R.string.live_guide_adding, shown)) {
            loadOne(c.epg.addSource(name, url, pid), shown, appString(R.string.live_guide_added, shown))
        }
    }

    /** Updates just guide [id] (named [shown]): [done], or its own error if it couldn't be loaded. */
    private suspend fun loadOne(id: Long, shown: String, done: String) =
        // A guide that couldn't be loaded doesn't fail the update (the others still load), so look for its own error.
        update(done, only = id) { errors ->
            errors[id]?.let { WorkStatus(appString(R.string.live_guide_load_failed, shown), it, failed = true) }
        }

    fun refresh() = work.run(appString(R.string.live_guide_updating)) {
        update(appString(R.string.live_guide_updated)) { errors ->
            if (errors.isEmpty()) return@update null
            val names = sources.value.map { it.source }.filter { it.id in errors }.joinToString { it.name }
            WorkStatus(appString(R.string.live_guide_some_failed), "$names: ${errors.values.first()}", failed = true)
        }
    }

    fun matchMissing() = work.run(appString(R.string.live_guide_matching)) {
        val r = c.sync.matchMissingGuides().getOrElse { return@run WorkRunner.failed(appString(R.string.common_match_failed), it) }
        val now = coverage()
        val title = when {
            r.matched > 0 -> appPlural(R.plurals.live_guide_match_found, r.matched, formatCount(r.matched))
            now.noGuide == 0 -> appString(R.string.live_guide_match_all)
            r.checked == 0 -> appString(R.string.live_guide_match_none_left)
            else -> appString(R.string.live_guide_match_none_new)
        }
        WorkStatus(title, coverageText(now, now))
    }

    /**
     * Updates the guide (downloading just [only], if given): [done], with how many channels have a guide now, unless
     * [check] finds a problem.
     */
    private suspend fun update(done: String, only: Long? = null, check: (Map<Long, String>) -> WorkStatus?): WorkStatus {
        val before = coverage()
        val errors = c.sync.updateGuide(only).getOrElse { return WorkRunner.failed(appString(R.string.common_guide_update_failed), it) }
        return check(errors) ?: WorkStatus(done, coverageText(before, coverage()))
    }

    /** How many of the shown playlist's channels have a guide. */
    private suspend fun coverage() = c.epg.coverage(playlist.value?.id ?: 0)

    /** Switches [row]'s guide on or off for the playlist shown. Switched on, it's loaded then if it needs to be. */
    fun toggle(row: GuideRow) = viewModelScope.launch {
        val pid = playlist.value?.id ?: return@launch
        val s = row.source
        if (c.epg.setSourceOn(pid, s, !row.on)) {
            work.run(appString(R.string.live_guide_updating)) {
                loadOne(s.id, s.name, appString(R.string.live_guide_updated))
            }
        }
    }

    fun remove(s: EpgSourceEntity) = viewModelScope.launch { c.epg.removeSource(s.id) }

    private fun coverageText(before: GuideCoverage, now: GuideCoverage): String {
        val gained = now.withGuide - before.withGuide
        val n = now.withGuide
        val coverage = if (gained > 0) {
            appPlural(R.plurals.live_guide_coverage_more, n, formatCount(n), formatCount(gained))
        } else {
            appPlural(R.plurals.live_guide_coverage, n, formatCount(n))
        }
        if (now.noGuide == 0) return coverage
        val none = appPlural(R.plurals.live_guide_coverage_none, now.noGuide, formatCount(now.noGuide))
        return "$coverage $none"
    }
}

@Composable
fun GuideSourcesScreen() {
    val vm = appViewModel { GuideSourcesViewModel(it) }
    val sources by vm.sources.collectAsState()
    val playlist by vm.playlist.collectAsState()
    val name = playlist?.name.orEmpty()
    var adding by rememberSaveable { mutableStateOf(false) }
    val title = stringResource(R.string.common_guide_sources)
    SettingsPage(if (playlist == null) title else "$title · $name") {
        item {
            Muted(stringResource(R.string.live_guide_sources_intro, name), maxLines = 2)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton(stringResource(R.string.live_guide_add), { adding = true }, rememberFirstFocus(), primary = true)
                PillButton(stringResource(R.string.live_guide_update_now), vm::refresh)
                PillButton(stringResource(R.string.live_guide_match_missing), vm::matchMissing)
            }
        }
        // Directly under the buttons, so whatever was pressed, its progress and outcome show in the same place.
        item(key = "status") { StatusPanel(vm.status) }
        item {
            Text(
                stringResource(R.string.live_guide_for, name),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
        if (sources.isEmpty()) item { Muted(stringResource(R.string.live_guide_none_yet)) }
        items(sources, key = { it.source.id }) { row ->
            val s = row.source
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                TvCard(onClick = { vm.toggle(row) }, color = Palette.surfaceLow, modifier = Modifier.weight(1f)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, style = MaterialTheme.typography.titleMedium)
                            Muted(sourceDetails(row, playlist))
                            s.lastError?.let {
                                Text(
                                    stringResource(R.string.live_guide_last_failed, GuideError.text(it)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Palette.warning,
                                    maxLines = 1
                                )
                            }
                        }
                        Toggle(row.on)
                    }
                }
                if (s.playlistId == null) PillButton(stringResource(R.string.common_remove), { vm.remove(s) })
            }
        }
    }
    if (adding) AddGuidePanel(vm, sources.map { it.source }) { adding = false }
}

/** Adding a guide: a free one for a country (the TV's own first), or any XMLTV address. Closes once one is added. */
@Composable
private fun AddGuidePanel(vm: GuideSourcesViewModel, sources: List<EpgSourceEntity>, onDismiss: () -> Unit) {
    val free = remember(sources) { FreeGuides.notAdded(sources) }
    SidePanel(onDismiss, width = 560.dp) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text(stringResource(R.string.live_guide_add), style = MaterialTheme.typography.headlineSmall) }
            vm.status?.takeIf { it.working }?.let { status ->
                item { Text(stringResource(R.string.live_guide_wait, status.title), color = Palette.warning) }
            }
            item {
                Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.live_guide_own_address), style = MaterialTheme.typography.titleMedium)
                    val urlEdit = rememberSaveable { mutableStateOf(false) }
                    val nameHint = stringResource(R.string.live_guide_name_hint)
                    TvTextField(vm.name, { vm.name = it }, nameHint, Modifier.fillMaxWidth(), onNext = { urlEdit.value = true })
                    TvTextField(vm.url, {
                        vm.url = it
                    }, "https://…/guide.xml.gz", Modifier.fillMaxWidth(), keyboardType = KeyboardType.Uri, editing = urlEdit)
                    PillButton(stringResource(R.string.live_guide_add_this), {
                        if (vm.add()) onDismiss()
                    }, if (free.isEmpty()) rememberFirstFocus() else Modifier, primary = true)
                }
            }
            item {
                Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.live_guide_free_title), style = MaterialTheme.typography.titleMedium)
                    Muted(stringResource(R.string.live_guide_free_note), maxLines = 2)
                }
            }
            items(free, key = { it.code }) { g ->
                TvCard(
                    onClick = { if (vm.addFree(g)) onDismiss() },
                    color = Palette.surfaceLow,
                    modifier = (if (g == free.first()) rememberFirstFocus() else Modifier).fillMaxWidth()
                ) {
                    Text(
                        g.country,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
            }
        }
    }
}

/** Where [row]'s guide is from, its size, and which playlists use it, as [shown] sees it. */
private fun sourceDetails(row: GuideRow, shown: PlaylistEntity?): String {
    val s = row.source
    val parts = mutableListOf(
        when (row.provider?.id) {
            null -> s.url
            shown?.id -> appString(R.string.live_guide_from_provider)
            else -> appString(R.string.live_guide_from_other_provider, row.provider.name)
        }
    )
    if (s.channelCount > 0) parts += appPlural(R.plurals.live_guide_source_channels, s.channelCount, formatCount(s.channelCount))
    when {
        !row.on && row.others.isEmpty() -> parts += appString(R.string.live_guide_not_used)
        !row.on -> parts += appString(R.string.live_guide_off_here)
        else -> {
            if (s.lastImportAt > 0) parts += appString(R.string.common_updated_at, formatDateTime(s.lastImportAt))
            if (row.others.isNotEmpty()) parts += appString(R.string.live_guide_also_used_by, row.others.joinToString())
        }
    }
    return parts.joinToString(" · ")
}

// ---------------------------------------------------------------- matching

/** [status] is what the matching query filters on; [count] the tab's number. */
enum class MatchTab(@StringRes val label: Int, val status: Int, val count: (MatchCounts) -> Int) {
    ALL(R.string.live_guide_tab_all, 0, { it.total }),
    UNMATCHED(R.string.live_guide_no_guide, 1, { it.unmatched }),
    AUTO(R.string.live_guide_tab_auto, 2, { it.auto }),
    MANUAL(R.string.live_guide_tab_manual, 3, { it.manual })
}

/** One candidate in the right-hand panel: from the matcher (with a score) or from a name search. */
data class GuideChoice(val candidate: EpgCandidate, val score: Double?, val sourceName: String)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class GuideMatchingViewModel(private val c: AppContainer, private val playlistId: Long, initial: Long?) : ViewModel() {
    val tab = MutableStateFlow(MatchTab.ALL)
    val filter = MutableStateFlow("")
    val counts = c.epg.observeMatchCounts(playlistId).stateIn(viewModelScope, SharingStarted.Eagerly, MatchCounts(0, 0, 0, 0))
    val channels = combine(tab, filter.debounce(250)) { t, f -> t to f }
        .flatMapLatest { (t, f) -> c.epg.pageForMatching(playlistId, f, t.status) }
        .cachedIn(viewModelScope)

    var selected by mutableStateOf<ChannelEntity?>(null)
        private set
    var mapping by mutableStateOf<MappingInfo?>(null)
        private set
    var search by mutableStateOf("")
    var choices by mutableStateOf<List<GuideChoice>>(emptyList())
        private set
    var busy by mutableStateOf(false)
        private set

    private var sources: Map<Long, String> = emptyMap()
    private var searchJob: Job? = null
    private var selectJob: Job? = null

    init {
        viewModelScope.launch {
            c.epg.observeSources().collect { list -> sources = list.associate { it.id to it.name } }
        }
        // Opened for one channel (from its menu): show just that channel, already selected.
        if (initial != null) {
            viewModelScope.launch {
                c.channels.get(playlistId, initial)?.let {
                    filter.value = it.name
                    select(it)
                }
            }
        }
    }

    fun select(ch: ChannelEntity) {
        selected = ch
        search = ""
        searchJob?.cancel()
        selectJob?.cancel() // quick changes of channel must not show an earlier channel's suggestions
        selectJob = viewModelScope.launch {
            mapping = c.epg.mappingInfo(playlistId, listOf(ch.streamId)).firstOrNull()
            choices = suggestionsFor(ch)
        }
    }

    fun onSearch(q: String) {
        search = q
        searchJob?.cancel()
        val ch = selected ?: return
        searchJob = viewModelScope.launch {
            delay(250)
            choices = if (q.isBlank()) {
                suggestionsFor(ch)
            } else {
                c.epg.searchGuideChannels(playlistId, q).map {
                    GuideChoice(
                        EpgCandidate(it.sourceId, it.xmlId, it.displayName, it.nameNorm),
                        null,
                        sources[it.sourceId] ?: ""
                    )
                }
            }
        }
    }

    private suspend fun suggestionsFor(ch: ChannelEntity) = c.epg.suggestions(playlistId, ch.name, ch.providerEpgId).map { it.toChoice() }

    private fun EpgMatch.toChoice() = GuideChoice(candidate, score, sources[candidate.sourceId] ?: "")

    private fun change(block: suspend (ChannelEntity) -> Unit) {
        val ch = selected ?: return
        busy = true
        viewModelScope.launch {
            block(ch)
            mapping = c.epg.mappingInfo(playlistId, listOf(ch.streamId)).firstOrNull()
            busy = false
        }
    }

    fun use(choice: GuideChoice) = change {
        c.epg.setManual(
            playlistId,
            it.streamId,
            choice.candidate.sourceId,
            choice.candidate.xmlId,
            mapping?.offsetMinutes ?: 0
        )
    }
    fun noGuide() = change { c.epg.setNoGuide(playlistId, it.streamId) }
    fun reset() = change { c.epg.resetToAutomatic(playlistId, it.streamId, it.name, it.providerEpgId) }
    override fun onCleared() = c.epg.releaseMatcher()

    fun shiftTime() = change {
        val next = ((mapping?.offsetMinutes ?: 0) / 60 + 1).let { h -> if (h > 3) -3 else h } * 60
        c.epg.setOffset(playlistId, it.streamId, next)
    }
}

@Composable
fun GuideMatchingScreen(playlistId: Long, streamId: Long?) {
    val vm = appViewModel(key = "match-$playlistId-$streamId") { GuideMatchingViewModel(it, playlistId, streamId) }
    val tab by vm.tab.collectAsState()
    val counts by vm.counts.collectAsState()
    val filter by vm.filter.collectAsState()
    val items = vm.channels.collectAsLazyPagingItems()

    Column(
        Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.common_guide_matching), style = MaterialTheme.typography.headlineLarge)
                Muted(stringResource(R.string.live_guide_matching_intro), maxLines = 2)
            }
            MatchTab.entries.forEach { t ->
                PillButton(
                    "${stringResource(t.label)} ${formatCount(t.count(counts))}",
                    { vm.tab.value = t },
                    Modifier.padding(start = 8.dp),
                    primary =
                    t == tab
                )
            }
        }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.width(480.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val hint = stringResource(R.string.live_guide_filter_hint)
                FilterField(filter, { vm.filter.value = it }, hint, rememberFirstFocus().fillMaxWidth())
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(count = items.itemCount, key = items.itemKey { it.channel.streamId }) { i ->
                        val row = items[i] ?: return@items
                        MatchRowCard(row, row.channel.streamId == vm.selected?.streamId) { vm.select(row.channel) }
                    }
                }
            }
            ChoicePanel(vm, Modifier.weight(1f))
        }
    }
}

@Composable
private fun MatchRowCard(row: MatchRow, selected: Boolean, onClick: () -> Unit) {
    val ch = row.channel
    TvCard(onClick = onClick, selected = selected, color = Palette.surfaceLow, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ChannelLogo(ch.logoUrl, ch.name, 52.dp, 30.dp)
            Column(Modifier.weight(1f)) {
                Text(ch.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Muted(row.guideName?.let { "$it (${row.sourceName})" } ?: stringResource(R.string.live_guide_no_data))
            }
            StatusChip(row)
        }
    }
}

@Composable
private fun StatusChip(row: MatchRow) {
    val (label, fg, bg) = when {
        row.mapSourceId == null -> Triple(stringResource(R.string.live_guide_chip_none), Palette.danger, Palette.danger.copy(alpha = 0.15f))
        row.mapManual == true -> Triple(stringResource(R.string.live_guide_chip_set), LocalAccent.current.soft, LocalAccent.current.tint)
        else -> Triple(
            stringResource(R.string.live_guide_chip_auto, ((row.mapScore ?: 0.0) * 100).toInt()),
            Palette.warning,
            Palette.warning.copy(alpha = 0.15f)
        )
    }
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = fg,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

@Composable
private fun ChoicePanel(vm: GuideMatchingViewModel, modifier: Modifier) {
    val ch = vm.selected
    Column(
        modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).background(Palette.surfaceLow).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (ch == null) {
            Muted(stringResource(R.string.live_guide_select_channel), maxLines = 2)
            return@Column
        }
        Muted(stringResource(R.string.live_guide_choose_for))
        Text(ch.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val m = vm.mapping
        Muted(
            when {
                m == null || m.sourceId == null -> stringResource(R.string.live_guide_current_none)
                else -> {
                    val name = (m.displayName ?: m.xmlId).toString()
                    val src = m.sourceName ?: ""
                    if (m.offsetMinutes == 0) {
                        stringResource(R.string.live_guide_current, name, src)
                    } else {
                        stringResource(R.string.live_guide_current_shifted, name, src, m.offsetMinutes / 60)
                    }
                }
            }
        )
        TvTextField(
            vm.search,
            vm::onSearch,
            stringResource(R.string.live_guide_search_hint),
            Modifier.fillMaxWidth(),
            leading = { Icon(AppIcons.Search, null, tint = Palette.textDim, modifier = Modifier.size(18.dp)) }
        )
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(vm.choices, key = { "${it.candidate.sourceId}/${it.candidate.xmlId}" }) { choice ->
                val current = m?.sourceId == choice.candidate.sourceId && m.xmlId == choice.candidate.xmlId
                TvCard(onClick = { vm.use(choice) }, selected = current, color = Palette.surface, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                choice.candidate.displayName,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Muted("${choice.sourceName} · ${choice.candidate.xmlId}")
                        }
                        choice.score?.let {
                            Text(
                                stringResource(R.string.live_guide_match_score, (it * 100).toInt()),
                                style = MaterialTheme.typography.labelMedium,
                                color = LocalAccent.current.soft
                            )
                        }
                    }
                }
            }
            if (vm.choices.isEmpty()) {
                item {
                    Muted(stringResource(R.string.live_guide_no_results), maxLines = 2)
                }
            }
        }
        Box(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton(stringResource(R.string.live_guide_time_shift, (m?.offsetMinutes ?: 0) / 60), vm::shiftTime)
            PillButton(stringResource(R.string.live_guide_no_guide), vm::noGuide)
            PillButton(stringResource(R.string.live_guide_reset), vm::reset)
        }
    }
}
