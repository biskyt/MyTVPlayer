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
import com.biskyt.mytvplayer.data.db.WatchProgressEntity
import com.biskyt.mytvplayer.launcher.ChannelCard
import com.biskyt.mytvplayer.launcher.LauncherLink
import com.biskyt.mytvplayer.launcher.LauncherRows
import com.biskyt.mytvplayer.launcher.LauncherWriter
import com.biskyt.mytvplayer.launcher.PlayNextCard
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * What a launcher card opens: a film or an episode ([kind] VOD_MOVIE or VOD_EPISODE, as the progress), a channel, or
 * the favourite channels (the channel's own link).
 */
data class LauncherTarget(val kind: String, val playlistId: Long, val itemId: Long = 0, val seriesId: Long? = null) {
    companion object {
        const val CHANNEL = "channel"
        const val FAVOURITES = "favourites"
    }
}

/**
 * Continue watching and the favourite channels on the TV's home screen (setting "Show on the TV's home screen", the
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
     * The app's channel, made and not yet shown: Settings asks the TV to show it (the TV asks the user), then sets it
     * back to null. Kept until then: it can be made while Settings is not open.
     */
    val madeChannel = MutableStateFlow<Long?>(null)

    /** One write at a time, and none after the rows are turned off (see [republish]). */
    private val writes = Mutex()

    init {
        scope.launch {
            settings.settings.map { it.launcherRows }.distinctUntilChanged().collectLatest { on ->
                when {
                    on -> rows().collect(::publish)
                    // Turned off (or off since it was last on): the cards come off the TV. Off from the start: nothing.
                    writing { launcher.isOn() } == true -> writing(launcher::clear)
                }
            }
        }
    }

    /** A launcher asked for the cards (see LauncherReceiver): written again, if the rows are on (then, not before). */
    fun republish() {
        scope.launch {
            val pid = settings.current().activePlaylistId
            val rows = data(pid).first().rows()
            writing { if (settings.current().launcherRows) launcher.publish(rows)?.let { madeChannel.value = it } }
        }
    }

    /** Whether [t] may still open: a card can be older than a change in the app (a favourite taken off, a title restricted). */
    suspend fun allows(t: LauncherTarget): Boolean = when (t.kind) {
        LauncherTarget.FAVOURITES -> db.playlists().get(t.playlistId) != null
        LauncherTarget.CHANNEL -> db.channels().launcherMayOpen(t.playlistId, t.itemId)
        VOD_MOVIE, VOD_EPISODE -> db.vod().launcherMayOpen(t.playlistId, t.kind, t.itemId)
        else -> false
    }

    /**
     * The cards: at once (and for another playlist), then once changes stop for [SETTLE_MS]. Playback saves its place
     * every 15 s: the cards are written once it stops, not all the way through.
     */
    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    private fun rows(): Flow<LauncherRows> {
        var shown: Long? = null
        return settings.activePlaylistId.flatMapLatest(::data)
            .debounce { if (it.pid == shown) SETTLE_MS else 0L }
            .onEach { shown = it.pid }
            .map { it.rows() }
    }

    private fun data(pid: Long): Flow<Cards> =
        combine(db.vod().observeLauncherCarryOn(pid, LIMIT, FINISHED), db.channels().observeLauncherFavourites(pid, LIMIT)) {
                carryOn,
                favourites
            ->
            Cards(pid, carryOn, favourites)
        }.distinctUntilChanged()

    /** The data the cards are made from: compared before the cards (their intents are never equal). */
    private data class Cards(val pid: Long, val carryOn: List<WatchProgressEntity>, val favourites: List<ChannelEntity>)

    private fun Cards.rows() = LauncherRows(
        playNext = carryOn.map { it.card() },
        channelName = appString(R.string.launcher_favourites),
        channel = favourites.map { it.card() },
        channelOpen = open(LauncherTarget(LauncherTarget.FAVOURITES, pid)),
    )

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
        ChannelCard("$playlistId:$streamId", name, logoUrl, open(LauncherTarget(LauncherTarget.CHANNEL, playlistId, streamId)))

    private suspend fun publish(rows: LauncherRows) {
        writing { launcher.publish(rows)?.let { madeChannel.value = it } }
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
