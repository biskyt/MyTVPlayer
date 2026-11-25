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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.data.db.CategoryEntity
import com.biskyt.mytvplayer.data.db.ChannelEntity
import com.biskyt.mytvplayer.ui.common.Ambience
import com.biskyt.mytvplayer.ui.common.AmbientSource
import com.biskyt.mytvplayer.ui.common.CatchupMark
import com.biskyt.mytvplayer.ui.common.CategoryEntry
import com.biskyt.mytvplayer.ui.common.CategoryOptionsHost
import com.biskyt.mytvplayer.ui.common.ChannelLogo
import com.biskyt.mytvplayer.ui.common.LocalAmbience
import com.biskyt.mytvplayer.ui.common.NowLine
import com.biskyt.mytvplayer.ui.common.NowNextLoader
import com.biskyt.mytvplayer.ui.common.ProgressLine
import com.biskyt.mytvplayer.ui.common.RestrictedMark
import com.biskyt.mytvplayer.ui.common.ambientSource
import com.biskyt.mytvplayer.ui.common.formatCount
import com.biskyt.mytvplayer.ui.common.nowLine
import com.biskyt.mytvplayer.ui.common.numberLabel
import com.biskyt.mytvplayer.ui.theme.LocalAccent
import com.biskyt.mytvplayer.ui.theme.Palette

// The parts that the touch layouts share (the owner, 2026-11-25): one of each, so that the screens look and behave
// the same. Plain Compose foundation: no Material library is added.

/** The space at each side of a phone page. */
internal val PhoneGutter = 20.dp

/** A card's fill (a channel). */
internal val PhoneCard = Color(0xFF181B20)

/** The fill of a chip, a pill and a tile button that is not chosen. */
internal val PhoneChip = Color(0xFF22262D)

/** The smallest touch target. */
internal val PhoneTouch = 44.dp

/** A chip's height: a little more than the smallest touch target, as chips sit close together. */
private val CHIP_HEIGHT = 48.dp

/**
 * A rounded button with [label], at least [PhoneTouch] high: the accent's colour when [primary], light when [light] (the
 * one that stands out over a video, Next episode), else [PhoneChip]. [icon] goes after the label.
 */
@Composable
internal fun PhonePill(
    label: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    light: Boolean = false,
    icon: ImageVector? = null,
    onClick: () -> Unit
) {
    val accent = LocalAccent.current
    val (fill, text) = when {
        primary -> accent.color to accent.onAccent
        light -> Palette.text to Palette.background
        else -> PhoneChip to Palette.text
    }
    Row(
        modifier.heightIn(min = PhoneTouch).clip(RoundedCornerShape(50)).background(fill)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(label, color = text, fontSize = 14.sp, fontWeight = if (light) FontWeight.Black else FontWeight.ExtraBold, maxLines = 1)
        icon?.let { Image(it, null, Modifier.size(16.dp), colorFilter = ColorFilter.tint(text)) }
    }
}

/**
 * A round button with [icon], read out as [label]: [size] across (at least [PhoneTouch]), filled with [fill], the icon
 * [iconSize] in [tint], with a small [number] under it if given (the skip's seconds).
 */
@Composable
internal fun PhoneIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = PhoneTouch,
    fill: Color = Palette.surfaceHigh,
    tint: Color = Palette.text,
    iconSize: Dp = 20.dp,
    number: String? = null
) {
    Column(
        modifier.size(size.coerceAtLeast(PhoneTouch)).clip(CircleShape).background(fill)
            .clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // (No description of its own: the button is read as its label.)
        Image(icon, null, Modifier.size(iconSize), colorFilter = ColorFilter.tint(tint))
        number?.let { Text(it, color = tint, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold) }
    }
}

/** A row of chips that scrolls sideways, with the chosen one ([selected]) kept in view, and the one before it showing. */
@Composable
internal fun <T> PhoneChipRow(
    items: List<T>,
    selected: (T) -> Boolean,
    key: (T) -> Any,
    modifier: Modifier = Modifier,
    chip: @Composable (item: T, chosen: Boolean) -> Unit
) {
    val state = rememberLazyListState()
    val index = items.indexOfFirst(selected)
    // On opening, the chosen chip is placed at once; a chip chosen later slides into view.
    val opened = remember { booleanArrayOf(false) }
    LaunchedEffect(index) {
        if (index >= 0 && !state.showsWhole(index)) {
            val target = (index - 1).coerceAtLeast(0)
            if (opened[0]) state.animateScrollToItem(target) else state.scrollToItem(target)
        }
        opened[0] = true
    }
    LazyRow(
        modifier,
        state = state,
        contentPadding = PaddingValues(horizontal = PhoneGutter, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(items, key = key) { chip(it, selected(it)) }
    }
}

private fun LazyListState.showsWhole(index: Int): Boolean {
    val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return false
    return item.offset >= layoutInfo.viewportStartOffset && item.offset + item.size <= layoutInfo.viewportEndOffset
}

/**
 * A chip, one of a [PhoneChipRow]: [label], with its [count] and a lock when [restricted]; in the accent colour when
 * [chosen]. A long press calls [onLongClick], when given (a category's options).
 */
@Composable
internal fun PhoneChoiceChip(
    label: String,
    chosen: Boolean,
    count: Int? = null,
    restricted: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val accent = LocalAccent.current
    val colour = if (chosen) accent.onAccent else Palette.text
    Row(
        Modifier.heightIn(min = CHIP_HEIGHT).clip(RoundedCornerShape(50)).background(if (chosen) accent.color else PhoneChip)
            .semantics { selected = chosen }
            .combinedClickable(role = Role.Tab, onLongClick = onLongClick, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(label, color = colour, fontSize = 14.sp, fontWeight = if (chosen) FontWeight.ExtraBold else FontWeight.Bold, maxLines = 1)
        if (restricted) RestrictedMark()
        count?.let { Text(formatCount(it), color = colour.copy(alpha = 0.7f), fontSize = 12.sp, maxLines = 1) }
    }
}

/**
 * The lists to choose from ([entries]) as chips, the one shown ([selected]) in the accent colour. A long press on a
 * provider category offers to hide it ([onHide]) and to restrict it, as holding OK does in a TV's column. Without
 * [onHide] (the Catch-up tab), there are no options.
 */
@Composable
internal fun <F> PhoneCategoryChips(
    entries: List<CategoryEntry<F>>,
    selected: F,
    onSelect: (F) -> Unit,
    onHide: ((CategoryEntity) -> Unit)?
) {
    var options by remember { mutableStateOf<CategoryEntity?>(null) }
    PhoneChipRow(entries, { it.filter == selected }, { it.label + it.filter.hashCode() }) { entry, chosen ->
        val category = entry.category
        PhoneChoiceChip(
            entry.label,
            chosen,
            entry.count,
            restricted = category?.restricted == true,
            onLongClick = if (onHide != null && category != null) ({ options = category }) else null
        ) { onSelect(entry.filter) }
    }
    if (onHide != null) CategoryOptionsHost(options, entries, { options = null }, onSelect, onHide, selected)
}

/** A card at touch size: a tap sets the background to [source] and calls [onClick]; a long press likewise [onLongClick]. */
internal fun Modifier.touchCard(ambience: Ambience?, source: AmbientSource, onLongClick: (() -> Unit)?, onClick: () -> Unit): Modifier =
    combinedClickable(
        role = Role.Button,
        onLongClick = onLongClick?.let { long ->
            {
                ambience?.source = source
                long()
            }
        }
    ) {
        ambience?.source = source
        onClick()
    }

/**
 * A channel at touch size, on Home and in the channel lists: its number (when [number]), its logo (with the favourite's
 * heart), its name, and what is on now, until when, with how far through it is ([nowMs]: the minute clock). [loader]
 * fetches what is on once the card is shown. A tap calls [onClick], a long press [onLongClick] (its options).
 */
@Composable
internal fun PhoneChannelCard(
    ch: ChannelEntity,
    loader: NowNextLoader,
    nowMs: Long,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    number: Boolean = false,
    favourite: Boolean = false,
    onClick: () -> Unit
) {
    LaunchedEffect(ch.streamId) { loader.request(ch.streamId) }
    val nn = loader.entries[ch.streamId]
    val ambience = LocalAmbience.current
    val source = AmbientSource.logo(ch.logoUrl)
    Row(
        modifier.heightIn(min = PHONE_CHANNEL_HEIGHT).ambientSource(ambience, source).clip(RoundedCornerShape(14.dp))
            .background(PhoneCard).touchCard(ambience, source, onLongClick, onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (number) {
            Text(
                ch.numberLabel,
                color = Palette.textDim,
                fontSize = 13.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier.width(32.dp)
            )
        }
        ChannelLogo(ch.logoUrl, ch.name, width = 64.dp, height = 40.dp, favourite = favourite)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    ch.name,
                    color = Palette.text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                CatchupMark(ch, Modifier.padding(start = 6.dp))
            }
            if (nn == null) {
                Box(Modifier.fillMaxWidth(0.6f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(Palette.surfaceHigh))
            } else {
                Text(
                    nowLine(nn, NowLine.TITLE_UNTIL),
                    color = Palette.textDim,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (nn.now != null) ProgressLine(nn.progress(nowMs), Modifier.padding(top = 2.dp), height = 3.dp)
            }
        }
    }
}

/** A channel card's height, and that of its stand-in while it loads. */
internal val PHONE_CHANNEL_HEIGHT = 72.dp
