package com.biskyt.mytvplayer.ui.player

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.viewModelScope
import com.biskyt.mytvplayer.AppContainer
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.appString
import com.biskyt.mytvplayer.core.player.OverlayState
import com.biskyt.mytvplayer.core.xtream.XtreamClient
import com.biskyt.mytvplayer.data.StreamFormat
import com.biskyt.mytvplayer.data.db.ChannelEntity
import com.biskyt.mytvplayer.data.db.ChannelProgramme
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.common.ItemAction
import com.biskyt.mytvplayer.ui.common.NumberEntry
import com.biskyt.mytvplayer.ui.common.channelActions
import com.biskyt.mytvplayer.ui.common.cycle
import com.biskyt.mytvplayer.ui.common.favouriteFlash
import com.biskyt.mytvplayer.ui.common.guideNowNext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Live TV: zapping through the list it was started from, number entry, now/next and the channel guide panel. */
class PlayerViewModel(c: AppContainer, appContext: Context, private val screen: Screen.Player) : PlaybackViewModel(c, appContext) {
    var channel by mutableStateOf<ChannelEntity?>(null)
        private set

    /** Channel previewed in the guide panel (may differ from the one playing). */
    var guideChannel by mutableStateOf<ChannelEntity?>(null)
        private set

    override val isLive = true

    /** The list being zapped through (kept when going to catch-up and back). */
    val filter get() = screen.filter
    override val failureMessage = R.string.player_failure_live

    val nowNext = guideNowNext(viewModelScope, c) { screen.playlistId }

    /** What's on now, with its description once read (for the controls and About); read again as the programme changes. */
    var nowDetails by mutableStateOf<ChannelProgramme?>(null)
        private set
    override val about: ChannelProgramme? get() = nowDetails

    init {
        viewModelScope.launch {
            // Read again after a guide update too (a description may be new).
            combine(snapshotFlow { channel?.let { nowNext.entries[it.streamId]?.now } }, c.epg.changes) { p, _ -> p }.collectLatest { p ->
                // In one step, so the About button doesn't go and come back meanwhile.
                nowDetails = p?.let { details(it) }
                // Nothing on (a gap in the guide): nothing to tell of.
                if (p == null && sheet == Sheet.ABOUT) sheet = null
            }
        }
    }

    /** [p] with its description, or as it is if that can't be read (e.g. the guide being replaced just then). */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun details(p: ChannelProgramme) = try {
        c.epg.withDetails(screen.playlistId, p)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("Player", "Programme details", e)
        p
    }

    /** This playlist's favourite channels, for the More menu's "Add to / Remove from favourites". */
    private var favouriteIds by mutableStateOf(emptySet<Long>())

    override fun menuActions(): List<ItemAction> {
        val ch = channel ?: return emptyList()
        return channelActions(ch, ch.streamId in favouriteIds) { favourite ->
            viewModelScope.launch {
                c.channels.setFavourite(ch.playlistId, ch.streamId, favourite)
                flash(favouriteFlash(favourite))
            }
        }
    }

    private var client: XtreamClient? = null

    /** The format provider channels are played in first: the one that worked for this provider, else the setting. */
    private var format = StreamFormat.TS

    /** The format the channel being tuned is playing in: null for an M3U one, else [format] until the other is tried. */
    private var tuning: StreamFormat? = null
    private var ids: List<Long> = emptyList()

    /** Whether the channel list (for zapping and the guide panel's ◀ ▶) has loaded: it comes after tuning starts. */
    internal val channelListLoaded: Boolean get() = ids.isNotEmpty()

    private var tuneJob: Job? = null

    /** The channel being tuned to; updated immediately so rapid zapping steps through channels one by one. */
    private var targetStreamId: Long = screen.streamId

    init {
        viewModelScope.launch { c.channels.observeFavouriteIds(screen.playlistId).collect { favouriteIds = it.toSet() } }
        viewModelScope.launch {
            startEngine()
            format = c.settings.streamFormatFor(screen.playlistId) ?: settings.streamFormat
            client = c.playlists.get(screen.playlistId)?.takeUnless { it.isM3u }?.let(c.playlists::client)
            tuneTo(screen.streamId)
            // Only needed for changing channel: not worth delaying the first picture for.
            ids = c.channels.ids(screen.playlistId, screen.filter)
        }
    }

    /** Typed digits switch to that channel number. */
    val numberEntry = NumberEntry(viewModelScope) { n ->
        val ch = c.channels.byNumber(screen.playlistId, n)
        if (ch != null) tuneTo(ch.streamId) else flash(appString(R.string.player_no_channel, n))
    }

    override fun typeDigit(digit: Int) = numberEntry.type(digit)

    /** Starts tuning to [streamId], cancelling any tune still in progress so the last request always wins. */
    private fun tuneTo(streamId: Long) {
        targetStreamId = streamId
        tuning = null // the last channel's failures mustn't try it in the other format any more
        tuneJob?.cancel()
        tuneJob = viewModelScope.launch { tune(streamId) }
    }

    private suspend fun tune(streamId: Long) {
        val ch = c.channels.get(screen.playlistId, streamId) ?: return
        val client = client
        channel = ch
        guideChannel = ch
        previewStreamId = ch.streamId
        resetForNewStream()
        if (ch.streamUrl != null) {
            // M3U channel: its own URL; the format is worked out from the stream itself.
            engine?.play(ch.streamUrl, hls = null, userAgent = ch.userAgent, referrer = ch.referrer)
        } else {
            playFrom(client ?: return, ch, format)
        }
        nowNext.refresh(ch.streamId)
        showControls()
        // Only remember channels actually watched: zapping past one shouldn't write to storage.
        delay(WATCHED_AFTER_MS)
        c.channels.markWatched(ch.playlistId, ch.streamId)
        c.settings.update { it.copy(lastChannelId = ch.streamId) }
    }

    private fun playFrom(client: XtreamClient, ch: ChannelEntity, f: StreamFormat) {
        tuning = f
        val hls = f == StreamFormat.HLS
        engine?.play(client.liveStreamUrl(ch.streamId, if (hls) "m3u8" else "ts"), hls)
    }

    /** Some providers only offer one of HLS and MPEG-TS (or only one works): a channel that won't start gets the other. */
    override fun tryOtherFormat(): Boolean {
        val ch = channel
        val client = client
        val tried = tuning
        if (ch == null || client == null || tried != format) return false
        playFrom(client, ch, StreamFormat.entries.cycle(tried))
        return true
    }

    /** The other format played: this provider starts with it from now on. */
    override fun onPlayingChanged(playing: Boolean) {
        val playingIn = tuning
        if (!playing || playingIn == null || playingIn == format) return
        format = playingIn
        // Kept even if the player is closed straight away.
        c.appScope.launch { c.settings.rememberStreamFormat(screen.playlistId, playingIn) }
    }

    /** A provider channel starts again in its usual format (then the other); anything else as before. */
    override fun retry() {
        val ch = channel
        val client = client
        if (tuning == null || ch == null || client == null) return super.retry()
        resetForNewStream()
        playFrom(client, ch, format)
    }

    override fun zap(step: Int) {
        if (ids.isNotEmpty()) tuneTo(ids.cycle(targetStreamId, step))
    }

    override fun openGuide() {
        cancelAutoHide()
        previewJob?.cancel()
        guideChannel = channel
        previewStreamId = channel?.streamId
        overlay = OverlayState.GUIDE
    }

    /** The channel the guide panel is moving to: set at once, so quick presses each count (as zapping's [targetStreamId]). */
    private var previewStreamId: Long? = null
    private var previewJob: Job? = null

    /** Moves the guide panel's preview to the previous/next channel without changing what is playing. */
    fun previewGuide(step: Int) {
        val current = previewStreamId ?: return
        if (ids.isEmpty()) return
        val next = ids.cycle(current, step)
        previewStreamId = next
        previewJob?.cancel()
        previewJob = viewModelScope.launch { guideChannel = c.channels.get(screen.playlistId, next) }
    }

    fun watchGuideChannel() {
        val target = previewStreamId ?: return
        closeGuide()
        if (target != targetStreamId) tuneTo(target)
    }

    private companion object {
        const val WATCHED_AFTER_MS = 3_000L
    }
}
