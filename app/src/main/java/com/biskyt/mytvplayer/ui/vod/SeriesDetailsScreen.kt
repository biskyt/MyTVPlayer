package com.biskyt.mytvplayer.ui.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowRgb565
import com.biskyt.mytvplayer.AppContainer
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.appString
import com.biskyt.mytvplayer.core.xtream.EpisodeInfo
import com.biskyt.mytvplayer.core.xtream.SeriesDetails
import com.biskyt.mytvplayer.data.db.CAT_SERIES
import com.biskyt.mytvplayer.data.db.SeriesEntity
import com.biskyt.mytvplayer.data.db.WatchProgressEntity
import com.biskyt.mytvplayer.ui.LocalNavigator
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.appViewModel
import com.biskyt.mytvplayer.ui.common.AppIcons
import com.biskyt.mytvplayer.ui.common.FocusStroke
import com.biskyt.mytvplayer.ui.common.ItemActionsPanel
import com.biskyt.mytvplayer.ui.common.LocalTopBarInset
import com.biskyt.mytvplayer.ui.common.Muted
import com.biskyt.mytvplayer.ui.common.PillButton
import com.biskyt.mytvplayer.ui.common.Poster
import com.biskyt.mytvplayer.ui.common.ProgressLine
import com.biskyt.mytvplayer.ui.common.TvCard
import com.biskyt.mytvplayer.ui.common.formatDate
import com.biskyt.mytvplayer.ui.common.formatRuntime
import com.biskyt.mytvplayer.ui.common.markWatched
import com.biskyt.mytvplayer.ui.common.rememberFirstFocus
import com.biskyt.mytvplayer.ui.common.restoreFocusIf
import com.biskyt.mytvplayer.ui.common.shortEpisodeLabel
import com.biskyt.mytvplayer.ui.common.timeLeft
import com.biskyt.mytvplayer.ui.player.DescribedEpisode
import com.biskyt.mytvplayer.ui.player.EpisodeDescriptionPanel
import com.biskyt.mytvplayer.ui.player.episodeDescriptionAction
import com.biskyt.mytvplayer.ui.player.episodeName
import com.biskyt.mytvplayer.ui.theme.BOLD
import com.biskyt.mytvplayer.ui.theme.BoardStyle
import com.biskyt.mytvplayer.ui.theme.EXTRA_BOLD
import com.biskyt.mytvplayer.ui.theme.LocalAccent
import com.biskyt.mytvplayer.ui.theme.MEDIUM
import com.biskyt.mytvplayer.ui.theme.Palette
import com.biskyt.mytvplayer.ui.theme.bd
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SeriesDetailsViewModel(
    private val c: AppContainer,
    private val playlistId: Long,
    private val seriesId: Long,
    season: Int?
) : ViewModel() {
    var series by mutableStateOf<SeriesEntity?>(null)
        private set
    var details by mutableStateOf<SeriesDetails?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** The season asked for or picked (until then, the one with the episode to carry on with). */
    var season by mutableStateOf(season)

    /** An episode to focus when the page opens (from the TV guide), until it has had focus. */
    var focusEpisodePending = true

    /** Progress per episode id. */
    val progress = c.vod.observeSeriesProgress(playlistId, seriesId).map { list -> list.associateBy { it.itemId } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    val favourite = c.vod.observeFavourite(playlistId, CAT_SERIES, seriesId).stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        c.progressSync.syncSoon()
        load()
    }

    fun load() = viewModelScope.launch {
        error = null
        series = c.vod.series(playlistId, seriesId)
        val p = c.playlists.get(playlistId) ?: return@launch
        try {
            val d = c.vod.seriesDetails(p, seriesId)
            details = d
        } catch (e: java.io.IOException) {
            error = e.message ?: appString(R.string.vod_episodes_failed)
        }
    }

    fun toggleFavourite() = viewModelScope.launch { c.vod.setFavourite(playlistId, CAT_SERIES, seriesId, !favourite.value) }

    /** The episode whose options are open (holding OK on it). */
    var optionsFor by mutableStateOf<EpisodeInfo?>(null)

    /** The episode whose description is open (from its options). */
    var describing by mutableStateOf<EpisodeInfo?>(null)

    /**
     * Marks [e] watched (saved as played to the end, as the player saves it, so "Continue watching" moves on past it)
     * or unwatched (its progress forgotten).
     */
    fun setWatched(e: EpisodeInfo, watched: Boolean) {
        // The list stays on this season (it otherwise follows the episode to carry on with, which this may move on).
        if (season == null) season = e.season
        // Taken now, as the page may load again meanwhile.
        val show = series
        val episodes = details?.episodes.orEmpty()
        c.appScope.launch {
            if (watched) {
                c.vod.markEpisodeWatched(playlistId, seriesId, show, episodes, e)
            } else {
                c.vod.forgetEpisode(playlistId, e.id, episodes.indexOf(e).takeIf { it >= 0 }?.let { episodes.getOrNull(it + 1) })
            }
        }
    }

    /** Whether the remote is in the episode list: changes made there (marking one watched) leave it there. */
    var listFocused by mutableStateOf(false)
}

/**
 * Where to carry on: the last episode watched if it isn't finished, otherwise the one after it; the first episode
 * when nothing has been watched yet.
 */
fun continueEpisode(episodes: List<EpisodeInfo>, progress: Map<Long, WatchProgressEntity>): EpisodeInfo? {
    val last = progress.values.maxByOrNull { it.updatedAt } ?: return episodes.firstOrNull()
    val index = episodes.indexOfFirst { it.id == last.itemId }
    if (index < 0) return episodes.firstOrNull()
    return if (last.finished) episodes.getOrNull(index + 1) ?: episodes[index] else episodes[index]
}

@Composable
fun SeriesDetailsScreen(screen: Screen.SeriesDetails) {
    val vm = appViewModel { SeriesDetailsViewModel(it, screen.playlistId, screen.seriesId, screen.season) }
    val nav = LocalNavigator.current
    val show = vm.series ?: return
    val d = vm.details
    val progress by vm.progress.collectAsState()
    val play = { e: EpisodeInfo -> nav.push(Screen.VodPlayer(screen.playlistId, e.id, screen.seriesId)) }
    val next = d?.let { continueEpisode(it.episodes, progress) }
    // Opened for one episode (from the TV guide): focus goes to it rather than to Continue.
    val target = d?.episodes?.firstOrNull { it.season == screen.season && it.number == screen.episode }
    // The season shown: the one picked; else the one to carry on with (a season the provider doesn't have, as the guide
    // may number them differently, shows the usual one).
    val season = d?.let {
        vm.season?.takeIf { s -> it.episodes.any { e -> e.season == s } } ?: next?.season
            ?: it.seasons.firstOrNull()?.number
    }

    // Right from the left column goes into the episode list (at its first unplayed episode), not up to the top bar
    // when the list is too short to be beside the buttons. Only while the list is shown.
    val episodes = remember { FocusRequester() }
    val toEpisodes = if (vm.error == null && d?.episodes?.isNotEmpty() == true) Modifier.focusProperties { right = episodes } else Modifier
    Box(Modifier.fillMaxSize()) {
        Backdrop(d?.info?.backdropUrl)
        Row(
            Modifier.padding(start = 48.bd, end = 40.bd, top = LocalTopBarInset.current, bottom = 16.bd),
            horizontalArrangement = Arrangement.spacedBy(30.bd)
        ) {
            val favourite by vm.favourite.collectAsState()
            SeriesColumn(show, d, season, moreModifier = toEpisodes) {
                // Continue, and the favourite's heart beside it at the same height, so the column fits the screen.
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.bd)) {
                    if (next != null) {
                        ContinueButton(
                            next,
                            progress,
                            play,
                            if (target == null || !vm.focusEpisodePending) playFocus(vm, next.id) else Modifier
                        )
                    }
                    FavouriteButton(
                        favourite,
                        { vm.toggleFavourite() },
                        (if (next == null && d != null) rememberFirstFocus() else Modifier).then(toEpisodes).fillMaxHeight()
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.bd)) {
                when {
                    vm.error != null -> Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Muted(vm.error.orEmpty())
                        val retryFocus = if (next == null) rememberFirstFocus() else Modifier
                        PillButton(stringResource(R.string.common_retry), { vm.load() }, retryFocus)
                    }
                    d == null -> Muted(stringResource(R.string.vod_loading_episodes))
                    d.episodes.isEmpty() -> Muted(stringResource(R.string.vod_no_episodes))
                    else -> Episodes(vm, d, season, progress, target, play, episodes)
                }
            }
        }
    }
    vm.optionsFor?.let { e -> EpisodeOptions(vm, e, progress[e.id]) }
    vm.describing?.let { e -> EpisodeDescriptionPanel(DescribedEpisode(show.title, e)) { vm.describing = null } }
}

/**
 * The series page's left column (board "TvSeries"), 300 wide: the poster, the title, the facts ([season]'s name, its
 * year if known, else the show's, the number of seasons...), the plot (4 lines at most), and [buttons] (Continue and
 * the favourite's heart). [extraFacts] is where MyPlexPlayer adds its own.
 */
@Composable
fun SeriesColumn(
    show: SeriesEntity,
    d: SeriesDetails?,
    season: Int? = null,
    /** For the More… under the plot: where Right goes from it. */
    moreModifier: Modifier = Modifier,
    extraFacts: @Composable ColumnScope.() -> Unit = {},
    buttons: @Composable ColumnScope.() -> Unit
) {
    // It scrolls when it is taller than the screen (large text): the button with focus is then brought into view.
    Column(Modifier.width(300.bd).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.bd)) {
        Poster(show.coverUrl ?: d?.info?.posterUrl, show.title, 120.bd, radius = 10.bd)
        Text(show.title, style = SeriesTitle.current, maxLines = 2, overflow = TextOverflow.Ellipsis)
        // One season: its name says it, so not "1 season" too.
        val seasons = d?.seasons?.size?.takeIf { it > 1 }?.let { pluralStringResource(R.plurals.vod_seasons, it, it) }
        val current = season?.let { n -> seasonName(n, d?.seasons?.firstOrNull { it.number == n }?.name) }
        // The season's year: when its first episode was first shown.
        val year = d?.episodes?.filter { it.season == season }?.mapNotNull { it.airDate?.year }?.minOrNull()
        val facts = listOfNotNull(
            current,
            metaLine(year ?: show.year ?: d?.info?.year, null, d?.info?.genre, show.rating ?: d?.info?.rating, seasons).ifEmpty { null }
        ).joinToString(" · ")
        if (facts.isNotEmpty()) {
            Text(
                facts,
                style = SeriesFacts.current,
                color = Palette.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        extraFacts()
        // 4 lines at most, so the buttons stay in reach; More… shows the rest.
        d?.info?.plot?.let { PlotWithMore(show.title, it, SeriesPlot.current, maxLines = 4, moreModifier) }
        // Room under the last button for its focus ring: the scrolled column clips what is outside it.
        Column(
            Modifier.padding(top = 4.bd, bottom = FocusStroke.width),
            verticalArrangement = Arrangement.spacedBy(10.bd),
            horizontalAlignment = Alignment.Start,
            content = buttons
        )
    }
}

/** A season's name: the provider's, else the app's ("Season 2"). */
@Composable
private fun seasonName(number: Int, name: String?) = name ?: stringResource(R.string.vod_season, number)

/** "Continue S2 E4 · 21 min left", "Continue S2 E5" (the next one), or "Play S1 E1" when nothing is watched yet. */
@Composable
private fun ContinueButton(e: EpisodeInfo, progress: Map<Long, WatchProgressEntity>, play: (EpisodeInfo) -> Unit, modifier: Modifier) {
    val label = shortEpisodeLabel(e.season, e.number)
    val started = progress[e.id].partWatched()
    val text = when {
        started != null -> stringResource(R.string.vod_continue_left, label, timeLeft(started))
        progress.isNotEmpty() -> stringResource(R.string.vod_continue_episode, label)
        else -> stringResource(R.string.vod_play_episode, label)
    }
    DetailButton(text, { play(e) }, modifier, strong = true, horizontal = 18.bd)
}

/** An episode's options (its More… button, or holding OK on it): its description, and marking it watched or unwatched. */
@Composable
private fun EpisodeOptions(vm: SeriesDetailsViewModel, e: EpisodeInfo, saved: WatchProgressEntity?) {
    val close = { vm.optionsFor = null }
    val set = { watched: Boolean ->
        vm.setWatched(e, watched)
        close()
    }
    // One partly watched can be counted as watched, or started again (its progress forgotten).
    val actions = listOfNotNull(
        episodeDescriptionAction(e) {
            close()
            vm.describing = e
        },
        if (saved?.finished != true) markWatched(false, set) else null,
        if (saved?.started == true) markWatched(true, set) else null
    )
    ItemActionsPanel(episodeName(e, vm.series?.title), actions, close)
}

/** Focus for Play as the page opens and whenever where to carry on changes, unless the remote is in the episode list. */
@Composable
private fun playFocus(vm: SeriesDetailsViewModel, key: Any): Modifier {
    val r = remember { FocusRequester() }
    LaunchedEffect(key) { if (!vm.listFocused) runCatching { r.requestFocus() } }
    return Modifier.focusRequester(r)
}

@Composable
private fun Episodes(
    vm: SeriesDetailsViewModel,
    d: SeriesDetails,
    season: Int?,
    progress: Map<Long, WatchProgressEntity>,
    target: EpisodeInfo?,
    play: (EpisodeInfo) -> Unit,
    /** Focus coming into the list goes to its first unplayed episode, or its first when all are watched. */
    entry: FocusRequester
) {
    if (d.seasons.size > 1) {
        // In by the ring's width, as the episode list is, so the numbers line up with the pills' text.
        LazyRow(Modifier.padding(start = RING), horizontalArrangement = Arrangement.spacedBy(8.bd)) {
            items(d.seasons, key = { it.number }) { s ->
                SeasonPill(seasonName(s.number, s.name), s.number == season) { vm.season = s.number }
            }
        }
    }
    val shown = remember(d, season) { d.episodes.filter { season == null || it.season == season } }
    val list = rememberLazyListState()
    // Scrolled into view first, so the row exists to take focus.
    LaunchedEffect(target, shown) {
        val i = shown.indexOf(target)
        if (vm.focusEpisodePending && i >= 0) list.scrollToItem(i)
    }
    val firstUnplayed = remember(shown, progress) { shown.firstOrNull { progress[it.id]?.finished != true } ?: shown.firstOrNull() }
    val firstUnplayedRow = remember { FocusRequester() }
    LazyColumn(
        state = list,
        modifier = Modifier.onFocusChanged { vm.listFocused = it.hasFocus }
            .focusRequester(entry)
            // Its row if it is laid out (a long list may have it out of view): otherwise the usual first row in view.
            // Only coming in with Right (from the buttons): a row asked for by name (from the TV guide) keeps focus.
            .focusProperties {
                onEnter = {
                    if (requestedFocusDirection ==
                        FocusDirection.Right
                    ) {
                        runCatching { firstUnplayedRow.requestFocus(requestedFocusDirection) }
                    }
                }
            }
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(8.bd),
        // Room for the open row's ring, just outside it.
        contentPadding = PaddingValues(start = RING, end = RING, top = RING, bottom = 24.bd)
    ) {
        items(shown, key = { it.id }) { e ->
            val focus = Modifier.restoreFocusIf(vm.focusEpisodePending && e == target) { vm.focusEpisodePending = false }
                .then(if (e == firstUnplayed) Modifier.focusRequester(firstUnplayedRow) else Modifier)
            EpisodeRow(e, vm.series?.title, progress[e.id], focus, options = { vm.optionsFor = e }) { play(e) }
        }
    }
}

/**
 * An episode (board "TvSeries"). Without focus: one line, its number, its name, a tick when watched, its length (and
 * what is left, with a short bar, when part-watched). With focus it opens out: its picture if it has one, two lines of
 * its name, its length, what is left and when it was first shown, two lines of its plot, a bar, and a More… button on
 * the right (Right moves to it) that opens its options, as holding OK does.
 */
@Composable
private fun EpisodeRow(
    e: EpisodeInfo,
    series: String?,
    progress: WatchProgressEntity?,
    modifier: Modifier,
    options: () -> Unit,
    onClick: () -> Unit
) {
    // Worked out once, not at each focus change (again when the language changes: it may be the app's "Episode 2").
    val name = remember(e, series, LocalConfiguration.current) { episodeName(e, series) }
    var open by remember { mutableStateOf(false) }
    val bring = remember { BringIntoViewRequester() }
    // Open, it is taller than when the list scrolled to it: all of it is brought into view, once it has its size.
    LaunchedEffect(open) {
        if (open) {
            withFrameNanos { }
            bring.bringIntoView()
        }
    }
    val shape = RoundedCornerShape(if (open) 12.bd else 10.bd)
    Row(
        Modifier.fillMaxWidth().bringIntoViewRequester(bring).onFocusChanged { open = it.hasFocus }
            .then(if (open) Modifier.drawBehind { drawRing() } else Modifier)
            .clip(shape).background(if (open) Palette.surfaceHigh else Color.Transparent),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TvCard(
            onClick = onClick,
            onLongClick = options,
            color = Color.Transparent,
            focusedColor = Color.Transparent,
            ring = false,
            shape = shape,
            modifier = modifier.weight(1f)
        ) {
            if (open) OpenEpisode(e, name, progress) else ClosedEpisode(e, name, progress)
        }
        if (open) {
            DetailButton(
                stringResource(R.string.vod_more),
                options,
                Modifier.padding(end = 14.bd),
                horizontal = 16.bd,
                vertical = 8.bd,
                style = MoreText.current,
                background = Palette.background
            )
        }
    }
}

/** The open row's ring: 3 wide, just outside it (the board's box-shadow 0 0 0 3px). */
private fun DrawScope.drawRing() {
    val w = RING.toPx()
    drawRoundRect(
        FocusStroke.brush,
        topLeft = Offset(-w / 2, -w / 2),
        size = Size(size.width + w, size.height + w),
        cornerRadius = CornerRadius(12.bd.toPx() + w / 2),
        style = Stroke(w)
    )
}

private val RING = FocusStroke.width

/** What is left of a part-watched episode, or null. */
private fun WatchProgressEntity?.partWatched() = this?.takeIf { it.started && !it.finished && it.durationMs > 0 }

@Composable
private fun EpisodeNumber(e: EpisodeInfo, open: Boolean) =
    Text("${e.number}", style = NumberText.current, color = if (open) Palette.text else Palette.textDim, modifier = Modifier.width(24.bd))

@Composable
private fun ClosedEpisode(e: EpisodeInfo, name: String, progress: WatchProgressEntity?) {
    Row(
        Modifier.padding(horizontal = 14.bd, vertical = 7.bd),
        horizontalArrangement = Arrangement.spacedBy(14.bd),
        verticalAlignment = Alignment.CenterVertically
    ) {
        EpisodeNumber(e, open = false)
        Column(verticalArrangement = Arrangement.spacedBy(2.bd)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.bd), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name,
                    style = EpisodeTitle.current,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (progress?.finished == true) WatchedTick()
            }
            val part = progress.partWatched()
            val length = listOfNotNull(e.durationSec?.let(::formatRuntime), part?.let { timeLeftText(it) }).joinToString(" · ")
            if (length.isNotEmpty()) Text(length, style = EpisodeSmall.current, color = Palette.textDim, maxLines = 1)
            if (part != null) ProgressLine(part.fraction, Modifier.padding(top = 3.bd).width(160.bd), height = 3.bd)
        }
    }
}

@Composable
private fun OpenEpisode(e: EpisodeInfo, name: String, progress: WatchProgressEntity?) {
    Row(Modifier.padding(horizontal = 14.bd, vertical = 10.bd), horizontalArrangement = Arrangement.spacedBy(14.bd)) {
        EpisodeNumber(e, open = true)
        e.imageUrl?.let { EpisodePicture(it) }
        Column(verticalArrangement = Arrangement.spacedBy(3.bd)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.bd), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name,
                    style = OpenTitle.current,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (progress?.finished == true) WatchedTick()
            }
            val part = progress.partWatched()
            val info = listOfNotNull(
                e.durationSec?.let(::formatRuntime),
                part?.let { timeLeftText(it) },
                e.airDate?.let { stringResource(R.string.vod_first_shown, formatDate(it)) }
            ).joinToString(" · ")
            if (info.isNotEmpty()) {
                Text(
                    info,
                    style = EpisodeSmall.current,
                    color = Palette.textMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            e.plot?.let {
                Text(it, style = OpenPlot.current, color = Palette.textDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (part != null) ProgressLine(part.fraction, Modifier.padding(top = 3.bd).width(260.bd), height = 4.bd)
        }
    }
}

@Composable
private fun timeLeftText(p: WatchProgressEntity) = stringResource(R.string.common_time_left, timeLeft(p))

/** "✓ Watched", in the accent colour. */
@Composable
private fun WatchedTick() {
    val accent = LocalAccent.current.color
    Row(horizontalArrangement = Arrangement.spacedBy(3.bd), verticalAlignment = Alignment.CenterVertically) {
        Icon(AppIcons.Tick, contentDescription = null, tint = accent, modifier = Modifier.size(12.bd))
        Text(stringResource(R.string.vod_watched), style = WatchedText.current, color = accent, maxLines = 1)
    }
}

/** The episode's picture (Xtream's movie_image), 128 x 72. */
@Composable
private fun EpisodePicture(url: String) {
    val context = LocalContext.current
    val request =
        remember(url) { ImageRequest.Builder(context).data(url).size(PICTURE_WIDTH, PICTURE_WIDTH * 9 / 16).allowRgb565(true).build() }
    AsyncImage(request, null, contentScale = ContentScale.Crop, modifier = Modifier.size(128.bd, 72.bd).clip(RoundedCornerShape(6.bd)))
}

private const val PICTURE_WIDTH = 256

// The series page's text (the boards' sizes; colours are given where they differ).
private val SeriesTitle = BoardStyle(24, EXTRA_BOLD, 1.2f)
private val SeriesFacts = BoardStyle(14, BOLD)
private val SeriesPlot = BoardStyle(14, MEDIUM, 1.4f)
private val NumberText = BoardStyle(20, EXTRA_BOLD)
private val EpisodeTitle = BoardStyle(15, EXTRA_BOLD)
private val OpenTitle = BoardStyle(15, EXTRA_BOLD, 1.25f)
private val EpisodeSmall = BoardStyle(13, MEDIUM)
private val OpenPlot = BoardStyle(13, MEDIUM, 1.35f)
private val WatchedText = BoardStyle(12, EXTRA_BOLD)
private val MoreText = BoardStyle(14, BOLD)
