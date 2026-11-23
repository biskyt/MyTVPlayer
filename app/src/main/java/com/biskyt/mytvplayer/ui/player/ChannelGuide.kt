package com.biskyt.mytvplayer.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.biskyt.mytvplayer.AppContainer
import com.biskyt.mytvplayer.data.ChannelFilter
import com.biskyt.mytvplayer.data.ChannelKinds
import com.biskyt.mytvplayer.data.db.ChannelEntity
import com.biskyt.mytvplayer.ui.common.cycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The list a player zaps through, and the channel guide panel's channel, for live TV and catch-up alike (the owner,
 * 2026-11-23: they are both live TV, catch-up only earlier). ◀ ▶ in the panel move its channel through the list,
 * without changing what plays.
 */
class ChannelGuide(
    private val scope: CoroutineScope,
    private val c: AppContainer,
    private val playlistId: Long,
    private val filter: ChannelFilter,
    /** The kinds of channels in the list (see ChannelKinds), read once the player has its settings. */
    private val kinds: () -> ChannelKinds,
) {
    /** The channel the panel shows. */
    var channel by mutableStateOf<ChannelEntity?>(null)
        private set

    /** The list's channels, in order; empty until [load]. */
    var ids: List<Long> = emptyList()
        private set

    /** The channel the panel is moving to: set at once, so that quick presses each count. */
    var shownId: Long? = null
        private set
    private var job: Job? = null

    /** Reads the list (after the first picture: it is not worth holding that up for). */
    suspend fun load() {
        ids = c.channels.ids(playlistId, filter, kinds())
    }

    /** Shows [ch] in the panel. */
    fun show(ch: ChannelEntity?) {
        job?.cancel()
        channel = ch
        shownId = ch?.streamId
    }

    /** Moves the panel's channel [step] channels on through the list. */
    fun step(step: Int) {
        val current = shownId ?: return
        if (ids.isEmpty()) return
        val next = ids.cycle(current, step)
        shownId = next
        job?.cancel()
        job = scope.launch { channel = c.channels.get(playlistId, next) }
    }

    /** The channel [step] on from [streamId] in the list (zapping); null until it is read. */
    fun next(streamId: Long, step: Int): Long? = ids.takeIf { it.isNotEmpty() }?.cycle(streamId, step)
}

/** A player with the channel guide panel. */
interface GuidePlayer {
    val guide: ChannelGuide

    /** Closes the panel. */
    fun closeGuide()

    /** OK on a programme on now or later: the panel's channel, live. */
    fun watchGuideChannel()

    /** OK on a past programme of a channel with catch-up: the panel's channel, from [startMs]. */
    fun watchFromGuide(startMs: Long)
}
