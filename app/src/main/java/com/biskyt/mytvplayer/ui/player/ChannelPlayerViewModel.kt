package com.biskyt.mytvplayer.ui.player

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.biskyt.mytvplayer.AppContainer
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.appString
import com.biskyt.mytvplayer.core.player.OverlayState
import com.biskyt.mytvplayer.data.ChannelFilter
import com.biskyt.mytvplayer.data.ChannelKinds
import com.biskyt.mytvplayer.data.db.ChannelEntity
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.common.ItemAction
import com.biskyt.mytvplayer.ui.common.NumberEntry
import com.biskyt.mytvplayer.ui.common.channelActions
import com.biskyt.mytvplayer.ui.common.favouriteFlash
import com.biskyt.mytvplayer.ui.common.guideNowNext
import kotlinx.coroutines.launch

/**
 * A channel's player, live or catch-up: they are both live TV, catch-up only earlier (the owner, 2026-11-23), so they
 * share all this: the channel guide panel, zapping through the list it was opened from (of [screenKinds], TV, radio
 * or both), channel numbers, now and next, favourites in More, and the screen that takes this one's place.
 */
abstract class ChannelPlayerViewModel(
    c: AppContainer,
    appContext: Context,
    protected val playlistId: Long,
    /** The list being zapped through (kept when going to catch-up and back). */
    val filter: ChannelFilter,
    private val screenKinds: ChannelKinds,
) : PlaybackViewModel(c, appContext),
    GuidePlayer {
    var channel by mutableStateOf<ChannelEntity?>(null)
        protected set

    override val infoChannel: ChannelEntity? get() = channel

    /** The kinds of channels zapped through: the list's, or, from a list of both, those shown (see ChannelKinds). */
    val kinds: ChannelKinds get() = if (screenKinds == ChannelKinds.ALL) shownKinds else screenKinds

    override val isLive = true

    override val guide = ChannelGuide(viewModelScope, c, playlistId, filter) { kinds }

    val nowNext = guideNowNext(viewModelScope, c) { playlistId }

    /**
     * The screen to go to in this one's place: catch-up from a moment (skipping back, a past programme in the guide),
     * or a channel live (catch-up reaching the present, Watch live, zapping, a number, the guide).
     */
    var next by mutableStateOf<Screen?>(null)
        private set

    protected fun goTo(screen: Screen) {
        engine?.player?.stop()
        next = screen
    }

    /** [streamId]'s catch-up from [startMs]; [switched]: by skipping back on the live channel. */
    protected fun catchupScreen(streamId: Long, startMs: Long?, switched: Boolean = false) =
        Screen.CatchupPlayer(playlistId, streamId, startMs, filter, switched, screenKinds)

    protected fun liveScreen(streamId: Long) = Screen.Player(playlistId, streamId, filter, screenKinds)

    /** Typed digits switch to that channel number. */
    val numberEntry = NumberEntry(viewModelScope) { n ->
        val ch = c.channels.byNumber(playlistId, n, kinds)
        if (ch != null) watch(ch.streamId) else flash(appString(R.string.player_no_channel, n))
    }

    override fun typeDigit(digit: Int) = numberEntry.type(digit)

    /** Plays channel [streamId] live: tuned here (live TV), or in this one's place (catch-up). */
    protected abstract fun watch(streamId: Long)

    /** The channel buttons: the next channel in the list, live. */
    override fun zap(step: Int) {
        guide.next(zapFrom(), step)?.let(::watch)
    }

    /** The channel zapping counts from: the one playing, or the one being tuned to. */
    protected open fun zapFrom(): Long = channel?.streamId ?: 0

    override fun openGuide() {
        cancelAutoHide()
        guide.show(channel)
        overlay = OverlayState.GUIDE
    }

    override fun watchGuideChannel() {
        val target = guide.shownId ?: return
        closeGuide()
        watch(target)
    }

    /** A past programme in the guide: the channel shown there, replayed from its start. */
    override fun watchFromGuide(startMs: Long) {
        val target = guide.shownId ?: return
        closeGuide()
        goTo(catchupScreen(target, startMs))
    }

    /** This playlist's favourite channels, for the More menu's "Add to / Remove from favourites". */
    private var favouriteIds by mutableStateOf(emptySet<Long>())

    init {
        viewModelScope.launch { c.channels.observeFavouriteIds(playlistId).collect { favouriteIds = it.toSet() } }
    }

    override fun menuActions(): List<ItemAction> {
        val ch = channel ?: return emptyList()
        // All about the programme that plays, first (the owner, 2026-11-26: not a button on the controls).
        val about = about?.takeIf { it.description != null || it.subtitle != null }?.let {
            ItemAction.Run(appString(R.string.player_about_programme)) { sheet = Sheet.ABOUT }
        }
        // The channel's category, guide and more, as Live TV's options show them (the owner, 2026-11-26).
        val info = ItemAction.Run(appString(R.string.live_option_more_info), appString(R.string.live_option_more_info_help)) {
            sheet = Sheet.CHANNEL_INFO
        }
        return listOfNotNull(about, info) + channelActions(ch, ch.streamId in favouriteIds) { favourite ->
            viewModelScope.launch {
                c.channels.setFavourite(ch.playlistId, ch.streamId, favourite)
                flash(favouriteFlash(favourite))
            }
        }
    }
}
