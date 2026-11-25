package com.biskyt.mytvplayer.ui.phone

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowRgb565
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.core.vod.CastMember
import com.biskyt.mytvplayer.data.db.MovieEntity
import com.biskyt.mytvplayer.data.db.WatchProgressEntity
import com.biskyt.mytvplayer.ui.LocalNavigator
import com.biskyt.mytvplayer.ui.common.AppIcons
import com.biskyt.mytvplayer.ui.common.CirclePhoto
import com.biskyt.mytvplayer.ui.common.Poster
import com.biskyt.mytvplayer.ui.common.Tab
import com.biskyt.mytvplayer.ui.theme.FormFactor
import com.biskyt.mytvplayer.ui.theme.LocalAccent
import com.biskyt.mytvplayer.ui.theme.LocalFormFactor
import com.biskyt.mytvplayer.ui.theme.Palette
import com.biskyt.mytvplayer.ui.vod.PlotPanel
import com.biskyt.mytvplayer.ui.vod.TitleInfo
import com.biskyt.mytvplayer.ui.vod.TmdbScore
import com.biskyt.mytvplayer.ui.vod.filmFacts
import com.biskyt.mytvplayer.ui.vod.originalTitle
import com.biskyt.mytvplayer.ui.vod.people
import com.biskyt.mytvplayer.ui.vod.resumeLabel

// The film page on a phone or an unfolded foldable (the owner, 2026-11-25), as the mock-up "Film" draws it. The
// parts that the series page uses too are here (see PhoneSeries.kt). Sizes are in plain dp and sp: the device's own.

/**
 * The film page on a phone: the backdrop at the top, which fades into the page, with the poster beside the title and
 * the facts; Play or Resume across the page, then Restart, Trailer and More in a row; the plot, who made it, and the
 * cast in a row that scrolls sideways. The page scrolls. Unfolded, the poster, facts and buttons are on the left, and the
 * plot and cast on the right. The arguments are FilmPage's.
 */
@Composable
@Suppress("LongParameterList") // FilmPage's, passed on as they are
fun PhoneFilmPage(
    movie: MovieEntity,
    info: TitleInfo,
    category: String?,
    resumable: WatchProgressEntity?,
    play: (fromStart: Boolean) -> Unit,
    more: () -> Unit,
    openPerson: (CastMember) -> Unit,
    playTrailer: (() -> Unit)?
) {
    val header = @Composable {
        PhoneDetailHeader(info, movie.posterUrl ?: info.posterUrl, movie.title) {
            originalTitle(movie.title, info.tmdb)?.let {
                PhoneSmallText(stringResource(R.string.vod_original_title_is, it), maxLines = 2)
            }
            val facts = filmFacts(movie, info, category)
            if (facts.isNotEmpty()) PhoneFacts(facts)
            info.score?.let { TmdbScore(it) }
        }
    }
    val buttons = @Composable { FilmButtons(resumable, play, more, playTrailer) }
    val about = @Composable { unfolded: Boolean ->
        info.plot?.let { PhonePlot(movie.title, it, if (unfolded) UNFOLDED_PLOT_LINES else PLOT_LINES) }
        people(info)?.let { PhoneSmallText(it, Modifier.padding(horizontal = PhoneGutter), maxLines = 3) }
        info.castNames?.let {
            PhoneSmallText(stringResource(R.string.vod_cast, it), Modifier.padding(horizontal = PhoneGutter), maxLines = 3)
        }
        if (info.cast.isNotEmpty()) PhoneCastRow(info.cast, openPerson)
    }
    PhoneDetailLayout(
        Tab.MOVIES,
        left = {
            header()
            buttons()
        },
        right = { unfolded -> about(unfolded) }
    )
}

/**
 * A phone page in one column that scrolls, [left] over [right]; unfolded, in two panes side by side, each scrolling on
 * its own. [right] is told which. The tabs are with it, [tab] the current one (see PhoneTabFrame).
 */
@Composable
internal fun PhoneDetailLayout(
    tab: Tab,
    left: @Composable ColumnScope.() -> Unit,
    right: @Composable ColumnScope.(unfolded: Boolean) -> Unit
) = PhoneTabFrame(tab) {
    val page = Modifier.fillMaxSize().background(Palette.background)
    if (LocalFormFactor.current == FormFactor.UNFOLDED) {
        Row(page) {
            Column(
                Modifier.width(UNFOLDED_LEFT).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(PANE_GAP)
            ) { left() }
            Column(
                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(top = 24.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(PANE_GAP)
            ) { right(true) }
        }
    } else {
        Column(page.verticalScroll(rememberScrollState()).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(PANE_GAP)) {
            left()
            right(false)
        }
    }
}

/**
 * The top of a film or series page: a backdrop 230 high, which fades into the page, with a back button on it; then the
 * [poster] over its bottom edge, and beside it the [title] and [lines] (the facts). [info]'s backdrops are tried in turn,
 * the provider's then TMDB's. When none loads, or there is none, the poster comes up under the back button, with no
 * empty space (the owner, 2026-11-25). Until then (one loading, or the page's look-ups not finished), the space stays,
 * so that the page does not move when a backdrop comes.
 */
@Composable
internal fun PhoneDetailHeader(info: TitleInfo, poster: String?, title: String, lines: @Composable ColumnScope.() -> Unit) {
    val nav = LocalNavigator.current
    // The addresses that failed: not tried again when TMDB's answer adds one.
    val failed = remember { mutableStateListOf<String>() }
    val backdrop = listOfNotNull(info.backdropUrl, info.tmdb?.backdropUrl).firstOrNull { it !in failed }
    val height = if (backdrop != null || !info.settled) BACKDROP_HEIGHT else NO_BACKDROP_HEIGHT
    Box(Modifier.fillMaxWidth()) {
        if (backdrop != null) {
            Box(Modifier.fillMaxWidth().height(BACKDROP_HEIGHT).background(Palette.surfaceHigh)) {
                val context = LocalContext.current
                val request = remember(backdrop) {
                    ImageRequest.Builder(context).data(backdrop).size(BACKDROP_PX, BACKDROP_PX * 9 / 16).allowRgb565(true).build()
                }
                AsyncImage(
                    request,
                    null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().testTag("backdrop"),
                    onError = { failed += backdrop }
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(0.4f to Palette.background.copy(alpha = 0f), 1f to Palette.background)
                    )
                )
            }
        }
        PhoneIconButton(
            AppIcons.Back,
            stringResource(R.string.player_back),
            { nav.back() },
            Modifier.padding(14.dp),
            fill = Color.Black.copy(alpha = 0.45f),
            iconSize = 22.dp
        )
        Row(
            Modifier.padding(start = PhoneGutter, end = PhoneGutter, top = height - POSTER_OVER),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            Poster(poster, title, POSTER_WIDTH, radius = 10.dp)
            Column(Modifier.weight(1f).padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    title,
                    color = Palette.text,
                    fontSize = 24.sp,
                    lineHeight = 26.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                lines()
            }
        }
    }
}

/** Play or Resume (with what is left, and its progress along the bottom), then Restart, Trailer and More. */
@Composable
private fun FilmButtons(resumable: WatchProgressEntity?, play: (Boolean) -> Unit, more: () -> Unit, playTrailer: (() -> Unit)?) {
    val resume = resumable?.takeIf { it.durationMs > 0 }
    Column(Modifier.padding(start = PhoneGutter, end = PhoneGutter, top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PhoneMainButton(
            resumeLabel(resume),
            resume?.fraction,
            onLongClick = more
        ) { play(false) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val tile = Modifier.weight(1f)
            if (resume != null) PhoneTileButton(AppIcons.SkipBack, stringResource(R.string.vod_restart), tile) { play(true) }
            playTrailer?.let { PhoneTileButton(AppIcons.Trailer, stringResource(R.string.vod_trailer), tile, onClick = it) }
            PhoneTileButton(AppIcons.More, moreLabel(), tile, onClick = more)
        }
    }
}

/** The page's main button, across it in the accent colour: [progress] (0 to 1) runs along its bottom. */
@Composable
internal fun PhoneMainButton(text: String, progress: Float?, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val accent = LocalAccent.current
    Box(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(14.dp)).background(accent.color)
            .combinedClickable(role = Role.Button, onLongClick = onLongClick, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Image(AppIcons.Play, null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(accent.onAccent))
            Text(
                text,
                color = accent.onAccent,
                fontSize = 17.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (progress != null) {
            Box(
                Modifier.align(
                    Alignment.BottomStart
                ).fillMaxWidth(progress.coerceIn(0f, 1f)).height(4.dp).background(Color.Black.copy(alpha = 0.35f))
            )
        }
    }
}

/** "More", as a tile's label: the TV's "More…" without its dots (a tile opens its panel at once, as the dots say). */
@Composable
internal fun moreLabel() = stringResource(R.string.vod_more).trimEnd('…')

/**
 * A square-ish button with [icon] over its [label], one of a row. [iconDescription] is what the icon says for a screen
 * reader, when it says more than the label (the heart: whether it is a favourite).
 */
@Composable
internal fun PhoneTileButton(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    iconDescription: String? = null,
    onClick: () -> Unit
) {
    Column(
        modifier.heightIn(min = 58.dp).clip(RoundedCornerShape(12.dp)).background(PhoneChip)
            .clickable(role = Role.Button, onClick = onClick).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)
    ) {
        Image(icon, iconDescription, Modifier.size(20.dp), colorFilter = ColorFilter.tint(Palette.text))
        Text(
            label,
            color = Palette.text,
            fontSize = 12.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** The facts line ("15 Sept 2021 · 2 h 35 min · Drama"). */
@Composable
internal fun PhoneFacts(text: String) = Text(
    text,
    color = Palette.textMuted,
    fontSize = 13.sp,
    fontWeight = FontWeight.ExtraBold,
    maxLines = 3,
    overflow = TextOverflow.Ellipsis
)

/** A small muted line (the original title, who made it). */
@Composable
internal fun PhoneSmallText(text: String, modifier: Modifier = Modifier, maxLines: Int = 1) =
    Text(text, color = Palette.textDim, fontSize = 13.sp, maxLines = maxLines, overflow = TextOverflow.Ellipsis, modifier = modifier)

/**
 * The plot, [maxLines] at most. When it is cut short, a tap on it opens the whole of it in a sheet, with [title], as
 * Read more does on a TV.
 */
@Composable
internal fun PhonePlot(title: String, plot: String, maxLines: Int) {
    var cut by remember(plot) { mutableStateOf(false) }
    var open by remember(plot) { mutableStateOf(false) }
    Text(
        plot,
        color = Palette.textMuted,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { cut = it.hasVisualOverflow },
        modifier = Modifier.fillMaxWidth().clickable(enabled = cut, role = Role.Button) { open = true }.padding(horizontal = PhoneGutter)
    )
    if (open) PlotPanel(title, plot) { open = false }
}

/** "Cast", then each one's round photo, name and part in a row that scrolls sideways. A tap opens their page. */
@Composable
private fun PhoneCastRow(cast: List<CastMember>, open: (CastMember) -> Unit) {
    Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            stringResource(R.string.vod_cast_title),
            color = Palette.text,
            fontSize = 16.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(horizontal = PhoneGutter)
        )
        LazyRow(contentPadding = PaddingValues(horizontal = PhoneGutter), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // Keyed by place and name: a name can be in the cast twice.
            itemsIndexed(cast, key = { i, person -> "$i ${person.name}" }) { _, person ->
                Column(
                    Modifier.width(76.dp).clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button) { open(person) }
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    CirclePhoto(person.photoUrl, person.name, 64.dp)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        person.name,
                        color = Palette.text,
                        fontSize = 12.sp,
                        lineHeight = 14.sp,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    person.role?.let {
                        Text(
                            it,
                            color = Palette.textDim,
                            fontSize = 11.sp,
                            lineHeight = 13.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

private val PANE_GAP = 12.dp
private val UNFOLDED_LEFT = 380.dp
private val BACKDROP_HEIGHT = 230.dp
private val POSTER_WIDTH = 104.dp

/** How far the poster comes up over the backdrop. */
private val POSTER_OVER = 70.dp

/** In place of a backdrop: the back button's room at the top (14 + 44 + 14), so the poster's top is just under it. */
private val NO_BACKDROP_HEIGHT = 72.dp + POSTER_OVER
private const val BACKDROP_PX = 720
private const val PLOT_LINES = 4
private const val UNFOLDED_PLOT_LINES = 10
