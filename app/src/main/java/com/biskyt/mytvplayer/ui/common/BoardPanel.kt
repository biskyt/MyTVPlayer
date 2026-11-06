package com.biskyt.mytvplayer.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.em
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.ui.theme.BOLD
import com.biskyt.mytvplayer.ui.theme.BoardStyle
import com.biskyt.mytvplayer.ui.theme.EXTRA_BOLD
import com.biskyt.mytvplayer.ui.theme.LocalAccent
import com.biskyt.mytvplayer.ui.theme.MEDIUM
import com.biskyt.mytvplayer.ui.theme.Palette
import com.biskyt.mytvplayer.ui.theme.SEMI_BOLD
import com.biskyt.mytvplayer.ui.theme.bd

// Every side panel of both apps (the owner, 2026-11-06), as MyPlexPlayer's film page boards draw them ("PlexFilmMore",
// "PlexFilmTech"). A panel is a BoardPanel of these parts, so that its style is set here only. Sizes are the boards'
// (see Board.kt).

/**
 * A side panel as the boards draw it: [width] wide (400 on most boards), with the boards' padding, and [content] in a
 * column [gap] apart that scrolls when it is taller than the screen. With [scroll] false, the column does not scroll,
 * for content that is a lazy list of its own. [dim] dims the screen behind it (not the video in the player). Keys
 * and first focus go on the content: it is in the panel's own window.
 */
@Composable
fun BoardPanel(
    onDismiss: () -> Unit,
    width: Dp = 400.bd,
    padding: PaddingValues = PaddingValues(horizontal = 26.bd, vertical = 24.bd),
    gap: Dp = BoardPanelGap,
    dim: Boolean = true,
    scroll: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) = SidePanel(onDismiss, width = width, dim = dim, padding = padding) {
    Column(
        if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier,
        verticalArrangement = Arrangement.spacedBy(gap),
        content = content
    )
}

/** A panel's title: 20, extra bold (18 for a [small] one, such as an episode's, which is longer). */
@Composable
fun BoardPanelTitle(text: String, modifier: Modifier = Modifier, small: Boolean = false) = Text(
    text,
    style = if (small) PanelTitleSmall.current else PanelTitle.current,
    maxLines = 2,
    overflow = TextOverflow.Ellipsis,
    modifier = modifier
)

/** The line under a panel's title ("2021 · Drama, Mystery", the track in use): 13, muted. */
@Composable
fun BoardPanelSubtitle(text: String, modifier: Modifier = Modifier) =
    Text(text, style = PanelSubtitle.current, color = Palette.textDim, modifier = modifier)

/** A panel's running text (a plot, a programme's description): 14, at [maxLines] at most. */
@Composable
fun BoardPanelText(text: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE, color: Color = Palette.text) =
    Text(text, style = PanelText.current, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis, modifier = modifier)

/**
 * A board's focusable surface: transparent, and filled with the accent colour while it has focus or is pressed, with
 * no ring and no zoom (a [TvCard] with fill). Its content colour is the text's, or [content] (Delete's red), and the
 * accent's own while filled; text in a colour of its own takes it from [cardColor]. [selected] (the track in use) is
 * tinted with the accent, and keeps a ring inside the fill while it has focus, so that it still shows. [onLongClick] is
 * holding OK, the remote's Menu button, or a long press.
 */
@Composable
fun AccentFillSurface(
    onClick: () -> Unit,
    shape: Shape,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    content: Color = Palette.text,
    selected: Boolean = false,
    body: @Composable BoxScope.() -> Unit
) = TvCard(
    onClick = onClick,
    modifier = modifier,
    onLongClick = onLongClick,
    selected = selected,
    shape = shape,
    color = Color.Transparent,
    fill = true,
    contentColor = content,
    content = body
)

/**
 * A panel's option: a rounded row that fills with the accent colour while it has focus. [help] is a smaller line under
 * it; [danger] (Delete) is in red; [selected] is the choice in use (see [AccentFillSurface]).
 */
@Composable
fun BoardOption(
    label: String,
    help: String? = null,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    selected: Boolean = false,
    onClick: () -> Unit
) {
    AccentFillSurface(
        onClick,
        OptionShape,
        modifier.fillMaxWidth(),
        content = if (danger) Palette.danger else Palette.text,
        selected = selected
    ) {
        Column(Modifier.padding(horizontal = 14.bd, vertical = 8.bd), verticalArrangement = Arrangement.spacedBy(1.bd)) {
            Text(label, style = OptionText.current, color = LocalContentColor.current)
            if (help != null) Text(help, style = HelpText.current, color = cardColor(Palette.textDim, HELP_ON_ACCENT))
        }
    }
}

/** A row of a panel's list with content of its own (a programme): [AccentFillSurface] in an option's shape. */
@Composable
fun BoardRow(onClick: () -> Unit, modifier: Modifier = Modifier, body: @Composable BoxScope.() -> Unit) =
    AccentFillSurface(onClick, OptionShape, modifier.fillMaxWidth(), body = body)

/** A heading over a group of a panel's options ("This episode"): 12, extra bold, upper case, in the accent colour. */
@Composable
fun BoardSection(text: String) = Text(
    text.uppercase(),
    style = SectionText.current,
    color = LocalAccent.current.color,
    modifier = Modifier.padding(start = 14.bd, end = 14.bd, top = 8.bd)
)

// The facts of MyPlexPlayer's Technical info: shared, so that both apps' panels stay one file.

/**
 * A heading over a group of facts ("Video"): 13, extra bold, upper case, in the accent colour, with the boards' 10 above
 * it and 2 below.
 */
@Composable
fun BoardFactsHeading(text: String) = Text(
    text.uppercase(),
    style = FactsHeading.current,
    color = LocalAccent.current.color,
    modifier = Modifier.padding(top = 10.bd, bottom = 2.bd)
)

/**
 * A fact: its [label] in a column [labelWidth] wide, muted, then its [value] in bold, 13 each. It takes the remote, so
 * that Up and Down scroll a long list, and shows it quietly: a dark rounded fill behind it, no ring.
 */
@Composable
fun BoardFact(label: String, value: String, labelWidth: Dp, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier.onFocusChanged { focused = it.isFocused }.focusable()
            .background(if (focused) Palette.surfaceHigh else Color.Transparent, FactShape),
        horizontalArrangement = Arrangement.spacedBy(10.bd)
    ) {
        Text(label, style = FactText.current, color = Palette.textDim, modifier = Modifier.width(labelWidth))
        Text(value, style = FactValue.current)
    }
}

/** The boards' gap between a panel's rows: for a panel's own list too (see [BoardPanel]'s scroll). */
val BoardPanelGap = 5.bd

private val PanelTitle = BoardStyle(20, EXTRA_BOLD)
private val PanelTitleSmall = BoardStyle(18, EXTRA_BOLD)

private val OptionShape = RoundedCornerShape(10.bd)
private val FactShape = RoundedCornerShape(4.bd)

/** An option's help on the accent fill: the accent's text colour, a little faded. */
private const val HELP_ON_ACCENT = 0.78f

/** A panel's options: 15, extra bold; their help 12, semi-bold. */
private val OptionText = BoardStyle(15, EXTRA_BOLD)
private val HelpText = BoardStyle(12, SEMI_BOLD)

/** Under a panel's title ("2021 · Drama, Mystery"): 13, muted. */
private val PanelSubtitle = BoardStyle(13, MEDIUM)
private val PanelText = BoardStyle(14, MEDIUM, 1.4f)
private val SectionText = BoardStyle(12, EXTRA_BOLD, letterSpacing = 0.04f.em)
private val FactsHeading = BoardStyle(13, EXTRA_BOLD, letterSpacing = 0.04f.em)
private val FactText = BoardStyle(13, MEDIUM, 1.35f)
private val FactValue = BoardStyle(13, BOLD, 1.35f)
