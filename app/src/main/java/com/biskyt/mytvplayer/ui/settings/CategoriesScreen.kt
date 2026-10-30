package com.biskyt.mytvplayer.ui.settings

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.AppContainer
import com.biskyt.mytvplayer.AppText
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.data.db.CAT_LIVE
import com.biskyt.mytvplayer.data.db.CAT_MOVIE
import com.biskyt.mytvplayer.data.db.CategoryEntity
import com.biskyt.mytvplayer.data.db.ChannelEntity
import com.biskyt.mytvplayer.data.db.MAX_SQL_ARGS
import com.biskyt.mytvplayer.data.db.VOD_TYPES
import com.biskyt.mytvplayer.data.groupLabel
import com.biskyt.mytvplayer.data.label
import com.biskyt.mytvplayer.ui.LocalNavigator
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.appViewModel
import com.biskyt.mytvplayer.ui.common.FilterField
import com.biskyt.mytvplayer.ui.common.Muted
import com.biskyt.mytvplayer.ui.common.PillButton
import com.biskyt.mytvplayer.ui.common.Tab
import com.biskyt.mytvplayer.ui.common.Toggle
import com.biskyt.mytvplayer.ui.common.TvCard
import com.biskyt.mytvplayer.ui.common.formatCount
import com.biskyt.mytvplayer.ui.common.matchesFilter
import com.biskyt.mytvplayer.ui.common.restoreFocusIf
import com.biskyt.mytvplayer.ui.theme.LocalAccent
import com.biskyt.mytvplayer.ui.theme.Palette
import java.text.Collator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A language/country group of categories, e.g. "English (UK)" with 34 categories. */
data class LanguageGroup(val code: String?, val label: String, val total: Int, val included: Int)

@OptIn(ExperimentalCoroutinesApi::class)
class CategoriesViewModel(private val c: AppContainer, private val playlistId: Long) : ViewModel() {
    /** Which list is being chosen: live channels, movies or series. */
    val type = MutableStateFlow(CAT_LIVE)

    /** The shown type's list. Right after switching, it still holds the previous type's: see [ofType]. */
    val categories = type.flatMapLatest { c.db.categories().observe(playlistId, it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * [list] if it's type [t]'s, else nothing: not the previous type's, held until the new type's list arrives. Each list
     * is of one type, so its first says which.
     */
    fun ofType(list: List<CategoryEntity> = categories.value, t: String = type.value) =
        list.takeIf { it.firstOrNull()?.type == t }.orEmpty()

    /** Movies and series exist for Xtream accounts only. */
    var hasVod by mutableStateOf(false)
        private set
    val hidden = c.channels.observeHidden(playlistId).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    var selectedGroup by mutableStateOf<String?>(UNSET)

    /** Filters both lists: countries/languages by name, and the categories in them. */
    var query by mutableStateOf("")
        private set

    fun filter(q: String) {
        query = q
        selectedGroup = UNSET // the first group shown
    }
    private val changed = HashSet<String>()

    init {
        viewModelScope.launch {
            val p = c.playlists.get(playlistId) ?: return@launch
            hasVod = p.hasVod
        }
    }

    fun showType(t: String) {
        if (t == type.value) return
        type.value = t
        selectedGroup = UNSET
    }

    fun groups(list: List<CategoryEntity>, q: String = ""): List<LanguageGroup> = list.groupBy { it.lang }
        .filter { (code, items) -> matchesFilter(groupLabel(code), q) || items.any { matchesFilter(it.label, q) } }
        .map { (code, items) -> LanguageGroup(code, groupLabel(code), items.size, items.count { it.included }) }
        .sortedWith(
            compareByDescending<LanguageGroup> {
                it.included > 0
            }.thenBy { it.code == null }.thenBy(Collator.getInstance(AppText.locale)) { it.label }
        )

    /** A group's categories, narrowed by [q] unless the group itself is what matched. */
    fun shown(list: List<CategoryEntity>, group: String?, q: String): List<CategoryEntity> {
        val inGroup = list.filter { it.lang == group }
        return if (matchesFilter(groupLabel(group), q)) inGroup else inGroup.filter { matchesFilter(it.label, q) }
    }

    /** Ticks or unticks a group: while filtering, only the categories shown for it. */
    fun toggleGroup(g: LanguageGroup) = setGroup(g.code, shownIn(g.code).any { !it.included })

    /** Ticks or unticks every category of group [code] the right-hand list shows (while filtering, those that match). */
    fun setGroup(code: String?, included: Boolean) = viewModelScope.launch {
        // All of them, not just those the list shows as different: it may not have caught up with the last change yet.
        setIncluded(type.value, shownIn(code).map { it.categoryId }, included)
    }

    private fun shownIn(code: String?) = shown(ofType(), code, query.trim())

    /** Ticks or unticks every language's categories of the type shown: while filtering, only those that match. */
    fun setAll(included: Boolean) = viewModelScope.launch {
        val t = type.value
        val q = query.trim()
        // What the screen shows: categories whose name, or whose group's name, matches.
        val ids = ofType(t = t)
            .filter { matchesFilter(it.label, q) || matchesFilter(groupLabel(it.lang), q) }
            .map { it.categoryId }
        setIncluded(t, ids, included)
    }

    private suspend fun setIncluded(t: String, ids: List<String>, included: Boolean) {
        if (ids.isEmpty()) return
        changed += t
        c.db.withTransaction { ids.chunked(MAX_SQL_ARGS).forEach { c.db.categories().setIncluded(playlistId, t, it, included) } }
    }

    fun toggleCategory(cat: CategoryEntity) = viewModelScope.launch {
        changed += cat.type
        c.db.categories().setIncluded(playlistId, cat.type, listOf(cat.categoryId), !cat.included)
    }

    fun unhide(ch: ChannelEntity) = viewModelScope.launch { c.channels.setHidden(ch.playlistId, ch.streamId, false) }

    /** Downloads again what the change affects: everything on first run or for live TV, else just movies and series. */
    fun finish(firstRun: Boolean) {
        when {
            // The guide comes with it: a playlist that names its own (as the free TV does) has it added as it loads.
            firstRun -> c.appScope.launch { c.sync.refreshNow(playlistId) }
            CAT_LIVE in changed -> c.sync.refresh(playlistId)
            changed.isNotEmpty() -> c.sync.refreshVod(playlistId)
        }
        changed.clear()
    }

    /** However the screen is left (a tab in the top bar too), what changed downloads; [finish] runs it once. */
    override fun onCleared() = finish(firstRun = false)

    /** Focus goes to the first group once, when the screen opens (not each time filtering changes the first group). */
    var firstFocusPending = true

    companion object {
        const val UNSET = "\u0000"
    }
}

@Composable
fun CategoriesScreen(playlistId: Long, firstRun: Boolean) {
    val vm = appViewModel(key = "cats-$playlistId") { CategoriesViewModel(it, playlistId) }
    val nav = LocalNavigator.current
    val listed by vm.categories.collectAsState()
    val type by vm.type.collectAsState()
    // Not the previous type's (its first language would be chosen for this one). Checked against the type as it is now:
    // the one collected above may not have caught up with a switch yet.
    val categories = vm.ofType(listed)
    val hidden by vm.hidden.collectAsState()
    val query = vm.query.trim()
    // Their names are in the app's language: built again when it changes (LocalConfiguration follows it).
    val groups = remember(categories, query, LocalConfiguration.current) { vm.groups(categories, query) }
    val hiddenShown = remember(hidden, query) { hidden.filter { matchesFilter(it.name, query) } }
    if (vm.selectedGroup == CategoriesViewModel.UNSET && groups.isNotEmpty()) vm.selectedGroup = groups.first().code
    val done: () -> Unit = {
        vm.finish(firstRun)
        if (firstRun) nav.reset(Screen.Home) else nav.pop()
    }
    BackHandler(onBack = done)

    Column(
        Modifier.fillMaxSize().padding(horizontal = 72.dp, vertical = 36.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_categories_title), style = MaterialTheme.typography.headlineLarge)
                Muted(stringResource(R.string.settings_categories_intro), maxLines = 2)
            }
            val included = categories.count { it.included }
            val loading = pluralStringResource(R.plurals.settings_categories_loading, categories.size, included, categories.size)
            Muted(loading, Modifier.padding(end = 16.dp))
            PillButton(
                stringResource(if (firstRun) R.string.common_continue else R.string.settings_categories_done),
                done,
                primary = true
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (vm.hasVod) {
                (listOf(CAT_LIVE) + VOD_TYPES).forEach { t ->
                    PillButton(stringResource(typeLabel(t)), { vm.showType(t) }, selected = t == type)
                }
            }
        }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            LazyColumn(
                Modifier.width(380.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                item {
                    FilterField(vm.query, vm::filter, stringResource(R.string.settings_categories_filter), Modifier.fillMaxWidth())
                }
                item {
                    Text(
                        stringResource(R.string.settings_categories_detected),
                        style = MaterialTheme.typography.labelMedium,
                        color = Palette.textMuted
                    )
                }
                // Every language at once (while filtering, the matching ones).
                if (groups.isNotEmpty()) {
                    item {
                        val all = if (query.isEmpty()) R.string.settings_categories_all else R.string.settings_categories_all_matching
                        SelectAllRow(stringResource(all), vm::setAll)
                    }
                }
                if (groups.isEmpty() && query.isNotEmpty()) item { Muted(stringResource(R.string.settings_categories_no_match, query)) }
                items(groups, key = { it.code ?: "" }) { g ->
                    TvCard(
                        onClick = { vm.toggleGroup(g) },
                        selected = g.code == vm.selectedGroup,
                        color = Palette.surfaceLow,
                        modifier = Modifier.restoreFocusIf(vm.firstFocusPending && g == groups.first()) { vm.firstFocusPending = false }
                            .fillMaxWidth()
                            .onFocusChanged { if (it.isFocused) vm.selectedGroup = g.code }
                    ) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(g.label, style = MaterialTheme.typography.titleSmall)
                                Muted(pluralStringResource(R.plurals.settings_categories_count, g.total, g.included, g.total))
                            }
                            Toggle(g.included > 0)
                        }
                    }
                }
                if (type == CAT_LIVE && hiddenShown.isNotEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.settings_categories_hidden),
                            style = MaterialTheme.typography.labelMedium,
                            color = Palette.textMuted,
                            modifier = Modifier.padding(top = 16.dp)
                        )
                    }
                    items(hiddenShown, key = { "h${it.streamId}" }) { ch ->
                        TvCard(onClick = { vm.unhide(ch) }, color = Palette.surfaceLow, modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                Text(
                                    ch.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    stringResource(R.string.settings_categories_unhide),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = LocalAccent.current.color
                                )
                            }
                        }
                    }
                }
            }
            val selected = vm.selectedGroup
            val shown = remember(categories, selected, query) { vm.shown(categories, selected, query) }
            LazyColumn(
                Modifier.weight(1f).background(Palette.surfaceLow).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Just this language's categories: all of them, say, without choosing every language.
                if (shown.isNotEmpty()) {
                    item {
                        SelectAllRow(stringResource(R.string.settings_categories_all_in, groupLabel(selected))) {
                            vm.setGroup(selected, it)
                        }
                    }
                }
                items(shown, key = { it.categoryId }) { cat ->
                    TvCard(onClick = { vm.toggleCategory(cat) }, color = Palette.surface, modifier = Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Toggle(cat.included)
                            Text(
                                cat.label,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (cat.included) Palette.text else Palette.textDim,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (cat.channelCount > 0) Muted(formatCount(cat.channelCount))
                        }
                    }
                }
            }
        }
    }
}

/** "Select all" ([all] names it) and "select none" for the list below. */
@Composable
private fun SelectAllRow(all: String, set: (included: Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PillButton(all, { set(true) })
        PillButton(stringResource(R.string.settings_categories_none), { set(false) })
    }
}

/** The name of a type of category (CAT_LIVE, CAT_MOVIE or CAT_SERIES): its tab's in the menu bar. */
@StringRes
private fun typeLabel(type: String) = when (type) {
    CAT_LIVE -> Tab.LIVE
    CAT_MOVIE -> Tab.MOVIES
    else -> Tab.SERIES
}.label
