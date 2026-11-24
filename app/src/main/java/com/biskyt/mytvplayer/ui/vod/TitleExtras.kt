package com.biskyt.mytvplayer.ui.vod

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.biskyt.mytvplayer.AppContainer
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.appString
import com.biskyt.mytvplayer.container
import com.biskyt.mytvplayer.core.tmdb.TmdbCredits
import com.biskyt.mytvplayer.core.tmdb.TmdbEpisode
import com.biskyt.mytvplayer.core.tmdb.TmdbTitle
import com.biskyt.mytvplayer.core.vod.DownloadNames
import com.biskyt.mytvplayer.core.xtream.EpisodeInfo
import com.biskyt.mytvplayer.data.db.CAT_MOVIE
import com.biskyt.mytvplayer.data.db.proxy
import com.biskyt.mytvplayer.ui.LocalNavigator
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.common.AppIcons
import com.biskyt.mytvplayer.ui.common.CastPanel
import com.biskyt.mytvplayer.ui.common.ItemAction
import com.biskyt.mytvplayer.ui.common.Tab
import com.biskyt.mytvplayer.ui.common.TrailersPanel
import com.biskyt.mytvplayer.ui.common.rememberTrailerOpener
import com.biskyt.mytvplayer.ui.player.DescribedEpisode
import com.biskyt.mytvplayer.ui.player.EpisodeDescriptionPanel
import com.biskyt.mytvplayer.ui.player.episodeDescriptionAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// A film's or show's More… away from its page (Home's cards, the player): the same options and panels as on its page
// (the owner, 2026-11-22), from one place, so that they stay the same everywhere.

/** A panel that a title's More… opens away from its page. */
sealed interface TitlePanel {
    data object Cast : TitlePanel

    data object About : TitlePanel

    data object Trailers : TitlePanel

    data class Describe(val episode: DescribedEpisode) : TitlePanel

    /** An episode's own cast and crew: [name] is the episode's. */
    data class EpisodeCast(val name: String, val credits: TmdbCredits) : TitlePanel
}

/**
 * The film's or series' TMDB title for playlist [playlistId] ([type] CAT_MOVIE or CAT_SERIES, [id] its stream or
 * series id), found by its name as its page finds it; null without a TMDB key, or when TMDB has no match.
 */
suspend fun AppContainer.tmdbFor(playlistId: Long, type: String, id: Long): TmdbTitle? = if (type == CAT_MOVIE) {
    vod.movie(playlistId, id)?.let { tmdb.movie(it.title, null) }
} else {
    vod.series(playlistId, id)?.let { tmdb.series(it.title, null) }
}

/** [tmdbFor], read once for a panel: null until it is known (the panel's other options show at once). */
@Composable
fun rememberTmdb(c: AppContainer, playlistId: Long, type: String, id: Long): TmdbTitle? {
    val title by produceState<TmdbTitle?>(null, playlistId, type, id) { value = c.tmdbFor(playlistId, type, id) }
    return title
}

/** TMDB's episode [number] of [season] of series [tmdb], read once for a panel: null until known, or unknown. */
@Composable
fun rememberTmdbEpisode(c: AppContainer, tmdb: TmdbTitle?, season: Int?, number: Int?): TmdbEpisode? {
    val episode by produceState<TmdbEpisode?>(null, tmdb?.id, season, number) {
        if (tmdb != null && tmdb.series && season != null && number != null) {
            value = c.tmdb.season(tmdb.id, season)?.firstOrNull { it.number == number }
        }
    }
    return episode
}

/** TMDB's cast and crew of episode [number] of [season] of series [tmdb], read once for a panel: null until known. */
@Composable
fun rememberEpisodeCredits(c: AppContainer, tmdb: TmdbTitle?, season: Int?, number: Int?): TmdbCredits? {
    val credits by produceState<TmdbCredits?>(null, tmdb?.id, season, number) {
        if (tmdb != null && tmdb.series && season != null && number != null) value = c.tmdb.episodeCredits(tmdb.id, season, number)
    }
    return credits
}

/**
 * An episode's own cast and crew ([credits]) in a side panel, titled [name] (the episode's). The person at [focus] has
 * the remote first; OK on one opens their page, after [opening] is told their place (to come back to it).
 */
@Composable
fun EpisodeCastPanel(playlistId: Long, name: String, credits: TmdbCredits, focus: Int, opening: (Int) -> Unit, onDismiss: () -> Unit) {
    val nav = LocalNavigator.current
    val crew = remember(credits) { castAndCrew(credits) }
    CastPanel(name, crew, stringResource(R.string.vod_cast_crew), focus, open = { i ->
        opening(i)
        personScreen(playlistId, crew[i], name, Tab.SERIES, i < credits.acting())?.let(nav::push)
    }, onDismiss = onDismiss)
}

/**
 * What a More… away from the title's page adds from TMDB, as its page offers it: the episode's description (with
 * TMDB's extra details, see [filledFrom]), then the trailer, Other trailers, Cast and crew, More like this, About and
 * links (see [titleActions]). [open] opens their panels (see [TitlePanels]).
 */
@Composable
fun titleExtraActions(
    playlistId: Long,
    tmdb: TmdbTitle?,
    film: Boolean,
    title: String,
    described: DescribedEpisode?,
    open: (TitlePanel) -> Unit,
): List<ItemAction> {
    val nav = LocalNavigator.current
    val playTrailer = rememberTrailerOpener()
    val tab = if (film) Tab.MOVIES else Tab.SERIES
    val episode = described?.let { d -> episodeDescriptionAction(d) { open(TitlePanel.Describe(d)) } }
    return listOfNotNull(episode) + titleActions(
        tmdb,
        film,
        tmdb?.trailer?.url?.let { url -> { playTrailer(url) } },
        otherTrailers = { open(TitlePanel.Trailers) },
        showCast = { open(TitlePanel.Cast) },
        showSimilar = { id -> nav.push(Screen.MoreLike(playlistId, id, series = !film, title, tab)) },
        showAbout = { open(TitlePanel.About) },
    )
}

/** The panel [open] (null: none) for [title], with [tmdb]'s data. OK on a person opens their page. */
@Composable
fun TitlePanels(open: TitlePanel?, playlistId: Long, title: String, tmdb: TmdbTitle?, film: Boolean, close: () -> Unit) {
    val nav = LocalNavigator.current
    when (open) {
        null -> Unit
        TitlePanel.Cast -> {
            val crew = remember(tmdb) { castAndCrew(tmdb) }
            val acting = tmdb?.cast?.size ?: 0
            val tab = if (film) Tab.MOVIES else Tab.SERIES
            CastPanel(title, crew, stringResource(R.string.vod_cast_crew), open = { i ->
                personScreen(playlistId, crew[i], title, tab, i < acting)?.let(nav::push)
            }, onDismiss = close)
        }
        TitlePanel.About -> tmdb?.let { AboutPanel(film, it, close) }
        TitlePanel.Trailers -> TrailersPanel(title, tmdb?.trailers.orEmpty(), close)
        is TitlePanel.Describe -> EpisodeDescriptionPanel(open.episode, close)
        is TitlePanel.EpisodeCast -> EpisodeCastPanel(playlistId, open.name, open.credits, 0, {}, close)
    }
}

/**
 * "Download" for a film or an episode, with the setting on (null with it off; see Downloads). [start] finds the
 * stream's address and where it goes, and starts it; a message then says where it is saved. Android's download manager
 * cannot use a proxy: for a playlist ([playlistId]) that has one, a warning says so (the owner, 2026-11-23).
 */
@Composable
fun downloadAction(playlistId: Long, start: suspend AppContainer.() -> DownloadNames.Place?): ItemAction? {
    val context = LocalContext.current
    val c = context.container
    val on = c.settings.settings.collectAsState(initial = null).value?.downloads == true
    val proxied by produceState(false, playlistId) { value = c.playlists.get(playlistId)?.proxy != null }
    if (!on) return null
    return ItemAction.Run(
        stringResource(R.string.vod_download),
        help = if (proxied) stringResource(R.string.vod_download_no_proxy) else null,
        icon = if (proxied) AppIcons.Warning else null
    ) {
        c.appScope.launch {
            val place = runCatching { c.start() }.getOrNull() ?: return@launch
            withContext(Dispatchers.Main) {
                Toast.makeText(context, appString(R.string.vod_download_started, "${place.folder}/${place.file}"), Toast.LENGTH_LONG).show()
            }
        }
    }
}

/**
 * [this] episode with [t]'s (TMDB's) plot, picture and date where the provider gave none. Not its name: what is saved
 * of an episode (marking it watched) is the provider's words only.
 */
fun EpisodeInfo.filledFrom(t: TmdbEpisode?): EpisodeInfo = if (t == null) {
    this
} else {
    copy(
        plot = plot?.takeIf { it.isNotBlank() } ?: t.overview,
        imageUrl = imageUrl ?: t.stillUrl,
        airDate = airDate ?: t.airDate
    )
}
