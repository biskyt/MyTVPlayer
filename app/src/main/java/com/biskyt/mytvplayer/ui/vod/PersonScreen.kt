package com.biskyt.mytvplayer.ui.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.biskyt.mytvplayer.AppContainer
import com.biskyt.mytvplayer.AppText
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.appString
import com.biskyt.mytvplayer.core.tmdb.PersonCredit
import com.biskyt.mytvplayer.core.vod.CastMember
import com.biskyt.mytvplayer.data.LibraryTitle
import com.biskyt.mytvplayer.data.PersonPage
import com.biskyt.mytvplayer.ui.LocalNavigator
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.appViewModel
import com.biskyt.mytvplayer.ui.common.BoardPanel
import com.biskyt.mytvplayer.ui.common.BoardPanelText
import com.biskyt.mytvplayer.ui.common.BoardPanelTitle
import com.biskyt.mytvplayer.ui.common.ErrorRetry
import com.biskyt.mytvplayer.ui.common.Muted
import com.biskyt.mytvplayer.ui.common.Tab
import com.biskyt.mytvplayer.ui.common.formatDate
import com.biskyt.mytvplayer.ui.common.initials
import com.biskyt.mytvplayer.ui.common.rememberFirstFocus
import com.biskyt.mytvplayer.ui.theme.BoardStyle
import com.biskyt.mytvplayer.ui.theme.MEDIUM
import com.biskyt.mytvplayer.ui.theme.Palette
import com.biskyt.mytvplayer.ui.theme.SEMI_BOLD
import com.biskyt.mytvplayer.ui.theme.bd
import java.io.IOException
import kotlinx.coroutines.launch

// A cast or crew member's page, fed from TMDB (MyPlexPlayer's PersonScreen.kt; board "Cast member's page", 2026-11-06). The board is 1280 wide, as the app's own
// canvas (see MyTvTheme), not 960 as the film and series boards: its sizes are dp here, not board pixels (bd). Its
// text sizes are BoardStyle's, as on the other boards.

/**
 * [person]'s page, opened from [title] under [tab] ([acting]: their role is their part in it). Null when they have no
 * TMDB id (the provider's names only).
 */
fun personScreen(playlistId: Long, person: CastMember, title: String, tab: Tab, acting: Boolean): Screen.Person? = person.id?.let {
    Screen.Person(playlistId, it, person.name, person.photoUrl, person.role?.takeIf { acting }, title, tab)
}

/** The page, read once for the screen (and kept while it is on the back stack). */
class PersonViewModel(private val c: AppContainer, private val screen: Screen.Person) : ViewModel() {
    var page by mutableStateOf<PersonPage?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    init {
        load()
    }

    fun load() = viewModelScope.launch {
        error = null
        try {
            page = c.people.page(screen.playlistId, screen.personId)
        } catch (e: IOException) {
            error = e.message ?: appString(R.string.vod_person_failed)
        }
    }
}

/**
 * The person's photo, name and a line about them, their biography (cut short, with "Full biography"). Then "In your
 * library" (OK opens the title's page), "Also appears in" and "All credits". A part with no data is left out. Down
 * scrolls the page.
 */
@Composable
fun PersonScreen(screen: Screen.Person) {
    val vm = appViewModel { PersonViewModel(it, screen) }
    val nav = LocalNavigator.current
    val page = vm.page
    var biography by rememberSaveable { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
        item { Header(screen, page) { biography = true } }
        val error = vm.error
        when {
            error != null -> item { Box(Modifier.padding(start = SIDE, top = 40.dp)) { ErrorRetry(error) { vm.load() } } }
            page == null -> item { Muted(stringResource(R.string.common_loading), Modifier.padding(start = SIDE, top = 40.dp)) }
            else -> sections(page, first = page.biography == null) { t ->
                nav.push(if (t.show) Screen.SeriesDetails(screen.playlistId, t.id) else Screen.MovieDetails(screen.playlistId, t.id))
            }
        }
    }
    if (biography) page?.let { p -> p.biography?.let { BiographyPanel(p.name, it) { biography = false } } }
}

/** The page's parts under the header. The first card or row has the remote when [first] (no biography button). */
private fun LazyListScope.sections(page: PersonPage, first: Boolean, open: (LibraryTitle) -> Unit) {
    var focus = first
    fun takesFocus(): Boolean = focus.also { focus = false }
    if (page.library.isNotEmpty()) {
        val f = takesFocus()
        item {
            PosterRow(stringResource(R.string.vod_person_library), page.library, f, top = FIRST_GAP) { t, modifier ->
                TitleCard(t.title, t.posterUrl, cardLine(t.show, t.year, t.role, t.kind), modifier) { open(t) }
            }
        }
    }
    if (page.elsewhere.isNotEmpty()) {
        val f = takesFocus()
        item {
            val top = if (page.library.isEmpty()) FIRST_GAP else GAP
            PosterRow(stringResource(R.string.vod_person_elsewhere), page.elsewhere, f, top) { c, modifier ->
                // Not in the playlist: nothing to open. It takes the remote all the same, so that the row scrolls.
                TitleCard(c.title, c.thumb, cardLine(c.show, c.year, c.role, c.kind), modifier) {}
            }
        }
    }
    if (page.credits.isNotEmpty()) {
        val top = if (page.library.isEmpty() && page.elsewhere.isEmpty()) FIRST_GAP else GAP
        item { Heading(stringResource(R.string.vod_person_credits), Modifier.padding(start = SIDE, top = top, bottom = 2.dp)) }
        val f = takesFocus()
        itemsIndexed(page.credits) { i, c -> CreditRow(c, if (f && i == 0) rememberFirstFocus() else Modifier) }
    }
}

/**
 * The photo (200, round, with initials until it loads), the name, the line about them and the biography, cut to
 * [BIO_LINES] lines, with "Full biography" ([showBiography]). Until the page loads, what the screen was opened with.
 */
@Composable
private fun Header(screen: Screen.Person, page: PersonPage?, showBiography: () -> Unit) {
    val name = page?.name ?: screen.name
    Row(Modifier.padding(horizontal = SIDE), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
        // The cast photo already shown, if there is one: a new address would show the initials while it loads.
        PersonPhoto(screen.photoUrl ?: page?.photoUrl, name)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(name, style = NameText.current, maxLines = 2, overflow = TextOverflow.Ellipsis)
            aboutLine(page, screen.role, screen.title)?.let {
                Text(it, style = LineText.current, color = Palette.textDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            page?.biography?.let { bio ->
                Text(
                    bio,
                    style = BioText.current,
                    color = Palette.textMuted,
                    maxLines = BIO_LINES,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 860.dp)
                )
                DetailButton(
                    stringResource(R.string.vod_person_full_biography),
                    showBiography,
                    rememberFirstFocus().padding(top = 6.dp),
                    horizontal = 20.dp,
                    vertical = 9.dp,
                    style = ButtonText.current
                )
            }
        }
    }
}

/** A round photo [PHOTO] across, on a grey circle with the person's initials until (or unless) it loads. */
@Composable
private fun PersonPhoto(url: String?, name: String) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { PHOTO.roundToPx() }
    val request = remember(url) { url?.let { ImageRequest.Builder(context).data(it).size(px, px).build() } }
    var loaded by remember(url) { mutableStateOf(false) }
    Box(Modifier.size(PHOTO).clip(CircleShape).background(PhotoGrey), contentAlignment = Alignment.Center) {
        if (!loaded) Text(remember(name) { initials(name) }, style = InitialsText.current, color = InitialsColour, maxLines = 1)
        if (request != null) {
            AsyncImage(request, null, Modifier.size(PHOTO), contentScale = ContentScale.Crop, onSuccess = { loaded = true })
        }
    }
}

/**
 * A credit: its year, its title (a show's with " (TV show)") and the part or job. It takes the remote, so that Up and
 * Down scroll the list, and shows it quietly (a dark fill behind it).
 */
@Composable
private fun CreditRow(c: PersonCredit, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier.padding(horizontal = SIDE).onFocusChanged { focused = it.isFocused }.focusable()
            .background(if (focused) Palette.surfaceHigh else Color.Transparent)
    ) {
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(c.year?.toString().orEmpty(), style = RowText.current, color = Palette.textDim, modifier = Modifier.width(60.dp))
            Text(
                if (c.show) stringResource(R.string.vod_person_show_title, c.title) else c.title,
                style = RowText.current,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                c.role ?: creditKind(c.kind),
                style = RowText.current,
                color = Palette.textDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(320.dp)
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.surfaceHigh))
        // The board's 10 between rows.
        Box(Modifier.height(10.dp))
    }
}

/**
 * The whole biography, in a side panel. Each paragraph (a long one in parts) takes the remote in turn, so that Up and
 * Down scroll it.
 */
@Composable
private fun BiographyPanel(name: String, biography: String, onDismiss: () -> Unit) {
    BoardPanel(onDismiss, gap = 10.bd) {
        BoardPanelTitle(name)
        remember(biography) { bioParts(biography) }.forEachIndexed { i, part ->
            var focused by remember { mutableStateOf(false) }
            BoardPanelText(
                part,
                (if (i == 0) rememberFirstFocus() else Modifier).onFocusChanged { focused = it.isFocused }.focusable()
                    .background(if (focused) Palette.surfaceHigh else Color.Transparent)
            )
        }
    }
}

/**
 * "Actor, writer · Born 3 May 1984 in Leeds, England · Mara in The Harbour": the kinds of work, birth (and death) and
 * the part in the title the page was opened from ([title]). A part not known is left out. Null when no part is known.
 */
fun aboutLine(page: PersonPage?, role: String?, title: String): String? = listOfNotNull(
    page?.kinds?.takeIf { it.isNotEmpty() }?.mapIndexed { i, k -> creditKind(k).let { if (i == 0) it else it.lowercase(AppText.locale) } }
        ?.joinToString(", "),
    page?.born?.let { born ->
        val date = formatDate(born)
        page.birthPlace?.let { appString(R.string.vod_person_born_in, date, it) } ?: appString(R.string.vod_person_born, date)
    },
    page?.died?.let { appString(R.string.vod_person_died, formatDate(it)) },
    role?.let { appString(R.string.vod_person_role_in, it, title) },
).joinToString(" · ").ifEmpty { null }

/** Under a card: "TV show · Mara" or "2019 · Director". */
private fun cardLine(show: Boolean, year: Int?, role: String?, kind: String?): String? = listOfNotNull(
    if (show) appString(R.string.vod_person_tv_show) else year?.toString(),
    role ?: kind?.let(::creditKind),
).joinToString(" · ").ifEmpty { null }

/** A kind of credit's name: Actor, Director, Writer, Producer. */
fun creditKind(kind: String): String = appString(
    when (kind) {
        PersonCredit.DIRECTOR -> R.string.vod_role_director
        PersonCredit.WRITER -> R.string.vod_role_writer
        PersonCredit.PRODUCER -> R.string.vod_role_producer
        else -> R.string.vod_role_actor
    }
)

/** A biography in parts for its panel: its paragraphs, and a long one in groups of sentences of [max] characters or so. */
fun bioParts(text: String, max: Int = BIO_PART): List<String> = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    .flatMap { paragraph ->
        if (paragraph.length <= max) return@flatMap listOf(paragraph)
        paragraph.split(SENTENCE_END).fold(mutableListOf<String>()) { parts, sentence ->
            if (parts.isNotEmpty() &&
                parts.last().length + sentence.length < max
            ) {
                parts[parts.lastIndex] += " $sentence"
            } else {
                parts += sentence
            }
            parts
        }
    }

private val SENTENCE_END = Regex("(?<=[.!?])\\s+")

/**
 * Above the first part under the header: where the board has it (352) under a header with three lines of biography
 * (the board's 60 under the photo, less what the text adds below it).
 */
private val FIRST_GAP = 44.dp
private val PHOTO = 200.dp

private const val BIO_LINES = 3
private const val BIO_PART = 500

private val PhotoGrey = Color(0xFF3B4048)
private val InitialsColour = Color(0xFFC9CDD3)

private val NameText = BoardStyle(40, SEMI_BOLD, 1.1f)
private val LineText = BoardStyle(15, MEDIUM)
private val BioText = BoardStyle(16, MEDIUM, 1.5f)
private val ButtonText = BoardStyle(15, SEMI_BOLD)
private val InitialsText = BoardStyle(48, MEDIUM)
private val RowText = BoardStyle(15, MEDIUM)
