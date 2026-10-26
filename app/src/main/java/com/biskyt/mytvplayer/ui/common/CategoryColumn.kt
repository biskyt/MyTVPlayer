package com.biskyt.mytvplayer.ui.common

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.data.db.CategoryEntity
import com.biskyt.mytvplayer.data.groupLabel
import com.biskyt.mytvplayer.data.label
import com.biskyt.mytvplayer.ui.theme.LocalAccent
import com.biskyt.mytvplayer.ui.theme.Palette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The selected entry of a [CategoryColumn]. Focusing an entry switches after a short pause, so moving through
 * categories stays smooth; selecting switches at once. [onChange] runs when the selection changes. Not [settled]
 * (until [openOnShown] has run), the list to open on isn't known yet.
 */
class CategoryFilter<F>(
    private val scope: CoroutineScope,
    initial: F,
    settled: Boolean = true,
    private val onChange: (F) -> Unit = {}
) {
    private val state = MutableStateFlow(initial)
    val value: StateFlow<F> = state
    private var job: Job? = null

    fun focus(f: F) {
        job?.cancel()
        if (f == state.value) return
        job = scope.launch {
            delay(FOCUS_DELAY_MS)
            set(f)
        }
    }

    fun select(f: F) {
        job?.cancel()
        set(f)
    }

    /** Whether the list shown is the one to show: so a list about to give way isn't shown (or focused) meanwhile. */
    var settled by mutableStateOf(settled)
        private set

    // Whether the user has picked a list yet (rather than the one the screen opened on).
    private var userPicked = false

    /**
     * Shows [f] as the list the screen opens on, unless the user has picked one already (a category being focused
     * still switches when its pause is over). Not a pick: no [onChange].
     */
    fun open(f: F) {
        if (!userPicked) state.value = f
        settled = true
    }

    private fun set(f: F) {
        settled = true
        if (f == state.value) return
        userPicked = true
        state.value = f
        onChange(f)
    }

    private companion object {
        const val FOCUS_DELAY_MS = 300L
    }
}

/**
 * Where a screen of lists (Live TV, Movies, Series) opens, once it's known whether the list it starts on would show
 * nothing: a category no longer shown ([gone]) gives way to [favourites], and [favourites] with none ([noFavourites])
 * to [all].
 */
suspend fun <F> CategoryFilter<F>.openOnShown(favourites: F, all: F, gone: suspend (F) -> Boolean, noFavourites: suspend () -> Boolean) {
    val start = value.value
    var open = start
    try {
        val shown = if (gone(start)) favourites else start
        open = if (shown == favourites && noFavourites()) all else shown
    } finally {
        // Whatever happens, a list is shown.
        open(open)
    }
}

/**
 * One entry in a category column: a fixed list (Favourites, All…), or a provider [category] (which can be hidden) with
 * its size.
 */
data class CategoryEntry<F>(val filter: F, val label: String, val count: Int? = null, val category: CategoryEntity? = null)

/**
 * Entries for [categories], named as the playlist names them; a name shared by categories of different groups ("News"
 * from two countries' channels) gets its group's label too, so they can be told apart.
 */
fun <F> categoryEntries(categories: List<CategoryEntity>, filter: (CategoryEntity) -> F): List<CategoryEntry<F>> =
    distinctLabels(categories, { it.label }, { it.lang }) { c, label -> CategoryEntry(filter(c), label, c.channelCount, c) }

/**
 * [items] made into [entry]s, each [label] shared by more than one adding its group ([lang]) when it has one and the
 * group isn't the label itself: "News · Sweden".
 */
fun <T, E> distinctLabels(items: List<T>, label: (T) -> String, lang: (T) -> String?, entry: (T, String) -> E): List<E> {
    val labels = items.map(label)
    val shared = labels.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    return items.mapIndexed { i, item ->
        val group = lang(item)?.takeIf { labels[i] in shared }?.let(::groupLabel)?.takeIf { it != labels[i] }
        entry(item, group?.let { "${labels[i]} · $it" } ?: labels[i])
    }
}

/**
 * The column of categories on the left of Live TV, Movies and Series. Moving up or down to an entry calls [onFocus]
 * (screens switch the list after a short pause); OK calls [onSelect]. Holding OK (or Menu) on a provider category
 * offers to hide it ([onHide]); the entry after it (or before, for the last) is then shown, with focus.
 *
 * The list follows the remote's up/down keys, not focus: focus arriving any other way (e.g. thrown out of a list that
 * changed under it) never switches it. Coming into the column, focus goes to the selected entry. [right]: where right
 * goes from it, when the list beside it isn't level with it (left to find, right could land on the top bar).
 */
@Composable
fun <F> CategoryColumn(
    entries: List<CategoryEntry<F>>,
    selected: F,
    onFocus: (F) -> Unit,
    onSelect: (F) -> Unit,
    onHide: ((CategoryEntity) -> Unit)? = null,
    right: FocusRequester = FocusRequester.Default
) {
    val selectedEntry = remember { FocusRequester() }
    var options by remember { mutableStateOf<CategoryEntity?>(null) }
    val state = rememberLazyListState()
    val selectedIndex = entries.indexOfFirst { it.filter == selected }
    val currentIndex by rememberUpdatedState(selectedIndex)
    fun selectedShown() = state.shows(currentIndex)
    // Picked some other way (remembered, or the one shown was hidden): the column shows it, ready for focus.
    LaunchedEffect(selectedIndex) { if (selectedIndex >= 0 && !selectedShown()) state.scrollToItem(selectedIndex) }
    var refocus by remember { mutableStateOf<Refocus<F>?>(null) }
    RefocusEffect(refocus, selected, { currentIndex }, state, selectedEntry)
    LazyColumn(
        Modifier.width(CATEGORY_WIDTH)
            // Coming in, focus goes to the selected entry; if it isn't in view yet (a slow box still scrolling to it),
            // once the column has scrolled there.
            .focusProperties {
                onEnter = {
                    when {
                        selectedShown() -> runCatching { selectedEntry.requestFocus(requestedFocusDirection) }
                        selectedIndex >= 0 -> {
                            cancelFocusChange()
                            refocus = Refocus(selected)
                        }
                    }
                }
            }
            .focusGroup(),
        state = state,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        itemsIndexed(entries, key = { _, e -> e.label + e.filter.hashCode() }) { i, entry ->
            val (f, label, count) = entry
            val category = entry.category
            val isSelected = f == selected
            TvCard(
                onClick = { onSelect(f) },
                onLongClick = if (onHide != null && category != null) ({ options = category }) else null,
                selected = isSelected,
                color = Palette.background,
                modifier = Modifier.fillMaxWidth()
                    .then(if (isSelected) Modifier.focusRequester(selectedEntry) else Modifier)
                    .rightTo(right)
                    .onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown) {
                            val next = when (e.key) {
                                Key.DirectionUp -> i - 1
                                Key.DirectionDown -> i + 1
                                else -> -1
                            }
                            entries.getOrNull(next)?.let { onFocus(it.filter) }
                        }
                        false // focus moves as usual
                    }
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        label,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (isSelected) LocalAccent.current.color else Palette.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (count != null) Text(formatCount(count), style = MaterialTheme.typography.bodySmall, color = Palette.textDim)
                }
            }
        }
    }
    options?.let { cat ->
        HidePanel(cat, { options = null }) {
            val next = entries.shownAfterHiding(cat)
            next?.let(onSelect)
            onHide?.invoke(cat)
            // Its entry goes, and focus goes to the one now selected (none, if it was the only one).
            next?.let { refocus = Refocus(it) }
        }
    }
}

/**
 * The entry to show once [cat] is hidden (on a TV, the entry with focus): where it was, not back to the top, so the entry
 * after it, or the one before for the last. Chosen at once, it also stands in for a switch to [cat] still pending.
 */
private fun <F> List<CategoryEntry<F>>.shownAfterHiding(cat: CategoryEntity): F? {
    // By id: the entry may have been read again (a new count) while its options were open.
    val i = indexOfFirst { it.category?.categoryId == cat.categoryId }
    return if (i < 0) null else (getOrNull(i + 1) ?: getOrNull(i - 1))?.filter
}

/** A [CategoryColumn]'s request to focus its selected entry once that is [to]: a new one each time, so it always runs. */
private class Refocus<F>(val to: F)

private fun LazyListState.shows(index: Int) = layoutInfo.visibleItemsInfo.any { it.index == index }

/**
 * Carries out [request]: focus goes to the selected entry ([entry], at [index] in [state]) once it's [Refocus.to] and
 * shown. A screen may take a moment to select [Refocus.to] (a flow it collects): until then focus would go to the entry
 * still selected, which may be one being hidden. Then a slow box may take a few frames to lay out the entry it scrolls to.
 */
@Composable
private fun <F> RefocusEffect(request: Refocus<F>?, selected: F, index: () -> Int, state: LazyListState, entry: FocusRequester) {
    val currentSelected by rememberUpdatedState(selected)
    LaunchedEffect(request) {
        val to = request?.to ?: return@LaunchedEffect
        withTimeoutOrNull(SELECT_WAIT_MS) { snapshotFlow { currentSelected }.first { it == to } } ?: return@LaunchedEffect
        repeat(REFOCUS_FRAMES) {
            val i = index()
            if (i >= 0 && !state.shows(i)) state.scrollToItem(i)
            withFrameNanos { }
            if (state.shows(index()) && runCatching { entry.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
}

/** A category's options in the column: hiding it. */
@Composable
private fun HidePanel(cat: CategoryEntity, close: () -> Unit, hide: () -> Unit) {
    val action = ItemAction.Run(stringResource(R.string.common_hide_category), stringResource(R.string.common_hide_category_help)) {
        close()
        hide()
    }
    ItemActionsPanel(cat.label, listOf(action), close)
}

/**
 * A [CategoryColumn] with a box above it to filter its entries by name, for playlists with hundreds of categories. The
 * first [fixed] entries (All, Favourites…) stay, so there's always a way back to them.
 */
@Composable
fun <F> FilteredCategoryColumn(
    entries: List<CategoryEntry<F>>,
    fixed: Int,
    selected: F,
    onFocus: (F) -> Unit,
    onSelect: (F) -> Unit,
    onHide: ((CategoryEntity) -> Unit)? = null,
    right: FocusRequester = FocusRequester.Default
) {
    var query by rememberSaveable { mutableStateOf("") }
    val q = query.trim()
    // The first [fixed] entries, the one chosen when typing began and the one chosen now stay whatever is typed.
    val kept = remember(q.isEmpty()) { selected }
    val shown = remember(entries, fixed, kept, selected, q) {
        // Unfiltered: the same list, so the column has nothing new to work out as the selection moves.
        if (q.isEmpty()) {
            entries
        } else {
            entries.filterIndexed { i, e ->
                i < fixed ||
                    e.filter == kept ||
                    e.filter == selected ||
                    matchesFilter(e.label, q)
            }
        }
    }
    Column(Modifier.width(CATEGORY_WIDTH), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FilterField(query, { query = it }, stringResource(R.string.common_filter_categories), Modifier.fillMaxWidth())
        CategoryColumn(shown, selected, onFocus, onSelect, onHide, right)
    }
}

/** Above a list or grid: its title and size, a filter box, and anything screen-specific ([trailing]). */
@Composable
fun ListHeader(
    title: String,
    count: Int?,
    query: String,
    onQuery: (String) -> Unit,
    placeholder: String,
    filterModifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    Row(
        Modifier.padding(bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.45f, fill = false)
        )
        if (count != null) Muted("· ${formatCount(count)}")
        FilterField(query, onQuery, placeholder, filterModifier.weight(0.55f))
        trailing()
    }
}

/** A box that filters a list as the user types; what's typed is matched as it is (see [matchesFilter]). */
@Composable
fun FilterField(query: String, onQuery: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) = TvTextField(
    value = query,
    onValueChange = onQuery,
    placeholder = placeholder,
    modifier = modifier,
    leading = { Icon(AppIcons.Filter, null, tint = Palette.textDim, modifier = Modifier.size(18.dp)) }
)

/** Whether [text] matches a (trimmed) filter [query]: anywhere in it, in any case. A blank filter matches everything. */
fun matchesFilter(text: String, query: String) = query.isBlank() || text.contains(query, ignoreCase = true)

private val CATEGORY_WIDTH = 250.dp

/** How many frames focus waits for the selected entry to be laid out after scrolling to it. */
private const val REFOCUS_FRAMES = 10

/** How long a refocus waits for the screen to select the entry it's for. */
private const val SELECT_WAIT_MS = 2_000L

/** Right from here goes to [target]; unchanged (the usual search) when it's the default. */
private fun Modifier.rightTo(target: FocusRequester): Modifier =
    if (target == FocusRequester.Default) this else focusProperties { right = target }
