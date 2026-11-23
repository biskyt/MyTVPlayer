package com.biskyt.mytvplayer.ui.player

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.biskyt.mytvplayer.AppContainer
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.appString
import com.biskyt.mytvplayer.core.player.OverlayState
import com.biskyt.mytvplayer.core.tmdb.TmdbEpisode
import com.biskyt.mytvplayer.core.tmdb.TmdbTitle
import com.biskyt.mytvplayer.core.vod.VodTitles
import com.biskyt.mytvplayer.core.xtream.EpisodeInfo
import com.biskyt.mytvplayer.data.db.CAT_MOVIE
import com.biskyt.mytvplayer.data.db.CAT_SERIES
import com.biskyt.mytvplayer.data.db.PlaylistEntity
import com.biskyt.mytvplayer.data.db.VOD_EPISODE
import com.biskyt.mytvplayer.data.db.VOD_MOVIE
import com.biskyt.mytvplayer.data.db.WatchProgressEntity
import com.biskyt.mytvplayer.data.db.proxy
import com.biskyt.mytvplayer.player.PlaybackEngine
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.common.ItemAction
import com.biskyt.mytvplayer.ui.common.Tab
import com.biskyt.mytvplayer.ui.common.favouriteAction
import com.biskyt.mytvplayer.ui.common.favouriteFlash
import com.biskyt.mytvplayer.ui.common.formatDuration
import com.biskyt.mytvplayer.ui.vod.TitlePanel
import com.biskyt.mytvplayer.ui.vod.filledFrom
import com.biskyt.mytvplayer.ui.vod.titleActions
import com.biskyt.mytvplayer.ui.vod.tmdbFor
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * A movie or an episode: resumes where the user left off, saves progress as it plays, and closes at the end.
 */
class VodPlayerViewModel(c: AppContainer, appContext: Context, private val screen: Screen.VodPlayer) : PlaybackViewModel(c, appContext) {
    var title by mutableStateOf("")
        private set

    /** "Series 1 · Episode 2 · The Pilot", or the year for a movie. */
    var subtitle by mutableStateOf("")
        private set

    /** Whole seconds, so the time shown changes once a second (and only while the controls are up). */
    var positionSec by mutableLongStateOf(0)
        private set
    var durationSec by mutableLongStateOf(0)
        private set

    /** The episode after this one, if any. */
    private var nextEpisode: EpisodeInfo? = null

    override val isLive = false
    override val failureMessage = R.string.player_failure_vod

    private var playlist: PlaylistEntity? = null
    private var current by mutableStateOf<Playing?>(null)
    private var tickJob: Job? = null

    /** What is playing, with what its progress row needs. */
    private data class Playing(
        val kind: String,
        val itemId: Long,
        val title: String,
        val imageUrl: String?,
        val episode: EpisodeInfo? = null,
        /** The show of [episode] (null when no longer listed). */
        val show: String? = null
    )

    /** The movie, or the series of the episode: what "Add to favourites" applies to. */
    private val series = screen.seriesId
    private val favouriteType = if (series == null) CAT_MOVIE else CAT_SERIES
    private val favouriteId = series ?: screen.itemId
    private var favourite by mutableStateOf(false)

    /** The film's or series' TMDB title, and the episode's (null without a TMDB key, or until known). */
    var tmdb by mutableStateOf<TmdbTitle?>(null)
        private set
    private var tmdbEpisode by mutableStateOf<TmdbEpisode?>(null)

    /** A TMDB panel open over the player (cast and crew, About), as the title's page opens it. */
    var panel by mutableStateOf<TitlePanel?>(null)

    /** The episode, with TMDB's plot and date where the provider gave none: as the series page describes it. */
    override val described: DescribedEpisode?
        get() = current?.let { p -> p.episode?.let { DescribedEpisode(p.show, it.filledFrom(tmdbEpisode)) } }

    override fun menuActions(): List<ItemAction> = listOfNotNull(
        described?.let { d -> episodeDescriptionAction(d.episode) { sheet = Sheet.DESCRIPTION } },
        favouriteAction(favourite) { add ->
            viewModelScope.launch {
                c.vod.setFavourite(screen.playlistId, favouriteType, favouriteId, add)
                flash(favouriteFlash(add))
            }
        },
        // In place of the player: playing from there starts a new player, not a second one over this.
        if (series == null) {
            ItemAction.Open(appString(R.string.common_about_movie), Screen.MovieDetails(screen.playlistId, screen.itemId), replace = true)
        } else {
            val episode = current?.episode
            ItemAction.Open(
                appString(R.string.common_all_episodes),
                Screen.SeriesDetails(screen.playlistId, series, episode?.season, episode?.number),
                replace = true
            )
        }
    ) + tmdbActions()

    /** As the title's page offers them: Cast and crew, More like this and About (not its links, nor trailers). */
    private fun tmdbActions(): List<ItemAction> = titleActions(
        tmdb,
        film = series == null,
        trailer = null,
        otherTrailers = null,
        showCast = { openPanel(TitlePanel.Cast) },
        showSimilar = { id ->
            val tab = if (series == null) Tab.MOVIES else Tab.SERIES
            pendingScreen = Screen.MoreLike(screen.playlistId, id, series = series != null, title, tab)
        },
        showAbout = { openPanel(TitlePanel.About) },
    ).filterNot { it is ItemAction.Links }

    private fun openPanel(p: TitlePanel) {
        sheet = null
        panel = p
    }

    /** A screen an option opens over the player (More like this): the screen pushes it. */
    var pendingScreen by mutableStateOf<Screen?>(null)

    /** The film's or series' TMDB data, once playback has started: for the menu's options. */
    private fun readTmdb(episode: EpisodeInfo?) = viewModelScope.launch {
        val t = c.tmdbFor(screen.playlistId, favouriteType, favouriteId) ?: return@launch
        tmdb = t
        if (episode != null) tmdbEpisode = c.tmdb.season(t.id, episode.season)?.firstOrNull { it.number == episode.number }
    }

    override fun beforeLeaving() = save(trim = true)

    init {
        viewModelScope.launch { c.vod.observeFavourite(screen.playlistId, favouriteType, favouriteId).collect { favourite = it } }
        viewModelScope.launch {
            startEngine()
            val p = c.playlists.get(screen.playlistId) ?: return@launch stop()
            playlist = p
            try {
                val seriesId = screen.seriesId
                if (seriesId == null) startMovie(p) else startEpisode(p, seriesId, screen.itemId, screen.fromStart, screen.continueSeries)
            } catch (e: java.io.IOException) {
                fail(e.message)
            }
        }
    }

    private suspend fun startMovie(p: PlaylistEntity) {
        val m = c.vod.movie(p.id, screen.itemId) ?: return stop()
        title = m.title
        subtitle = m.year?.toString().orEmpty()
        play(p, Playing(VOD_MOVIE, m.streamId, m.title, m.posterUrl), c.vod.movieUrl(p, m), screen.fromStart)
    }

    /** [continueSeries]: coming from "Continue watching", where [episodeId] may be one already finished: play the next. */
    private suspend fun startEpisode(
        p: PlaylistEntity,
        seriesId: Long,
        episodeId: Long,
        fromStart: Boolean,
        continueSeries: Boolean = false
    ) {
        val show = c.vod.series(p.id, seriesId)
        val episodes = c.vod.seriesDetails(p, seriesId).episodes
        var index = episodes.indexOfFirst { it.id == episodeId }.takeIf { it >= 0 } ?: return stop()
        if (continueSeries && c.vod.progress(p.id, VOD_EPISODE, episodeId)?.finished == true && index < episodes.lastIndex) index++
        val e = episodes[index]
        title = show?.title ?: episodeName(e, null)
        subtitle = episodeLabel(e, show?.title)
        nextEpisode = episodes.getOrNull(index + 1)
        // Saved for Continue watching: the provider's words only, never the app's (they'd stay in today's language).
        val playing = Playing(VOD_EPISODE, e.id, show?.title ?: e.title, show?.coverUrl ?: e.imageUrl, e, show?.title)
        play(p, playing, c.vod.episodeUrl(p, e.id, e.extension), fromStart)
    }

    private suspend fun play(p: PlaylistEntity, playing: Playing, url: String, fromStart: Boolean) {
        // Read before playback starts, so nothing waits between starting it and the controls coming up.
        val known = c.vod.progress(p.id, playing.kind, playing.itemId)
        current = playing
        if (tmdb == null || playing.episode != null) readTmdb(playing.episode)
        // A resumed title waits too: stopped at once, it keeps its place on Continue watching (the owner, 2026-11-16).
        saving = false
        resetForNewStream()
        val engine = engine ?: return
        // A film or an episode on a slow server (a file made on the fly, a disk that sleeps) may take a while to start.
        engine.play(url, hls = null, proxy = p.proxy, firstPictureMs = PlaybackEngine.ON_DEMAND_REBUFFER_MS)
        // Resume unless it was nearly finished (or the user asked to start over).
        val saved = if (fromStart) null else known
        if (saved != null && !saved.finished && saved.positionMs > RESUME_AFTER_MS) {
            engine.seekTo(saved.positionMs)
            flash(appString(R.string.player_resuming_from, formatDuration(saved.positionMs)))
        }
        showControls()
        startTicking()
    }

    /** Updates the time shown while the controls are up, and saves progress every so often while playing. */
    private fun startTicking() {
        tickJob?.cancel()
        tickJob = viewModelScope.launch {
            var sinceSave = 0L
            while (isActive) {
                val player = engine?.player ?: break
                if (overlay == OverlayState.CONTROLS) showTime()
                if (player.isPlaying) {
                    playedMs += TICK_MS
                    sinceSave += TICK_MS
                    if (sinceSave >= SAVE_EVERY_MS) {
                        sinceSave = 0
                        save()
                    }
                }
                delay(TICK_MS)
            }
        }
    }

    override fun onControlsShown() = showTime()

    /** Where playback is, for the play bar. */
    private fun showTime() {
        val player = engine?.player ?: return
        positionSec = player.currentPosition / 1000
        durationSec = player.duration.coerceAtLeast(0) / 1000
    }

    /** Pausing (or buffering, or leaving the app) saves where playback got to. */
    override fun onPlayingChanged(playing: Boolean) {
        if (!playing) save()
    }

    /** Where playback is now, as a progress row (null before the duration is known). */
    private fun snapshot(): WatchProgressEntity? {
        val p = playlist
        val item = current
        val player = engine?.player
        val duration = player?.duration?.takeIf { it > 0 }
        if (p == null || item == null || player == null || duration == null) return null
        return WatchProgressEntity(
            playlistId = p.id,
            kind = item.kind,
            itemId = item.itemId,
            positionMs = player.currentPosition.coerceAtMost(duration),
            durationMs = duration,
            updatedAt = System.currentTimeMillis(),
            title = item.title,
            imageUrl = item.imageUrl,
            seriesId = screen.seriesId,
            season = item.episode?.season,
            episode = item.episode?.number,
            episodeTitle = item.episode?.let(::providerTitle),
            hasNext = nextEpisode != null
        )
    }

    /** How long this title has played since it started (pauses not counted): see [countsAsWatching]. */
    private var playedMs = 0L

    /** Whether progress was saved since this title started playing: later saves then follow at once. */
    private var saving = false

    /** Taken now and written in the app's scope, so it completes even as the screen closes. */
    private fun save(trim: Boolean = false) {
        val row = snapshot() ?: return
        if (!countsAsWatching(playedMs, saving, row.finished)) return
        saving = true
        c.appScope.launch { c.vod.saveProgress(row, trim, nextEpisode) }
    }

    /**
     * At the end, the player closes, as Stop does (the owner, 2026-11-06): the next episode never starts by itself. An
     * episode is saved as watched, with the next one on Continue watching (see [save]).
     */
    override fun onEnded() = stop()

    override fun stop() {
        // Saved before the player stops, while its position is still known.
        save(trim = true)
        tickJob?.cancel()
        super.stop()
    }

    private companion object {
        const val TICK_MS = 500L
        const val SAVE_EVERY_MS = 15_000L

        /** Less than this in, it just starts from the beginning. */
        const val RESUME_AFTER_MS = 30_000L
    }
}

/**
 * Whether playback is saved (and so goes to the front of Continue watching): once it has played [COUNTS_AFTER_MS] since
 * it started or resumed (a short look isn't watching it), once it was saved already ([saving]), or at the end.
 */
internal fun countsAsWatching(playedMs: Long, saving: Boolean, finished: Boolean) = saving || finished || playedMs >= COUNTS_AFTER_MS

internal const val COUNTS_AFTER_MS = 30_000L

/** The provider's title for [e], as it gave it; null when it gave none. */
fun providerTitle(e: EpisodeInfo): String? = e.title.ifBlank { null }

/**
 * The provider's title for [e] without what the page shows already: the series' name ([series]), a language tag and
 * the episode code (see VodTitles.episodeName). Null when nothing is left. Only shown, never saved.
 */
fun shownTitle(e: EpisodeInfo, series: String?): String? = VodTitles.episodeName(e.title, series)

/**
 * "Series 1 · Episode 2 · Title": the provider's title only when it says more than "Episode 2" (matched in English, as
 * providers write it), so the number isn't there twice. Left out, never reworded.
 */
fun episodeLabel(e: EpisodeInfo, series: String?): String =
    shownTitle(e, series)?.takeUnless { it.equals("Episode ${e.number}", ignoreCase = true) }
        ?.let { appString(R.string.player_episode_label_titled, e.season, e.number, it) }
        ?: appString(R.string.player_episode_label, e.season, e.number)

/** How [e] of [series] is shown: [shownTitle], or the app's "Episode 2" (in its language) when it is none. Never saved. */
fun episodeName(e: EpisodeInfo, series: String?): String = shownTitle(e, series) ?: appString(R.string.vod_episode, e.number)
