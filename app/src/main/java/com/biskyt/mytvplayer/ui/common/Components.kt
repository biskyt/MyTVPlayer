package com.biskyt.mytvplayer.ui.common

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
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
import coil3.BitmapImage
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowRgb565
import coil3.request.transformations
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.appString
import com.biskyt.mytvplayer.data.db.ChannelEntity
import com.biskyt.mytvplayer.data.db.VodCard
import com.biskyt.mytvplayer.ui.theme.LocalAccent
import com.biskyt.mytvplayer.ui.theme.LocalOnAccentTypography
import com.biskyt.mytvplayer.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The ring around what has focus. */

/** The ring inside a filled card that is in use (see TvCard's fill). A Border's inset is outwards: in, it is less than 0. */
private val SelectedRing = 2.dp
private val SelectedRingInset = (-3).dp

/** A card's shape. */
val CardShape = RoundedCornerShape(14.dp)

/**
 * The app's one clickable surface: neutral background, a glow in the accent colour when focused (never a white ring,
 * the owner 2026-11-22), optional selected state in accent.
 * With [fill] (settings rows and menus, the owner 2026-11-06), it is filled with the accent colour when focused, with
 * no ring, and its text takes the accent's text colour (see [LocalCardFilled]). Only a filled card that is [selected]
 * (the track, playlist or guide in use) has a ring then, inside its fill, so that it still shows which one is in use.
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
    /** Without it, what holds the card shows its focus (the series page's episode rows). With it, it glows. */
    ring: Boolean = true,
    fill: Boolean = false,
    /** Its background while [selected] and without focus. */
    selectedColor: Color = LocalAccent.current.tint,
    /** Its text's colour without focus (a panel's Delete is red). */
    contentColor: Color = Palette.text,
    /**
     * With focus, it grows and glows in the accent colour, in place of the white ring (see focusGlow; the owner,
     * 2026-11-15): for cards in rows and grids, whose items are raised (raiseOnFocus) and have room around them.
     */
    glow: Boolean = false,
    content: @Composable BoxScope.() -> Unit
) {
    val accent = LocalAccent.current
    var focused by remember { mutableStateOf(false) }
    val focusFill = if (fill) {
        accent.color
    } else if (selected) {
        accent.tint
    } else {
        focusedColor
    }
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        // Back on its screen, the card used last has focus again.
        modifier = (if (onLongClick != null) modifier.onOptionsKey(onLongClick) else modifier)
            .then(
                when {
                    glow -> Modifier.focusGlow(shape, accent.color)
                    // At its own size: rows and buttons would grow over what is beside them.
                    ring && !fill -> Modifier.focusGlow(shape, accent.color, grow = false)
                    else -> Modifier
                }
            )
            .returnsFocus()
            .onFocusChanged { focused = it.hasFocus }
            .onTap(onLongClick, onClick),
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) selectedColor else color,
            focusedContainerColor = focusFill,
            pressedContainerColor = if (fill) accent.color else focusedColor,
            contentColor = contentColor,
            focusedContentColor = if (fill) accent.onAccent else contentColor
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = when {
                // In use and filled: a ring in the fill's text colour, so it still shows which one is in use.
                fill && selected -> Border(BorderStroke(SelectedRing, accent.onAccent), SelectedRingInset)
                else -> Border.None
            }
        )
    ) {
        val filled = fill && focused
        CompositionLocalProvider(LocalCardFocused provides focused, LocalCardFilled provides filled) {
            // Branched on fill (fixed for a card), not on focus: the same composition either way, so a focus move does
            // not dispose and recreate the content (its effects, such as a focus restore, would run again).
            val box = this
            if (fill) FilledCardText(filled) { box.content() } else content()
        }
    }
}

/**
 * The theme's text styles (they set the text colour themselves), in the accent's text colour while [filled], for text
 * on the accent fill. The theme makes those styles once (see [LocalOnAccentTypography]), not each card.
 */
@Composable
private fun FilledCardText(filled: Boolean, content: @Composable () -> Unit) {
    // Always through MaterialTheme, filled or not: the same composition either way, so a focus move does not dispose
    // and recreate the content.
    val t = MaterialTheme.typography
    MaterialTheme(MaterialTheme.colorScheme, MaterialTheme.shapes, if (filled) LocalOnAccentTypography.current else t, content)
}

/** Whether the [TvCard] around this content is filled with the accent colour now (focused, with fill). */
val LocalCardFilled = compositionLocalOf { false }

/**
 * A colour of its own for text or a mark in a card: [normal], or on the card's fill the accent's text colour (at
 * [onAccentAlpha], for something fainter).
 */
@Composable
fun cardColor(normal: Color, onAccentAlpha: Float = 1f): Color =
    if (LocalCardFilled.current) LocalAccent.current.onAccent.copy(alpha = onAccentAlpha) else normal

/** Whether the [TvCard] around this content has focus (e.g. for [OneLine]); only readers redraw when it changes. */
val LocalCardFocused = compositionLocalOf { false }

/**
 * Pill-shaped button. [primary] fills it with the accent colour; [selected] (a choice in use) with the text colour.
 * With focus, every button is filled with the accent colour, with no white ring (the owner, 2026-11-11 for the player,
 * 2026-11-13 for every button), and its text takes the accent's text colour. A primary or selected button has a thin
 * ring inside the fill then, so that focus still shows on it.
 */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    selected: Boolean = false,
    /** Smaller, for a choice beside a label. */
    compact: Boolean = false,
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
    // Filled with the accent colour while it has focus, as every button is: a primary or selected one has the in-use ring.
    TvCard(
        onClick = onClick,
        modifier = modifier,
        selected = primary || selected,
        shape = RoundedCornerShape(50),
        color = bg,
        selectedColor = bg,
        fill = true,
        contentColor = fg
    ) {
        val padding = if (compact) {
            Modifier.padding(
                horizontal = 12.dp,
                vertical = 4.dp
            )
        } else {
            Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
        }
        Row(padding, verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) {
                leading()
                Box(Modifier.width(8.dp))
            }
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                color = cardColor(fg),
                maxLines = 1
            )
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
    val style = MaterialTheme.typography.labelMedium
    ImageWithInitials(url, name, width, height, LogoShape, ContentScale.Fit, 4.dp, style, logo = true)
}

/**
 * A favourite's logo or poster: [content] with a heart on its top-right corner, just outside it, or just inside it when
 * [inside] (a poster that is its card: the card's shape would cut it off).
 */
@Composable
private fun FavouriteBadge(favourite: Boolean, inside: Boolean = false, content: @Composable () -> Unit) = Box {
    // Always in the Box: the image stays put (and loaded) when a favourite is added or removed.
    content()
    if (favourite) {
        val place = if (inside) Modifier.offset(x = (-6).dp, y = 6.dp) else Modifier.offset(x = 5.dp, y = (-5).dp)
        Box(
            Modifier.align(Alignment.TopEnd).then(place).size(20.dp).clip(CircleShape).background(Palette.background),
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
fun Poster(url: String?, title: String, width: Dp, modifier: Modifier = Modifier, radius: Dp = POSTER_RADIUS) = ImageWithInitials(
    url, title, width, width * 3 / 2, RoundedCornerShape(radius), ContentScale.Crop, 0.dp, MaterialTheme.typography.titleMedium, modifier,
    opaque = true
)

/** A person's photo, [size] square in [shape] (a circle unless given), with their initials until (or unless) it loads. */
@Composable
fun CirclePhoto(url: String?, name: String, size: Dp, shape: Shape = CircleShape) = ImageWithInitials(
    url, name, size, size, shape, ContentScale.Crop, 0.dp, MaterialTheme.typography.labelLarge, opaque = true
)

/**
 * Why something could not load ([message]), then Retry ([onRetry]), which has the remote. Retry goes while it loads
 * again, so the remote first goes to [focusAfter], or else up the page: not nowhere.
 */
@Composable
fun ErrorRetry(message: String, focusAfter: FocusRequester? = null, onRetry: () -> Unit) {
    val focus = LocalFocusManager.current
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Muted(message, Modifier.weight(1f, fill = false), maxLines = 3)
        PillButton(stringResource(R.string.common_retry), {
            val moved = focusAfter?.tryFocus() ?: false
            if (!moved) focus.moveFocus(FocusDirection.Up)
            onRetry()
        }, rememberFirstFocus())
    }
}

/** Poster width in grids and rows of movies and series. */
val POSTER_WIDTH = 124.dp

private val LogoShape = RoundedCornerShape(9.dp)

/** A poster's corners (see [Poster]), and so a poster card's, so that its glow follows the poster. */
private val POSTER_RADIUS = 10.dp
private val PosterShape = RoundedCornerShape(POSTER_RADIUS)

/**
 * A poster as a card, with its title and details under it (the owner, 2026-11-15, as in MyTVLauncher): only the poster
 * grows and glows when it has focus (see focusGlow), [GLOW_GAP] clear of the text, which stays as it is. [modifier] is the
 * card's (focus, keys); [below] gets whether it has focus, for text that scrolls then (see [OneLine]). [texts], the text
 * under it, is the card's own for accessibility (a screen reader reads it with the card, and tests find the card by it).
 */
@Composable
fun PosterTile(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier,
    texts: List<String>,
    /** The poster's width (MyPlexPlayer's person page has larger posters). */
    width: Dp = POSTER_WIDTH,
    poster: @Composable () -> Unit,
    below: @Composable (focused: Boolean) -> Unit
) {
    // Set as focus moves, read only by the text under it (see Below): a focus move does not compose the tile again.
    val focused = remember { mutableStateOf(false) }
    // With focus, the whole tile comes into view, its text too (a grid's bottom row would otherwise keep it cut off).
    val tile = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    Column(Modifier.width(width).bringIntoViewRequester(tile)) {
        TvCard(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier.onFocusChanged {
                focused.value = it.hasFocus
                if (it.hasFocus) scope.launch { tile.bringIntoView() }
            }.semantics { this[SemanticsProperties.Text] = texts.filter { it.isNotBlank() }.map(::AnnotatedString) },
            shape = PosterShape,
            color = Palette.background,
            glow = true
        ) { poster() }
        // Clear of the poster as it grows: half its growth (see focusGlow), and a little more, GLOW_GAP at least.
        Spacer(Modifier.height(maxOf(GLOW_GAP, width * POSTER_GROWTH + 3.dp)))
        Below(focused, below)
    }
}

/** The text under a [PosterTile] (read with the card: see its texts), given whether the card has focus. */
@Composable
private fun Below(focused: State<Boolean>, below: @Composable (focused: Boolean) -> Unit) =
    Column(Modifier.clearAndSetSemantics {}, verticalArrangement = Arrangement.spacedBy(4.dp)) { below(focused.value) }

/** How far a poster grows downwards with focus, for its width: half of its growth ([LIFT_SCALE]) of its height (1.5 times its width). */
private const val POSTER_GROWTH = LIFT_SCALE / 2 * 1.5f

/** A movie or series poster with its title and year, as in grids and search. */
@Composable
fun PosterCard(
    card: VodCard,
    modifier: Modifier = Modifier,
    favourite: Boolean = false,
    /** A line under the year, such as the category of a search result. */
    detail: String? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
) = PosterTile(
    onClick,
    onLongClick,
    modifier,
    listOfNotNull(card.title, card.year?.toString(), detail),
    // (Its initials, shown until the picture loads, are not read: the card's texts are.)
    poster = {
        FavouriteBadge(favourite, inside = true) { Poster(card.imageUrl, card.title, POSTER_WIDTH, Modifier.clearAndSetSemantics {}) }
    }
) { focused ->
    OneLine(card.title, MaterialTheme.typography.labelLarge, focused = focused)
    Muted(card.year?.toString() ?: " ")
    if (detail != null) OneLine(detail, MaterialTheme.typography.bodyMedium, color = Palette.textMuted, focused = focused)
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
    opaque: Boolean = false,
    /** A TV channel's logo: a dark one gets a light tile (see LogoDark). */
    logo: Boolean = false
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    // A channel logo's address: one that may get a light tile (only then is any of that work done).
    val logoUrl = url.takeIf { logo }
    val request = remember(url) {
        url?.let {
            with(density) {
                ImageRequest.Builder(context).data(it).size(width.roundToPx(), height.roundToPx()).apply {
                    if (opaque) allowRgb565(true)
                    if (logoUrl != null) transformations(LogoDark(logoUrl))
                }.build()
            }
        }
    }
    var loaded by remember(url) { mutableStateOf(false) }
    val dark = logoUrl?.let { remember(it) { mutableStateOf(LogoDark.known(it) == true) } }
    val scope = if (logoUrl != null) rememberCoroutineScope() else null
    // Only once it shows: the initials, while it loads or when it fails, keep the dark tile they are made for.
    val tile = if (loaded && dark?.value == true) LIGHT_LOGO_TILE else Palette.surfaceHigh
    Box(modifier.size(width, height).clip(shape).background(tile), contentAlignment = Alignment.Center) {
        if (!loaded) Text(remember(name) { initials(name) }, style = textStyle, color = Palette.textDim, maxLines = 1)
        if (request != null) {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = contentScale,
                onSuccess = { state ->
                    loaded = true
                    if (logoUrl != null && dark != null && scope != null) {
                        val known = LogoDark.known(logoUrl)
                        // From Coil's memory cache after this note was dropped: looked at again, off the main thread.
                        val bitmap = (state.result.image as? BitmapImage)?.bitmap
                        when {
                            known != null -> dark.value = known
                            bitmap != null -> scope.launch {
                                dark.value =
                                    withContext(Dispatchers.Default) { LogoDark.look(logoUrl, bitmap) }
                            }
                        }
                    }
                },
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
        color = cardColor(Palette.textMuted, MUTED_ON_ACCENT),
        modifier = modifier,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis
    )
}

/** On/off switch drawn without animation. */
@Composable
fun Toggle(on: Boolean) {
    // On a card's accent fill: a track in the accent's text colour, and a knob in the accent colour.
    val accent = LocalAccent.current
    val track = if (on) cardColor(accent.color) else cardColor(Color(0xFF3A404C), TRACK_OFF_ON_ACCENT)
    Box(Modifier.size(52.dp, 30.dp).clip(RoundedCornerShape(15.dp)).background(track)) {
        Box(
            Modifier.padding(
                3.dp
            ).size(24.dp).align(if (on) Alignment.CenterEnd else Alignment.CenterStart).clip(CircleShape)
                .background(if (on && LocalCardFilled.current) accent.color else Palette.text)
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
    TvCard(onClick = onClick, modifier = modifier.fillMaxWidth(), color = Palette.surfaceLow, fill = true) {
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
        modifier = Modifier.clip(RoundedCornerShape(8.dp))
            .background(cardColor(Palette.surfaceHigh, CHIP_ON_ACCENT))
            .padding(horizontal = 12.dp, vertical = 6.dp)
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
    /** Left and Right stop at the row's ends (see sidewaysOnly), as on Home. */
    stopAtEnds: Boolean = false,
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
            // Room for the card with focus to grow and glow (see glowRoom), drawn over the cards beside it.
            LazyRow(
                Modifier.glowRoom().then(if (stopAtEnds) Modifier.sidewaysOnly() else Modifier),
                state = state,
                contentPadding = glowRoomPadding(),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) { items(items, key = key) { Box(Modifier.raiseOnFocus()) { card(it) } } }
        }
    }
}

/** On a card's accent fill: help text a little lighter than the label, an off switch's track and a value chip faint. */
private const val MUTED_ON_ACCENT = 0.78f
private const val TRACK_OFF_ON_ACCENT = 0.25f
private const val CHIP_ON_ACCENT = 0.14f
