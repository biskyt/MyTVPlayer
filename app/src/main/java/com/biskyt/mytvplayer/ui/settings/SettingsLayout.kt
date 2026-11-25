package com.biskyt.mytvplayer.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.ui.LocalNavigator
import com.biskyt.mytvplayer.ui.common.Muted
import com.biskyt.mytvplayer.ui.phone.PhoneBackButton
import com.biskyt.mytvplayer.ui.phone.PhoneGutter
import com.biskyt.mytvplayer.ui.theme.FormFactor
import com.biskyt.mytvplayer.ui.theme.LocalFormFactor
import com.biskyt.mytvplayer.ui.theme.Palette
import com.biskyt.mytvplayer.ui.vod.WrapRow

// The settings pages on a phone or an unfolded foldable (the owner, 2026-11-25): the same pages as on a TV, with these
// parts in place of the TV's fixed margins and rows. On a TV, each part is what the page had before.

/**
 * A settings page's column: [content] with the page's margins ([tvPadding] on a TV), [spacing] apart, and scrolling
 * when [scroll]. On a touch screen it has the phone's margins, and it is [PAGE_MAX] wide at most, in the middle.
 */
@Composable
internal fun SettingsColumn(
    modifier: Modifier = Modifier,
    scroll: Boolean = false,
    spacing: Dp = 0.dp,
    tvPadding: PaddingValues = TV_PADDING,
    content: @Composable ColumnScope.() -> Unit
) {
    val tv = LocalFormFactor.current.isTv
    // On a touch screen, clear of the keyboard, so that the box being typed in can be scrolled into view.
    val size = if (tv) modifier.fillMaxSize() else modifier.fillMaxSize().imePadding().wrapContentWidth().widthIn(max = PAGE_MAX)
    Column(
        size.then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier)
            .padding(if (tv) tvPadding else TOUCH_PADDING),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content
    )
}

/**
 * A settings page's [title]. On a TV, in [style]. On a touch screen, smaller, after a back arrow that calls [onBack]
 * ([PreviousPage] by default; none when it is null: the first screen).
 */
@Composable
internal fun SettingsTitle(
    title: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.headlineLarge,
    onBack: (() -> Unit)? = PreviousPage
) {
    if (LocalFormFactor.current.isTv) return Text(title, modifier, style = style)
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // Looked up only here: a TV draws no arrow, and a page can be shown without a navigator (in a test).
        val back = if (onBack === PreviousPage) backToPrevious() else onBack
        if (back != null) PhoneBackButton(back, Modifier.padding(end = 4.dp))
        Text(
            title,
            color = Palette.text,
            fontSize = 24.sp,
            fontWeight = FontWeight.Black,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(vertical = 8.dp)
        )
    }
}

/**
 * A page's [title] and [intro], with [controls] (a count, Done, tabs) beside them on a TV, or under them on a touch
 * screen, where the title has a back arrow that calls [onBack].
 */
@Composable
internal fun SettingsHeader(title: String, intro: String, onBack: () -> Unit, controls: @Composable () -> Unit) {
    if (!LocalFormFactor.current.isTv) {
        SettingsTitle(title, onBack = onBack)
        Muted(intro, maxLines = 3)
        ButtonRow(spacing = 8.dp, content = controls)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineLarge)
            Muted(intro, maxLines = 2)
        }
        controls()
    }
}

/** How many lines a text can take: [tv] on a TV, [touch] on a narrower phone or tablet page. */
@Composable
internal fun touchLines(tv: Int, touch: Int): Int = if (LocalFormFactor.current.isTv) tv else touch

/** The back arrow's usual action (see [SettingsTitle]): what Back does, [backToPrevious]. */
internal val PreviousPage: () -> Unit = {}

/** What Back does on a page: the page below, or else Home (see Navigator.back). */
@Composable
internal fun backToPrevious(): () -> Unit {
    val nav = LocalNavigator.current
    return remember(nav) { { nav.back() } }
}

/** Buttons in a row, [spacing] apart. On a touch screen, they go on to the next line when the row is full. */
@Composable
internal fun ButtonRow(spacing: Dp = 12.dp, verticalAlignment: Alignment.Vertical = Alignment.Top, content: @Composable () -> Unit) {
    if (LocalFormFactor.current.isTv) {
        Row(horizontalArrangement = Arrangement.spacedBy(spacing), verticalAlignment = verticalAlignment) { content() }
    } else {
        WrapRow(spacing, content)
    }
}

/** How the boxes of a [FormRow] share it. */
internal interface FormRowScope {
    /** A box taking a [weight] share of the row: on a phone, the whole width. */
    fun Modifier.share(weight: Float = 1f): Modifier
}

/** Boxes side by side. On a phone (too narrow for two), one under the other. */
@Composable
internal fun FormRow(content: @Composable FormRowScope.() -> Unit) {
    if (LocalFormFactor.current == FormFactor.PHONE) {
        Column(verticalArrangement = Arrangement.spacedBy(FORM_GAP)) { StackedForm.content() }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { SideBySideForm(this).content() }
    }
}

private object StackedForm : FormRowScope {
    override fun Modifier.share(weight: Float): Modifier = fillMaxWidth()
}

private class SideBySideForm(private val row: RowScope) : FormRowScope {
    override fun Modifier.share(weight: Float): Modifier = with(row) { this@share.weight(weight) }
}

/**
 * A card with [buttons] beside it (a playlist and its Edit). On a phone, the buttons go under the card, on the right;
 * [hasButtons] false leaves no room for them.
 */
@Composable
internal fun CardWithButtons(hasButtons: Boolean, card: @Composable (Modifier) -> Unit, buttons: @Composable () -> Unit) {
    if (LocalFormFactor.current != FormFactor.PHONE) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            card(Modifier.weight(1f))
            buttons()
        }
        return
    }
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        card(Modifier.fillMaxWidth())
        if (hasButtons) ButtonRow(spacing = 8.dp, content = buttons)
    }
}

/** A settings page's margins on a TV. */
private val TV_PADDING = PaddingValues(horizontal = 96.dp, vertical = 40.dp)

/** Its margins on a touch screen. */
private val TOUCH_PADDING = PaddingValues(start = PhoneGutter, end = PhoneGutter, top = 8.dp, bottom = 24.dp)

/** How wide a settings page is at most on a touch screen: an unfolded screen keeps its rows a comfortable length. */
private val PAGE_MAX = 760.dp

/** The space between the boxes of a [FormRow] when they are one under the other. */
private val FORM_GAP = 14.dp
