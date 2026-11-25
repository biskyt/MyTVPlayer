package com.biskyt.mytvplayer.ui.player

import com.biskyt.mytvplayer.data.db.ChannelEntity
import com.biskyt.mytvplayer.ui.common.numberLabel

/**
 * Where playback is on the play bar, for each kind of player: the TV's timeline and the phone's bar both read it.
 * [fraction] (0 to 1) is how far playback has got, [scrub] where it will go while skipping, and [onSeek] takes the
 * fraction that a tap or a drag on the bar picks (null: the bar only shows progress).
 */
internal data class PlayBar(val fraction: Float, val scrub: Float?, val onSeek: ((Float) -> Unit)?)

/** How far time [t] is through [span], from 0 to 1. */
private fun fractionOf(t: Long, span: LongRange) = ((t - span.first).toFloat() / (span.last - span.first).coerceAtLeast(1)).coerceIn(0f, 1f)

/** A live channel: the programme on now ([span]), [progress] (0 to 1) through it. */
internal fun PlayerViewModel.liveBar(span: LongRange, progress: Float) =
    PlayBar(progress, scrubMs?.let { fractionOf(it, span) }) { scrubTo(span, it) }

/** Catch-up: where playback is in the programme ([span]). */
internal fun CatchupPlayerViewModel.catchupBar(span: LongRange) =
    PlayBar(fractionOf(at, span), scrubMs?.let { fractionOf(it, span) }) { scrubTo(span, it) }

/**
 * A film or an episode: where playback is of its whole length, in whole seconds. It reads the position, which changes
 * each second: read it only where the bar and the times are drawn. Until playback starts, the length is not known
 * and the bar takes no touches.
 */
internal fun VodPlayerViewModel.vodBar(): PlayBar {
    val totalMs = (durationSec * 1000).takeIf { it > 0 }
    fun fraction(sec: Long) = totalMs?.let { sec * 1000f / it } ?: 0f
    return PlayBar(fraction(positionSec), scrubMs?.let { fraction(it / 1000) }, totalMs?.let { t -> { scrubTo(0L..t, it) } })
}

/** "101 · BBC One", and [line] after them when given: the parts that are not empty. */
internal fun ChannelEntity.numberAndName(line: String? = null) =
    listOfNotNull(numberLabel, name, line).filter { it.isNotEmpty() }.joinToString(" · ")
