package com.biskyt.mytvplayer.ui.phone

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowRgb565
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.core.xtream.EpisodeInfo
import com.biskyt.mytvplayer.core.xtream.SeriesDetails
import com.biskyt.mytvplayer.data.db.SeriesEntity
import com.biskyt.mytvplayer.data.db.WatchProgressEntity
import com.biskyt.mytvplayer.ui.common.AppIcons
import com.biskyt.mytvplayer.ui.common.ProgressLine
import com.biskyt.mytvplayer.ui.common.formatRuntime
import com.biskyt.mytvplayer.ui.common.rememberTrailerOpener
import com.biskyt.mytvplayer.ui.common.shortEpisodeLabel
import com.biskyt.mytvplayer.ui.common.timeLeft
import com.biskyt.mytvplayer.ui.player.episodeName
import com.biskyt.mytvplayer.ui.theme.LocalAccent
import com.biskyt.mytvplayer.ui.theme.Palette
import com.biskyt.mytvplayer.ui.vod.SeriesDetailsViewModel
import com.biskyt.mytvplayer.ui.vod.SeriesPanel
import com.biskyt.mytvplayer.ui.vod.TmdbScore
import com.biskyt.mytvplayer.ui.vod.metaLine
import com.biskyt.mytvplayer.ui.vod.nextEpisodeLine
import com.biskyt.mytvplayer.ui.vod.seasonName
import com.biskyt.mytvplayer.ui.vod.seriesStatusLine

// The series page on a phone or an unfolded foldable (phone spike, 2026-11-25): the film page's header (PhoneFilm.kt),
// then the seasons and their episodes.

/**
 * The series page on a phone: the header, Continue (or Play) across the page, then Favourite, Trailer and More (the
 * options of the episode to carry on with); the plot; the seasons as chips that scroll sideways, and [season]'s
 * episodes. A tap on an episode plays it ([play]); a long press opens its options, as holding OK does on a TV.
 * Unfolded, the seasons and episodes are on the right. [next] is the episode to carry on with; [d] null while it loads.
 */
@Composable
@Suppress("LongParameterList") // the page's state, passed down rather than read again
fun PhoneSeriesPage(
    vm: SeriesDetailsViewModel,
    show: SeriesEntity,
    d: SeriesDetails?,
    season: Int?,
    next: EpisodeInfo?,
    progress: Map<Long, WatchProgressEntity>,
    play: (EpisodeInfo) -> Unit
) {
    val info = vm.info
    val favourite by vm.favourite.collectAsState()
    val trailer = rememberTrailerOpener()
    PhoneDetailLayout(
        left = {
            PhoneDetailHeader(info.backdropUrl, show.coverUrl ?: info.posterUrl, show.title) {
                val seasons = d?.seasons?.size?.takeIf { it > 1 && info.tmdb?.seasonCount == null }
                    ?.let { pluralStringResource(R.plurals.vod_seasons, it, it) }
                val year = d?.episodes?.filter { it.season == season }?.mapNotNull { it.airDate?.year }?.minOrNull()
                val facts = metaLine((year ?: show.year ?: info.year)?.toString(), null, info.genre, show.rating ?: info.rating, seasons)
                if (facts.isNotEmpty()) PhoneFacts(facts)
                seriesStatusLine(info.tmdb)?.let { PhoneSmallText(it, maxLines = 2) }
                nextEpisodeLine(info.tmdb)?.let { PhoneSmallText(it, maxLines = 2) }
                info.score?.let { TmdbScore(it) }
            }
            Column(Modifier.padding(start = PAGE_EDGE, end = PAGE_EDGE, top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val options = next?.let { e -> { vm.open = SeriesPanel.Options(e) } }
                if (next != null) {
                    val started = progress[next.id].partWatched()
                    PhoneMainButton(continueText(next, started, progress.isNotEmpty()), started?.fraction, options) { play(next) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val tile = Modifier.weight(1f)
                    PhoneTileButton(
                        if (favourite) AppIcons.Heart else AppIcons.HeartOutline,
                        stringResource(R.string.common_favourite_mark),
                        tile
                    ) { vm.toggleFavourite() }
                    info.trailer?.let { url ->
                        PhoneTileButton(PhoneIcons.Trailer, stringResource(R.string.vod_trailer), tile) { trailer(url) }
                    }
                    options?.let { PhoneTileButton(PhoneIcons.More, stringResource(R.string.vod_more).trimEnd('…'), tile, it) }
                }
            }
            info.plot?.let { PhonePlot(show.title, it, PLOT_LINES) }
        },
        right = { _ ->
            when {
                vm.error != null -> Column(Modifier.padding(horizontal = PAGE_EDGE), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PhoneSmallText(vm.error.orEmpty(), maxLines = 3)
                    Text(
                        stringResource(R.string.common_retry),
                        color = LocalAccent.current.color,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button) { vm.load() }
                            .padding(vertical = 10.dp)
                    )
                }
                d == null -> PhoneSmallText(stringResource(R.string.vod_loading_episodes), Modifier.padding(horizontal = PAGE_EDGE))
                d.episodes.isEmpty() -> PhoneSmallText(
                    stringResource(R.string.vod_no_episodes),
                    Modifier.padding(horizontal = PAGE_EDGE),
                    maxLines = 3
                )
                else -> {
                    if (d.seasons.size > 1) SeasonChips(d, season) { vm.season = it }
                    val shown = remember(d, season) { d.episodes.filter { season == null || it.season == season } }
                    // A column, not a lazy list: the page scrolls as a whole, and a season has tens of episodes at most.
                    Column {
                        shown.forEach { e ->
                            PhoneEpisodeRow(e, show.title, progress[e.id], { vm.open = SeriesPanel.Options(e) }) { play(e) }
                        }
                    }
                }
            }
        }
    )
}

/** "Continue S2 E4 · 21 min left", "Continue S2 E5" (the next one), or "Play S1 E1" when nothing is watched yet. */
@Composable
private fun continueText(e: EpisodeInfo, started: WatchProgressEntity?, watchedAny: Boolean): String {
    val label = shortEpisodeLabel(e.season, e.number)
    return when {
        started != null -> stringResource(R.string.vod_continue_left, label, timeLeft(started))
        watchedAny -> stringResource(R.string.vod_continue_episode, label)
        else -> stringResource(R.string.vod_play_episode, label)
    }
}

/** The seasons as chips in a row that scrolls sideways: the one shown in the accent colour. */
@Composable
private fun SeasonChips(d: SeriesDetails, season: Int?, pick: (Int) -> Unit) {
    val accent = LocalAccent.current
    LazyRow(contentPadding = PaddingValues(horizontal = PAGE_EDGE), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(d.seasons, key = { it.number }) { s ->
            val current = s.number == season
            Text(
                seasonName(s.number, s.name),
                color = if (current) accent.onAccent else Palette.text,
                fontSize = 14.sp,
                fontWeight = if (current) FontWeight.ExtraBold else FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(20.dp))
                    .background(if (current) accent.color else PhoneChip)
                    .clickable(role = Role.Tab) { pick(s.number) }
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            )
        }
    }
}

/**
 * An episode: its still (or its number when it has none), its code and name, its running time and what is left, a bar
 * when part-watched, and a tick when watched. A tap plays it ([onClick]); a long press opens [options].
 */
@Composable
private fun PhoneEpisodeRow(e: EpisodeInfo, series: String, progress: WatchProgressEntity?, options: () -> Unit, onClick: () -> Unit) {
    val name = remember(e, series, LocalConfiguration.current) { episodeName(e, series) }
    val accent = LocalAccent.current.color
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp)
            .combinedClickable(role = Role.Button, onLongClick = options, onClick = onClick)
            .padding(horizontal = PAGE_EDGE, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(STILL_WIDTH, STILL_WIDTH * 9 / 16).clip(RoundedCornerShape(8.dp)).background(PhoneChip),
            contentAlignment = Alignment.Center
        ) {
            Text("${e.number}", color = Palette.textDim, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
            e.imageUrl?.let { url ->
                val context = LocalContext.current
                val request =
                    remember(url) { ImageRequest.Builder(context).data(url).size(STILL_PX, STILL_PX * 9 / 16).allowRgb565(true).build() }
                AsyncImage(request, null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                shortEpisodeLabel(e.season, e.number),
                color = Palette.textDim,
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1
            )
            Text(
                name,
                color = Palette.text,
                fontSize = 15.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            val part = progress.partWatched()
            val length = listOfNotNull(
                e.durationSec?.let(::formatRuntime),
                part?.let { stringResource(R.string.common_time_left, timeLeft(it)) }
            ).joinToString(" · ")
            if (length.isNotEmpty()) Text(length, color = Palette.textDim, fontSize = 13.sp, maxLines = 1)
            if (part != null) ProgressLine(part.fraction, Modifier.padding(top = 3.dp).fillMaxWidth(), height = 3.dp)
        }
        if (progress?.finished == true) {
            Image(AppIcons.Tick, stringResource(R.string.vod_watched), Modifier.size(20.dp), colorFilter = ColorFilter.tint(accent))
        }
    }
}

/** What is left of a part-watched episode, or null. */
private fun WatchProgressEntity?.partWatched() = this?.takeIf { it.started && !it.finished && it.durationMs > 0 }

private val STILL_WIDTH = 120.dp
private const val STILL_PX = 320
private const val PLOT_LINES = 4
