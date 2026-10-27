package com.biskyt.mytvplayer.ui.common

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowRgb565
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.appString
import com.biskyt.mytvplayer.data.db.ChannelEntity
import com.biskyt.mytvplayer.data.db.VodCard
import com.biskyt.mytvplayer.ui.theme.LocalAccent
import com.biskyt.mytvplayer.ui.theme.Palette
import kotlinx.coroutines.flow.StateFlow

/** The ring around what has focus (the guide draws it around its highlight, too). */
val FocusStroke = BorderStroke(3.dp, Palette.text)
private val FocusRing = Border(FocusStroke)

/** A card's shape. */
val CardShape = RoundedCornerShape(14.dp)

/**
 * The app's one clickable surface: neutral background, white ring when focused, optional selected state in accent.
 * No focus scale animation by default — re-laying out rows on every focus change is costly on slow boxes.
 * [onLongClick] (holding OK) is also opened by the remote's Info/Options/Menu button. On a touch screen: tap and long press.
 */
@Composable
fun TvCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean = false,
    shape: Shape = CardShape,
    color: Color = Palette.surface,
    focusedColor: Color = Palette.surfaceHigh,
    content: @Composable () -> Unit
) {
    val accent = LocalAccent.current
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = (if (onLongClick != null) modifier.onOptionsKey(onLongClick) else modifier)
            .onFocusChanged { focused = it.hasFocus }
            .onTap(onLongClick, onClick),
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) accent.tint else color,
            focusedContainerColor = if (selected) accent.tint else focusedColor,
            pressedContainerColor = focusedColor,
            contentColor = Palette.text,
            focusedContentColor = Palette.text
        ),
        border = ClickableSurfaceDefaults.border(focusedBorder = FocusRing)
    ) { CompositionLocalProvider(LocalCardFocused provides focused) { content() } }
}

/** Whether the [TvCard] around this content has focus (e.g. for [OneLine]); only readers redraw when it changes. */
val LocalCardFocused = compositionLocalOf { false }

/** Pill-shaped button. [primary] fills it with the accent colour. */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    selected: Boolean = false,
    leading: (@Composable () -> Unit)? = null
) {
    val accent = LocalAccent.current
    val bg = when {
        primary -> accent.color
        selected -> Palette.text
        else -> Palette.surfaceHigh
    }
    val fg = when {
        primary -> accent.onAccent
        selected -> Palette.background
        else -> Palette.text
    }
    Surface(
        onClick = onClick,
        modifier = modifier.onTap(onClick = onClick),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = bg,
            focusedContainerColor = bg,
            contentColor = fg,
            focusedContentColor = fg
        ),
        border = ClickableSurfaceDefaults.border(focusedBorder = FocusRing)
    ) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) {
                leading()
                Box(Modifier.width(8.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
        }
    }
}

/** Thin progress bar drawn in one pass (no nested layouts), with a round [marker] (e.g. while scrubbing) when given. */
@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier, height: Dp = 5.dp, marker: Float? = null) {
    val accent = LocalAccent.current.color
    Box(
        modifier.height(height).fillMaxWidth().drawBehind {
            val r = CornerRadius(size.height / 2)
            drawRoundRect(Palette.track, cornerRadius = r)
            val f = fraction.coerceIn(0f, 1f)
            if (f > 0f) drawRoundRect(accent, size = Size(size.width * f, size.height), cornerRadius = r)
            if (marker != null) {
                val center = Offset(size.width * marker.coerceIn(0f, 1f), size.height / 2)
                drawCircle(accent, radius = 11.dp.toPx(), center = center)
                drawCircle(Color.White, radius = 6.dp.toPx(), center = center)
            }
        }
    )
}

/**
 * Channel logo with an initials placeholder underneath, so rows look complete before (or without) the image.
 * Images are decoded at the displayed size to keep memory low.
 */
@Composable
fun ChannelLogo(url: String?, name: String, width: Dp = 72.dp, height: Dp = 40.dp, favourite: Boolean = false) = FavouriteBadge(favourite) {
    ImageWithInitials(url, name, width, height, RoundedCornerShape(9.dp), ContentScale.Fit, 4.dp, MaterialTheme.typography.labelMedium)
}

/** A favourite's logo or poster: [content] with a heart on its top-right corner. */
@Composable
private fun FavouriteBadge(favourite: Boolean, content: @Composable () -> Unit) = Box {
    // Always in the Box: the image stays put (and loaded) when a favourite is added or removed.
    content()
    if (favourite) {
        Box(
            Modifier.align(Alignment.TopEnd).offset(x = 5.dp, y = (-5).dp).size(20.dp).clip(CircleShape).background(Palette.background),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                AppIcons.Heart,
                stringResource(R.string.common_favourite_mark),
                tint = LocalAccent.current.color,
                modifier = Modifier.size(13.dp)
            )
        }
    }
}

/** A [com.biskyt.mytvplayer.data.Favourites] set while it's shown: empty until read. */
@Composable
fun StateFlow<Set<Long>?>.collectIds(): Set<Long> = collectAsState().value.orEmpty()

/** A movie or series poster (2:3). Posters are opaque, so they're decoded at half the memory of logos. */
@Composable
fun Poster(url: String?, title: String, width: Dp, modifier: Modifier = Modifier) = ImageWithInitials(
    url, title, width, width * 3 / 2, RoundedCornerShape(10.dp), ContentScale.Crop, 0.dp, MaterialTheme.typography.titleMedium, modifier,
    opaque = true
)

/** Poster width in grids and rows of movies and series. */
val POSTER_WIDTH = 124.dp

/** A movie or series poster with its title and year, as in grids and search. */
@Composable
fun PosterCard(
    card: VodCard,
    modifier: Modifier = Modifier,
    favourite: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    TvCard(onClick = onClick, onLongClick = onLongClick, color = Palette.background, modifier = modifier) {
        Column(Modifier.padding(6.dp).width(POSTER_WIDTH), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FavouriteBadge(favourite) { Poster(card.imageUrl, card.title, POSTER_WIDTH) }
            OneLine(card.title, MaterialTheme.typography.labelLarge)
            Muted(card.year?.toString() ?: " ")
        }
    }
}

/**
 * One line of text: cut short with "…" normally, scrolling a few times to show the rest while its [TvCard] has focus
 * (only the focused item animates, and not for ever).
 */
@Composable
fun OneLine(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    focused: Boolean = LocalCardFocused.current
) {
    if (focused) {
        Text(
            text,
            style = style,
            color = color,
            maxLines = 1,
            softWrap = false,
            modifier = modifier.basicMarquee()
        )
    } else {
        Text(text, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
    }
}

/** A row in an options panel ([SidePanel]): a label and, under it, what it does. */
@Composable
fun MenuOption(label: String, help: String? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    TvCard(onClick = onClick, modifier = modifier.fillMaxWidth(), color = Palette.surface) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            if (help != null) Muted(help)
        }
    }
}

/** An image downsampled to its size on screen, with the name's initials until (or unless) it loads. */
@Composable
private fun ImageWithInitials(
    url: String?,
    name: String,
    width: Dp,
    height: Dp,
    shape: Shape,
    contentScale: ContentScale,
    padding: Dp,
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
    opaque: Boolean = false
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val request = remember(url) {
        url?.let {
            with(density) {
                ImageRequest.Builder(context).data(it).size(width.roundToPx(), height.roundToPx()).apply {
                    if (opaque) allowRgb565(true)
                }.build()
            }
        }
    }
    var loaded by remember(url) { mutableStateOf(false) }
    Box(modifier.size(width, height).clip(shape).background(Palette.surfaceHigh), contentAlignment = Alignment.Center) {
        if (!loaded) Text(remember(name) { initials(name) }, style = textStyle, color = Palette.textDim, maxLines = 1)
        if (request != null) {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = contentScale,
                onSuccess = { loaded = true },
                modifier = Modifier.size(width, height).padding(padding)
            )
        }
    }
}

private val countryPrefix = Regex("^\\W*[A-Za-z]{2,3}\\s*[|:\\-]\\s*")

fun initials(name: String): String {
    val words = name.replace(countryPrefix, "").split(' ', '-', '_').filter { it.isNotBlank() }
    return when {
        words.isEmpty() -> appString(R.string.common_logo_fallback)
        words.size == 1 -> words[0].take(3).uppercase()
        else -> words.take(2).joinToString("") { it.take(1) }.uppercase()
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.headlineSmall, modifier = modifier.padding(bottom = 12.dp))
}

/** Whether text is drawn over the video, where a faint shadow keeps small text clear of a bright picture. */
val LocalOverVideo = staticCompositionLocalOf { false }

private val videoTextShadow = Shadow(Color.Black.copy(alpha = 0.8f), Offset(0f, 1.5f), blurRadius = 4f)

@Composable
fun Muted(text: String, modifier: Modifier = Modifier, maxLines: Int = 1) {
    val plain = MaterialTheme.typography.bodyMedium
    val style = if (LocalOverVideo.current) remember(plain) { plain.copy(shadow = videoTextShadow) } else plain
    Text(
        text,
        style = style,
        color = Palette.textMuted,
        modifier = modifier,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis
    )
}

/** On/off switch drawn without animation. */
@Composable
fun Toggle(on: Boolean) {
    val accent = LocalAccent.current.color
    Box(Modifier.size(52.dp, 30.dp).clip(RoundedCornerShape(15.dp)).background(if (on) accent else Color(0xFF3A404C))) {
        Box(
            Modifier.padding(
                3.dp
            ).size(24.dp).align(if (on) Alignment.CenterEnd else Alignment.CenterStart).clip(CircleShape).background(Palette.text)
        )
    }
}

/** A settings row: label + help text on the left, [trailing] control on the right. */
@Composable
fun SettingRow(
    label: String,
    help: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    TvCard(onClick = onClick, modifier = modifier.fillMaxWidth(), color = Palette.surfaceLow) {
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleSmall)
                if (help != null) Muted(help, maxLines = 2)
            }
            trailing()
        }
    }
}

/**
 * A setting shown as "◀ [value] ▶": left and right step it back and forward ([onStep] gets -1 or 1), once a press
 * (held, the language would switch over and over); OK, or a tap, moves it on.
 */
@Composable
fun CycleSettingRow(label: String, help: String?, value: String, onStep: (Int) -> Unit, modifier: Modifier = Modifier) {
    val keys = Modifier.onPreviewKeyEvent { e ->
        val step = when (e.key) {
            Key.DirectionLeft -> -1
            Key.DirectionRight -> 1
            else -> return@onPreviewKeyEvent false
        }
        if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) onStep(step)
        true
    }
    SettingRow(label, help, { onStep(1) }, modifier.then(keys)) { ValueChip(value) }
}

/** "◀ value ▶" chip for values changed with left/right (or OK to cycle). */
@Composable
fun ValueChip(value: String) {
    Text(
        "◀ $value ▶",
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Palette.surfaceHigh).padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

/** Marks a channel with catch-up (nothing for others): small and dim, there when looked for without crowding. */
@Composable
fun CatchupMark(ch: ChannelEntity, modifier: Modifier = Modifier) {
    if (ch.archiveDays <= 0) return
    Icon(AppIcons.Catchup, stringResource(R.string.common_catchup), tint = Palette.textDim, modifier = modifier.size(16.dp))
}

/**
 * A titled row of cards (sideways), as on Home and in search; left out when there are none. With [move], a favourite
 * being moved along it is kept in view, with how to move it under the title.
 */
@Suppress("LongParameterList") // a composable row: detekt exempts only @Composable functions
fun <T> LazyListScope.titledRow(
    @StringRes title: Int,
    items: List<T>,
    key: (T) -> Long,
    topPadding: Dp = 0.dp,
    move: MovableRow? = null,
    card: @Composable (T) -> Unit
) {
    if (items.isEmpty()) return
    item(key = title) {
        Column(Modifier.padding(top = topPadding)) {
            SectionTitle(stringResource(title))
            val state = rememberLazyListState()
            if (move != null) {
                MoveHint(move.mover, move.type, MoveLayout.ROW)
                KeepInView(state) { move.mover.movingId(move.type)?.let { p -> items.indexOfFirst { key(it) == p } } }
            }
            LazyRow(state = state, horizontalArrangement = Arrangement.spacedBy(14.dp)) { items(items, key = key) { card(it) } }
        }
    }
}
