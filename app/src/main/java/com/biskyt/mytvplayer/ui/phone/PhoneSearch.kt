package com.biskyt.mytvplayer.ui.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.data.ChannelFilter
import com.biskyt.mytvplayer.data.categoryLabel
import com.biskyt.mytvplayer.data.db.CAT_MOVIE
import com.biskyt.mytvplayer.data.db.CAT_SERIES
import com.biskyt.mytvplayer.data.db.ProgrammeHit
import com.biskyt.mytvplayer.data.db.VodHit
import com.biskyt.mytvplayer.data.programmeTitle
import com.biskyt.mytvplayer.ui.LocalNavigator
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.common.AmbientSource
import com.biskyt.mytvplayer.ui.common.AppIcons
import com.biskyt.mytvplayer.ui.common.ChannelLogo
import com.biskyt.mytvplayer.ui.common.LocalAmbience
import com.biskyt.mytvplayer.ui.common.Muted
import com.biskyt.mytvplayer.ui.common.OnNowLabel
import com.biskyt.mytvplayer.ui.common.TvTextField
import com.biskyt.mytvplayer.ui.common.collectIds
import com.biskyt.mytvplayer.ui.common.formatDayTime
import com.biskyt.mytvplayer.ui.common.isLive
import com.biskyt.mytvplayer.ui.common.rememberMinuteClock
import com.biskyt.mytvplayer.ui.common.vodDetails
import com.biskyt.mytvplayer.ui.search.Picked
import com.biskyt.mytvplayer.ui.search.ResultOptions
import com.biskyt.mytvplayer.ui.search.SearchViewModel
import com.biskyt.mytvplayer.ui.theme.FormFactor
import com.biskyt.mytvplayer.ui.theme.LocalFormFactor
import com.biskyt.mytvplayer.ui.theme.Palette

// Search on a phone or an unfolded foldable (the owner, 2026-11-26): the TV's results, sized for touch. A tap plays or
// opens a result, and a long press opens its options, as holding OK does on a TV.

/** The search box, then the channels, series, films and TV programmes found, from the TV's [SearchViewModel]. */
@Composable
internal fun PhoneSearch(vm: SearchViewModel) {
    val nav = LocalNavigator.current
    val query by vm.query.collectAsState()
    val found = vm.found.collectAsState().value
    val pid = found.playlistId
    val results = found.results
    val now by rememberMinuteClock()
    val favouriteChannels = vm.favourites.channels.collectIds()
    val favouriteSeries = vm.favourites.series.collectIds()
    val favouriteMovies = vm.favourites.movies.collectIds()
    val wide = LocalFormFactor.current == FormFactor.UNFOLDED
    // The keyboard is up on arrival, ready to type.
    val editing = rememberSaveable { mutableStateOf(true) }
    var picked by remember { mutableStateOf<Picked?>(null) }
    val play = { streamId: Long -> nav.push(Screen.Player(pid, streamId, ChannelFilter.All)) }
    // A new search starts at the top of its results; back from a result, the list is where it was.
    var searched by rememberSaveable { mutableStateOf(found.query) }
    LaunchedEffect(found.query) {
        if (found.query != searched) {
            searched = found.query
            vm.phoneList.scrollToItem(0)
        }
    }
    PhoneScaffold(null, stringResource(R.string.common_search)) {
        TvTextField(
            value = query,
            onValueChange = { vm.query.value = it },
            placeholder = stringResource(R.string.search_placeholder),
            modifier = Modifier.fillMaxWidth().padding(horizontal = PhoneGutter, vertical = 6.dp),
            leading = { Icon(AppIcons.Search, null, tint = Palette.textDim, modifier = Modifier.size(20.dp)) },
            editing = editing
        )
        LazyColumn(
            state = vm.phoneList,
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val message = when {
                found.query.isEmpty() -> null
                found.failed -> R.string.search_failed
                results == null -> R.string.search_keep_typing
                results.isEmpty -> R.string.search_nothing_found
                else -> null
            }
            if (message != null) {
                item { Muted(stringResource(message, found.query), Modifier.padding(horizontal = PhoneGutter, vertical = 12.dp)) }
            }
            if (results == null || found.failed) return@LazyColumn
            // Channels, series, films, then TV programmes, as on a TV.
            if (results.channels.isNotEmpty()) {
                item { PhoneSectionTitle(stringResource(R.string.common_channels)) }
                channelCards(
                    "channels",
                    results.channels,
                    wide,
                    vm.nowNext,
                    { picked = Picked.Channel(it.streamId, it.name) },
                    favouriteChannels,
                    { results.channelCategories[it.categoryId] }
                ) { play(it.streamId) }
            }
            vodResults(R.string.common_tab_series, CAT_SERIES, results.series, favouriteSeries, pid) { picked = it }
            vodResults(R.string.common_tab_movies, CAT_MOVIE, results.movies, favouriteMovies, pid) { picked = it }
            if (results.programmes.isNotEmpty()) {
                item { PhoneSectionTitle(stringResource(R.string.search_on_tv)) }
                items(results.programmes, key = { "p${it.streamId}:${it.startMs}" }) { p ->
                    ProgrammeRow(p, now, { picked = Picked.Channel(p.streamId, p.channelName) }) { play(p.streamId) }
                }
            }
        }
    }
    picked?.let { ResultOptions(it, pid, vm) { picked = null } }
}

/** A row of the series or films found, each saying which category it is in (the same title is often in several). */
@Suppress("LongParameterList") // the section's title and type, what was found, and what a poster does
private fun LazyListScope.vodResults(
    title: Int,
    type: String,
    hits: List<VodHit>,
    favourites: Set<Long>,
    pid: Long,
    onOptions: (Picked) -> Unit
) {
    if (hits.isEmpty()) return
    item(key = "vod-$type") {
        val nav = LocalNavigator.current
        PhoneSection(stringResource(title)) {
            PhonePosterRow(hits, VodHit::id) { hit ->
                PhonePoster(
                    hit.card.imageUrl,
                    hit.card.title,
                    hit.category?.let { categoryLabel(it, hit.categoryLang) } ?: hit.card.year?.toString().orEmpty(),
                    favourite = hit.id in favourites,
                    onLongClick = { onOptions(Picked.Vod(type, hit.card)) }
                ) { nav.push(vodDetails(pid, type, hit.id)) }
            }
        }
    }
}

/** A programme found: its channel's logo, its title, the channel, and when it is on. */
@Composable
private fun ProgrammeRow(p: ProgrammeHit, now: Long, onLongClick: () -> Unit, onClick: () -> Unit) {
    val ambience = LocalAmbience.current
    Row(
        Modifier.padding(horizontal = PhoneGutter).fillMaxWidth().heightIn(min = PHONE_CHANNEL_HEIGHT)
            .clip(RoundedCornerShape(14.dp)).background(PhoneCard)
            .touchCard(ambience, AmbientSource.logo(p.logoUrl), onLongClick, onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ChannelLogo(p.logoUrl, p.channelName, width = 64.dp, height = 40.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                programmeTitle(p.title),
                color = Palette.text,
                fontSize = 15.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                listOfNotNull(p.channelName, p.subtitle).joinToString(" · "),
                color = Palette.textDim,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (isLive(p.startMs, p.stopMs, now)) OnNowLabel() else Muted(formatDayTime(p.startMs, now))
    }
}
