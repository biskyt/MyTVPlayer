package com.biskyt.mytvplayer.ui.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.data.ChannelFilter
import com.biskyt.mytvplayer.data.db.ChannelEntity
import com.biskyt.mytvplayer.data.db.VOD_TYPES
import com.biskyt.mytvplayer.data.db.VodCard
import com.biskyt.mytvplayer.ui.LocalNavigator
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.common.AmbientSource
import com.biskyt.mytvplayer.ui.common.AppIcons
import com.biskyt.mytvplayer.ui.common.LocalAmbience
import com.biskyt.mytvplayer.ui.common.NowNextLoader
import com.biskyt.mytvplayer.ui.common.Poster
import com.biskyt.mytvplayer.ui.common.ProgressLine
import com.biskyt.mytvplayer.ui.common.Tab
import com.biskyt.mytvplayer.ui.common.rememberMinuteClock
import com.biskyt.mytvplayer.ui.common.vodDetails
import com.biskyt.mytvplayer.ui.home.HomeTile
import com.biskyt.mytvplayer.ui.home.HomeViewModel
import com.biskyt.mytvplayer.ui.home.TileOptions
import com.biskyt.mytvplayer.ui.home.carryOnLeft
import com.biskyt.mytvplayer.ui.home.carryOnProgress
import com.biskyt.mytvplayer.ui.home.favouriteRowTitle
import com.biskyt.mytvplayer.ui.theme.FormFactor
import com.biskyt.mytvplayer.ui.theme.LocalAccent
import com.biskyt.mytvplayer.ui.theme.LocalFormFactor
import com.biskyt.mytvplayer.ui.theme.Palette

// Home on a phone or an unfolded foldable (the owner, 2026-11-25): the TV's rows, sized for touch. A tap plays or
// opens a card, and a long press opens its options, as holding OK does on a TV.

/** Home, from the TV's [HomeViewModel]: Continue watching, the favourite channels, recently watched, then favourites. */
@Composable
fun PhoneHome(vm: HomeViewModel) {
    val nav = LocalNavigator.current
    val favourites by vm.favourites.collectAsState()
    val recent by vm.recent.collectAsState()
    val carryOn = vm.carryOn.collectAsState().value.orEmpty()
    val favouriteVod = VOD_TYPES.associateWith { vm.favouriteVod.getValue(it).collectAsState().value }
    val pid by vm.playlistId.collectAsState()
    var options by remember { mutableStateOf<HomeTile?>(null) }
    val wide = LocalFormFactor.current == FormFactor.UNFOLDED
    // A touch moves no focus: the background follows the first card shown until a card is touched.
    val ambience = LocalAmbience.current
    val firstPicture = carryOn.firstOrNull()?.imageUrl
    LaunchedEffect(firstPicture) { if (ambience != null && ambience.source == null) ambience.source = AmbientSource.picture(firstPicture) }
    PhoneScaffold(Tab.HOME, stringResource(R.string.app_name), actions = {
        // The rail has Settings of its own.
        if (!wide) PhoneIconButton(AppIcons.Settings, stringResource(R.string.common_settings), { nav.open(Screen.Settings) })
    }) {
        // Cards 10 dp apart; each section title has 12 dp more above it.
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (carryOn.isNotEmpty()) {
                item {
                    PhoneSection(stringResource(R.string.home_carry_on)) {
                        PhonePosterRow(carryOn, { "${it.kind}${it.itemId}" }) { p ->
                            PhonePoster(
                                p.imageUrl,
                                p.title,
                                carryOnLeft(p),
                                progress = p.carryOnProgress,
                                onLongClick = { options = HomeTile.CarryOn(p) }
                            ) {
                                // (vm.played is not set: it moves a TV's focus back to the row, and a touch screen has none.)
                                nav.push(Screen.VodPlayer(pid, p.itemId, p.seriesId, continueSeries = true))
                            }
                        }
                    }
                }
            }
            item { PhoneSectionTitle(stringResource(R.string.home_on_now)) }
            val favs = favourites
            when {
                favs == null -> Unit
                favs.isEmpty() -> item { EmptyFavourites { nav.reset(Screen.Live()) } }
                else -> channelCards("fav", favs, wide, vm.nowNext, { options = HomeTile.Channel(it, ChannelFilter.Favourites) }) {
                    nav.push(Screen.Player(pid, it.streamId, ChannelFilter.Favourites))
                }
            }
            if (recent.isNotEmpty()) {
                item { PhoneSectionTitle(stringResource(R.string.common_recent)) }
                channelCards("recent", recent, wide, vm.nowNext, { options = HomeTile.Channel(it, ChannelFilter.Recent) }) {
                    nav.push(Screen.Player(pid, it.streamId, ChannelFilter.Recent))
                }
            }
            // Favourite series, then movies.
            favouriteVod.forEach { (type, cards) ->
                if (cards.isNotEmpty()) {
                    item(key = "favourites-$type") {
                        PhoneSection(stringResource(favouriteRowTitle(type))) {
                            PhonePosterRow(cards, VodCard::id) { card ->
                                PhonePoster(card.imageUrl, card.title, card.year?.toString().orEmpty(), onLongClick = {
                                    options = HomeTile.Vod(type, card)
                                }) { nav.push(vodDetails(pid, type, card.id)) }
                            }
                        }
                    }
                }
            }
        }
    }
    options?.let { tile -> TileOptions(tile, vm, pid) { options = null } }
}

/** A row's title over [content]. */
@Composable
internal fun PhoneSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PhoneSectionTitle(title)
        content()
    }
}

@Composable
internal fun PhoneSectionTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        color = Palette.text,
        fontSize = 18.sp,
        fontWeight = FontWeight.ExtraBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(start = PhoneGutter, end = PhoneGutter, top = 12.dp)
    )
}

/** A row of posters that scrolls sideways. */
@Composable
internal fun <T : Any> PhonePosterRow(items: List<T>, key: (T) -> Any, card: @Composable (T) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = PhoneGutter), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(items, key = key) { card(it) }
    }
}

/** A poster for a phone: the poster, then its title and [detail] under it, with how far it got ([progress]) if given. */
@Composable
internal fun PhonePoster(
    imageUrl: String?,
    title: String,
    detail: String,
    width: Dp = PHONE_POSTER_WIDTH,
    progress: Float? = null,
    favourite: Boolean = false,
    onLongClick: () -> Unit,
    onClick: () -> Unit
) {
    Column(
        Modifier.width(
            width
        ).clip(RoundedCornerShape(10.dp)).touchCard(LocalAmbience.current, AmbientSource.picture(imageUrl), onLongClick, onClick),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box {
            // (Its initials, shown until the picture loads, are not read: the text under it is.)
            Poster(imageUrl, title, width, Modifier.clearAndSetSemantics {})
            if (progress != null) ProgressLine(progress, Modifier.align(Alignment.BottomCenter).padding(8.dp), height = 4.dp)
            if (favourite) FavouriteMark(Modifier.align(Alignment.TopEnd).padding(6.dp))
        }
        Text(
            title,
            color = Palette.text,
            fontSize = 13.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(detail.ifEmpty { " " }, color = Palette.textDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A favourite's heart, on the corner of its poster. */
@Composable
private fun FavouriteMark(modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(11.dp)).background(Palette.background).padding(4.dp)) {
        androidx.tv.material3.Icon(
            AppIcons.Heart,
            stringResource(R.string.common_favourite_mark),
            tint = LocalAccent.current.color,
            modifier = Modifier.width(13.dp)
        )
    }
}

/** Channel cards one under the other; on an unfolded screen, two side by side. */
@Suppress("LongParameterList") // the row's key, its channels and layout, and what a card does
internal fun LazyListScope.channelCards(
    /** Which row ("fav", "recent"): a channel can be in both, and keys must differ. */
    row: String,
    channels: List<ChannelEntity>,
    wide: Boolean,
    loader: NowNextLoader,
    onOptions: (ChannelEntity) -> Unit,
    /** The channels marked with a heart. */
    favourites: Set<Long> = emptySet(),
    /** A line under a channel's name (search's category). */
    detail: (ChannelEntity) -> String? = { null },
    onPlay: (ChannelEntity) -> Unit
) {
    val columns = if (wide) 2 else 1
    items(channels.chunked(columns), key = { cards -> "$row-" + cards.joinToString { it.streamId.toString() } }) { cards ->
        val now by rememberMinuteClock()
        Row(Modifier.padding(horizontal = PhoneGutter), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            cards.forEach { c ->
                PhoneChannelCard(
                    c,
                    loader,
                    now,
                    onLongClick = { onOptions(c) },
                    modifier = Modifier.weight(1f),
                    favourite = c.streamId in favourites,
                    detail = detail(c)
                ) { onPlay(c) }
            }
            repeat(columns - cards.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun EmptyFavourites(onBrowse: () -> Unit) {
    Column(Modifier.padding(horizontal = PhoneGutter), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.home_no_favourites), color = Palette.textDim, fontSize = 14.sp)
        PhonePill(stringResource(R.string.home_browse_channels), primary = true, onClick = onBrowse)
    }
}

/** A poster's width in rows on a phone. */
internal val PHONE_POSTER_WIDTH = 116.dp
