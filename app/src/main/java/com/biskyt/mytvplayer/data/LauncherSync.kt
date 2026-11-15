package com.biskyt.mytvplayer.data

import android.content.Context
import android.content.Intent
import android.util.Log
import com.biskyt.mytvplayer.MainActivity
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.appString
import com.biskyt.mytvplayer.core.vod.VodTitles
import com.biskyt.mytvplayer.data.db.AppDatabase
import com.biskyt.mytvplayer.data.db.ChannelEntity
import com.biskyt.mytvplayer.data.db.VOD_EPISODE
import com.biskyt.mytvplayer.data.db.VOD_MOVIE
import com.biskyt.mytvplayer.data.db.VodCard
import com.biskyt.mytvplayer.data.db.WatchProgressEntity
import com.biskyt.mytvplayer.launcher.LauncherChannel
import com.biskyt.mytvplayer.launcher.LauncherLink
import com.biskyt.mytvplayer.launcher.LauncherRows
import com.biskyt.mytvplayer.launcher.LauncherWriter
import com.biskyt.mytvplayer.launcher.PlayNextCard
import com.biskyt.mytvplayer.launcher.RowCard
import com.biskyt.mytvplayer.launcher.TvLauncher
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * What a launcher card opens: a film or an episode to play ([kind] VOD_MOVIE or VOD_EPISODE, as the progress), a
 * channel, a favourite film's or series' page, or a row's list (the row's own link).
 */
data class LauncherTarget(val kind: String, val playlistId: Long, val itemId: Long = 0, val seriesId: Long? = null) {
    companion object {
        const val CHANNEL = "channel"
        const val FAVOURITES = "favourites"
        const val MOVIE_PAGE = "movie-page"
        const val SERIES_PAGE = "series-page"
        const val FAVOURITE_MOVIES = "favourite-movies"
        const val FAVOURITE_SERIES = "favourite-series"
    }
}

/**
 * Continue watching and the favourite channels, movies and series (a row each) on the TV's home screen (setting "Show on the TV's home screen", the
 * owner 2026-11-14), through the launcher package. Off by default: while it is off, nothing here reads the database or
 * writes to the TV, and the parts the launcher calls are turned off (see TvLauncher.setEnabled). Turned off, the cards
 * are taken off the TV. Restricted titles and channels never go there, locked or not (parental controls). Only the
 * active playlist's cards.
 */
class LauncherSync(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
    private val db: AppDatabase,
    private val launcher: LauncherWriter = TvLauncher(context),
) {
    /**
     * The app's rows (channels) made and not yet shown: Settings asks the TV to show them (the TV can ask the user),
     * then takes them out. Kept until then (they can be made while Settings is not open), unless taken away first.
     */
    val madeChannels = MutableStateFlow<Set<Long>>(emptySet())

    /** One write at a time, and none after the rows are turned off (see [republish]). */
    private val writes = Mutex()

    /** The data written last (under [writes]): the same again is not written again, unless a launcher asks for it. */
    private var written: Cards? = null

    init {
        scope.launch {
            settings.settings.map { it.launcherRows }.distinctUntilChanged().collectLatest { on ->
                when {
                    on -> rows().collect { writing { write(it) } }
                    // Turned off (or off since it was last on): the cards come off the TV. Off from the start: nothing.
                    writing { launcher.isOn() } == true -> writing {
                        launcher.clear()
                        written = null
                        madeChannels.value = emptySet()
                    }
                }
            }
        }
    }

    /** A launcher asked for the cards (see LauncherReceiver): written again now, if the rows are on. */
    fun republish() = writeNow(again = true)

    /**
     * Playback stopped, or the user forgot something (see AppContainer): the cards written now, if the rows are on, so
     * that Continue watching on the TV's home screen is up to date when the user goes back there. Changes are otherwise
     * written once they settle (see rows).
     */
    fun playbackStopped() = writeNow(again = false)

    /** [again]: also if nothing changed since the last write (a launcher whose data was cleared asks again). */
    private fun writeNow(again: Boolean) {
        scope.launch {
            // Not even read while the rows are off (or with no playlist). Read under the lock, so that an older read is never
            // written after a newer one; checked again there, as the rows may have been turned off since.
            settings.current().takeIf { it.launcherRows && it.activePlaylistId != 0L } ?: return@launch
            writing {
                val now = settings.current()
                if (now.launcherRows && now.activePlaylistId != 0L) write(data(now.activePlaylistId).first(), again)
            }
        }
    }

    /** Whether [t] may still open: a card can be older than a change in the app (a favourite taken off, a title restricted). */
    suspend fun allows(t: LauncherTarget): Boolean = when (t.kind) {
        LauncherTarget.FAVOURITES, LauncherTarget.FAVOURITE_MOVIES, LauncherTarget.FAVOURITE_SERIES -> db.playlists().get(t.playlistId) !=
            null
        LauncherTarget.MOVIE_PAGE -> db.vod().launcherMayOpenMovie(t.playlistId, t.itemId)
        LauncherTarget.SERIES_PAGE -> db.vod().launcherMayOpenSeries(t.playlistId, t.itemId)
        LauncherTarget.CHANNEL -> db.channels().launcherMayOpen(t.playlistId, t.itemId)
        VOD_MOVIE, VOD_EPISODE -> db.vod().launcherMayOpen(t.playlistId, t.kind, t.itemId)
        else -> false
    }

    /**
     * The cards: at once (and for another playlist), then once changes stop for [SETTLE_MS]. Playback saves its place
     * every 15 s: the cards are written once it stops, not all the way through.
     */
    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    private fun rows(): Flow<Cards> {
        var shown: Long? = null
        return settings.activePlaylistId.flatMapLatest(::data)
            .debounce { if (it.pid == shown) SETTLE_MS else 0L }
            .onEach { shown = it.pid }
    }

    private fun data(pid: Long): Flow<Cards> = combine(
        db.vod().observeLauncherCarryOn(pid, LIMIT, FINISHED),
        db.channels().observeLauncherFavourites(pid, LIMIT),
        db.vod().observeLauncherMovieFavourites(pid, LIMIT),
        db.vod().observeLauncherSeriesFavourites(pid, LIMIT),
    ) { carryOn, channels, movies, series -> Cards(pid, carryOn, channels, movies, series) }.distinctUntilChanged()

    /** The data the cards are made from: compared before the cards (their intents are never equal). */
    private data class Cards(
        val pid: Long,
        val carryOn: List<WatchProgressEntity>,
        val channels: List<ChannelEntity>,
        val movies: List<VodCard>,
        val series: List<VodCard>,
    )

    /** A row with no cards is left out (not an empty one, nor the TV asking to show it). */
    private fun Cards.rows() = LauncherRows(
        playNext = carryOn.map { it.card() },
        channels = listOfNotNull(
            row(LauncherTarget.FAVOURITES, R.string.launcher_favourites, channels.map { it.card() }),
            row(LauncherTarget.FAVOURITE_MOVIES, R.string.home_favourite_movies, movies.map { card(it, RowCard.Kind.MOVIE) }),
            row(LauncherTarget.FAVOURITE_SERIES, R.string.home_favourite_series, series.map { card(it, RowCard.Kind.SERIES) }),
        ),
    )

    /**
     * A row, keyed by [list], the kind of target its own link opens: the same each time (the favourite channels' row kept
     * its key, "favourites", from 1.18.0).
     */
    private fun Cards.row(list: String, name: Int, cards: List<RowCard>) =
        cards.takeIf { it.isNotEmpty() }?.let { LauncherChannel(list, appString(name), it, open(LauncherTarget(list, pid))) }

    /** A favourite film's or series' card ([kind] MOVIE or SERIES): its poster, opening its page. */
    private fun Cards.card(v: VodCard, kind: RowCard.Kind): RowCard {
        val page = if (kind == RowCard.Kind.MOVIE) LauncherTarget.MOVIE_PAGE else LauncherTarget.SERIES_PAGE
        return RowCard("$pid:$page:${v.id}", v.title, v.imageUrl, open(LauncherTarget(page, pid, v.id)), kind)
    }

    /**
     * A finished episode with another after it (the next one not known yet) is up next, as Home shows it: a tap plays the
     * next one, so its own numbers are left out.
     */
    private fun WatchProgressEntity.card() = PlayNextCard(
        key = "$playlistId:$kind:$itemId",
        title = title,
        episode = if (kind == VOD_EPISODE && !finished) {
            PlayNextCard.Episode(season, episode, episodeTitle?.let { VodTitles.episodeName(it, title) })
        } else {
            null
        },
        upNext = !started || finished,
        positionMs = positionMs,
        durationMs = durationMs,
        watchedAt = updatedAt,
        posterUrl = imageUrl,
        open = open(LauncherTarget(kind, playlistId, itemId, seriesId)),
    )

    private fun ChannelEntity.card() =
        RowCard("$playlistId:$streamId", name, logoUrl, open(LauncherTarget(LauncherTarget.CHANNEL, playlistId, streamId)))

    /**
     * Writes the cards from [cards] (under [writes]), unless they were written last ([again]: also then). The rows made
     * now are to be shown; one made before and not shown yet that is gone now is not (the TV must not be asked to show it).
     */
    private fun write(cards: Cards, again: Boolean = false) {
        if (!again && cards == written) return
        val published = launcher.publish(cards.rows())
        written = cards
        madeChannels.update { (it intersect published.channels) + published.made }
    }

    /** One at a time. A TV without the TV provider (a phone), or one that refuses: the app goes on as before (null). */
    private suspend fun <T> writing(block: suspend () -> T): T? = writes.withLock {
        withContext(Dispatchers.IO) { attempt(block) }
    }

    private suspend fun <T> attempt(block: suspend () -> T): T? {
        return try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.w(TAG, "Could not update the TV's home screen", e)
            null
        }
    }

    private fun open(t: LauncherTarget): Intent = LauncherLink.intent(
        context,
        MainActivity::class.java,
        listOfNotNull(
            KIND to t.kind,
            PLAYLIST to t.playlistId.toString(),
            ITEM to t.itemId.toString(),
            t.seriesId?.let { SERIES to it.toString() }
        ).toMap()
    )

    companion object {
        private const val TAG = "LauncherSync"

        /** As many cards as Home shows in a row. */
        private const val LIMIT = 12
        private const val FINISHED = WatchProgressEntity.FINISHED_FRACTION

        /** Longer than playback's 15 s between saves (see rows). */
        private const val SETTLE_MS = 20_000L

        private const val KIND = "kind"
        private const val PLAYLIST = "playlist"
        private const val ITEM = "item"
        private const val SERIES = "series"

        /** What a launcher card asks the app to open ([intent] from the card), or null for another intent. */
        fun target(context: Context, intent: Intent): LauncherTarget? {
            val v = LauncherLink.read(context, intent) ?: return null
            val pid = v[PLAYLIST]?.toLongOrNull()?.takeIf { it != 0L } ?: return null
            return LauncherTarget(v[KIND] ?: return null, pid, v[ITEM]?.toLongOrNull() ?: 0, v[SERIES]?.toLongOrNull())
        }
    }
}
