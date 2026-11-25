package com.biskyt.mytvplayer.ui.phone

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
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
import com.biskyt.mytvplayer.ui.common.favouriteIcon
import com.biskyt.mytvplayer.ui.common.favouriteLabel
import com.biskyt.mytvplayer.ui.common.rememberTrailerOpener
import com.biskyt.mytvplayer.ui.common.shortEpisodeLabel
import com.biskyt.mytvplayer.ui.player.episodeName
import com.biskyt.mytvplayer.ui.theme.LocalAccent
import com.biskyt.mytvplayer.ui.theme.Palette
import com.biskyt.mytvplayer.ui.vod.SeriesDetailsViewModel
import com.biskyt.mytvplayer.ui.vod.SeriesPanel
import com.biskyt.mytvplayer.ui.vod.TmdbScore
import com.biskyt.mytvplayer.ui.vod.continueText
import com.biskyt.mytvplayer.ui.vod.episodeLength
import com.biskyt.mytvplayer.ui.vod.nextEpisodeLine
import com.biskyt.mytvplayer.ui.vod.partWatched
import com.biskyt.mytvplayer.ui.vod.seasonName
import com.biskyt.mytvplayer.ui.vod.seriesFacts
import com.biskyt.mytvplayer.ui.vod.seriesStatusLine

// The series page on a phone or an unfolded foldable (the owner, 2026-11-25): the film page's header (PhoneFilm.kt),
// then the seasons and their episodes.

/**
 * The series page on a phone: the header, Continue (or Play) across the page, then Favourite, Trailer and More (the
 * options of the episode to carry on with); the plot; the seasons as chips that scroll sideways, and [season]'s
 * episodes. A tap on an episode plays it ([play]); a long press opens its options, as holding OK does on a TV.
 * Unfolded, the seasons and episodes are on the right. [next] is the episode to carry on with; [d] null while it loads.
 * [target] is the episode the page was opened at (the player's Episodes button): the page scrolls to it.
 */
@Composable
@Suppress("LongParameterList") // the page's state, passed down rather than read again
fun PhoneSeriesPage(
    vm: SeriesDetailsViewModel,
    show: SeriesEntity,
    d: SeriesDetails?,
    season: Int?,
    next: EpisodeInfo?,
    target: EpisodeInfo?,
    progress: Map<Long, WatchProgressEntity>,
    play: (EpisodeInfo) -> Unit
) {
    val info = vm.info
    PhoneDetailLayout(
        left = {
            PhoneDetailHeader(info.backdropUrl, show.coverUrl ?: info.posterUrl, show.title) {
                val facts = seriesFacts(show, d, info, season)
                if (facts.isNotEmpty()) PhoneFacts(facts)
                seriesStatusLine(info.tmdb)?.let { PhoneSmallText(it, maxLines = 2) }
                nextEpisodeLine(info.tmdb)?.let { PhoneSmallText(it, maxLines = 2) }
                info.score?.let { TmdbScore(it) }
            }
            SeriesButtons(vm, next, progress, play)
            info.plot?.let { PhonePlot(show.title, it, PLOT_LINES) }
        },
        right = { _ -> EpisodesPane(vm, show, d, season, target, progress, play) }
    )
}

/** Continue (or Play) across the page, then Favourite, Trailer and More (the options of [next], the episode to carry on with). */
@Composable
private fun SeriesButtons(
    vm: SeriesDetailsViewModel,
    next: EpisodeInfo?,
    progress: Map<Long, WatchProgressEntity>,
    play: (EpisodeInfo) -> Unit
) {
    val favourite by vm.favourite.collectAsState()
    val trailer = rememberTrailerOpener()
    Column(Modifier.padding(start = PhoneGutter, end = PhoneGutter, top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val options = next?.let { e -> { vm.open = SeriesPanel.Options(e) } }
        if (next != null) {
            PhoneMainButton(continueText(next, progress), progress[next.id].partWatched()?.fraction, options) { play(next) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val tile = Modifier.weight(1f)
            PhoneTileButton(
                favouriteIcon(favourite),
                stringResource(R.string.common_favourite_mark),
                tile,
                iconDescription = favouriteLabel(favourite)
            ) { vm.toggleFavourite() }
            vm.info.trailer?.let { url ->
                PhoneTileButton(AppIcons.Trailer, stringResource(R.string.vod_trailer), tile) { trailer(url) }
            }
            options?.let { PhoneTileButton(AppIcons.More, moreLabel(), tile, onClick = it) }
        }
    }
}

/**
 * The episodes, or why there are none yet: the seasons as chips (when there is more than one), then [season]'s
 * episodes. The page scrolls to [target] once, as it opens.
 */
@Composable
@Suppress("LongParameterList") // the page's state, passed down rather than read again
private fun EpisodesPane(
    vm: SeriesDetailsViewModel,
    show: SeriesEntity,
    d: SeriesDetails?,
    season: Int?,
    target: EpisodeInfo?,
    progress: Map<Long, WatchProgressEntity>,
    play: (EpisodeInfo) -> Unit
) {
    when {
        vm.error != null -> Column(Modifier.padding(horizontal = PhoneGutter), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PhoneSmallText(vm.error.orEmpty(), maxLines = 3)
            PhonePill(stringResource(R.string.common_retry)) { vm.load() }
        }
        d == null -> PhoneSmallText(stringResource(R.string.vod_loading_episodes), Modifier.padding(horizontal = PhoneGutter))
        d.episodes.isEmpty() -> PhoneSmallText(
            stringResource(R.string.vod_no_episodes),
            Modifier.padding(horizontal = PhoneGutter),
            maxLines = 3
        )
        else -> {
            if (d.seasons.size > 1) {
                PhoneChipRow(d.seasons, { it.number == season }, { it.number }) { s, chosen ->
                    PhoneChoiceChip(seasonName(s.number, s.name), chosen) { vm.season = s.number }
                }
            }
            val shown = remember(d, season) { d.episodes.filter { season == null || it.season == season } }
            // A column, not a lazy list: the page scrolls as a whole, and a season has tens of episodes at most.
            Column {
                shown.forEach { e ->
                    val scrollTo = if (e == target && vm.focusEpisodePending) ({ vm.focusEpisodePending = false }) else null
                    PhoneEpisodeRow(e, show.title, progress[e.id], scrollTo, { vm.open = SeriesPanel.Options(e) }) { play(e) }
                }
            }
        }
    }
}

/**
 * An episode: its still (or its number when it has none), its code and name, its running time and what is left, a bar
 * when part-watched, and a tick when watched. A tap plays it ([onClick]); a long press opens [options]. With
 * [scrolledTo], the page scrolls to it once it is laid out, then calls that.
 */
@Composable
private fun PhoneEpisodeRow(
    e: EpisodeInfo,
    series: String,
    progress: WatchProgressEntity?,
    scrolledTo: (() -> Unit)?,
    options: () -> Unit,
    onClick: () -> Unit
) {
    val name = remember(e, series, LocalConfiguration.current) { episodeName(e, series) }
    val accent = LocalAccent.current.color
    val bring = remember { BringIntoViewRequester() }
    if (scrolledTo != null) {
        LaunchedEffect(Unit) {
            withFrameNanos { } // once it has its place on the page
            bring.bringIntoView()
            scrolledTo()
        }
    }
    Row(
        Modifier.fillMaxWidth().bringIntoViewRequester(bring).heightIn(min = 72.dp)
            .combinedClickable(role = Role.Button, onLongClick = options, onClick = onClick)
            .padding(horizontal = PhoneGutter, vertical = 8.dp),
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
            val length = episodeLength(e, part)
            if (length.isNotEmpty()) Text(length, color = Palette.textDim, fontSize = 13.sp, maxLines = 1)
            if (part != null) ProgressLine(part.fraction, Modifier.padding(top = 3.dp).fillMaxWidth(), height = 3.dp)
        }
        if (progress?.finished == true) {
            Image(AppIcons.Tick, stringResource(R.string.vod_watched), Modifier.size(20.dp), colorFilter = ColorFilter.tint(accent))
        }
    }
}

private val STILL_WIDTH = 120.dp
private const val STILL_PX = 320
private const val PLOT_LINES = 4
