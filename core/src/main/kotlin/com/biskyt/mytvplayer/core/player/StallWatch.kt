package com.biskyt.mytvplayer.core.player

/**
 * Notices a stream that is stuck for the whole of its playback, not only as it starts (the owner, 2026-11-23: a stream
 * could stay on "Loading" for ever). It is told how the player is at each check, [stepMs] apart, and says when one of
 * these goes on too long:
 * - Waiting for data, and the buffer gets no further: no data, or data that trickles in too slowly to play. A stream
 *   that has not started yet is given [startMs], one that played and is waiting again [rebufferMs].
 * - Playing, and the picture or the time does not move: a frozen picture, or a stream that only seems to play. It is
 *   given [frozenMs]. The picture counts only once it has moved at a video's rate since the start (or a seek): a
 *   stream often starts with its first picture held while the sound catches up with it (the owner, 2026-11-23), and a
 *   radio channel's picture can be a slide that changes now and then. Neither is frozen.
 */
class StallWatch(
    private val stepMs: Long,
    var startMs: Long = 20_000L,
    var rebufferMs: Long = 12_000L,
    private val frozenMs: Long = 8_000L,
) {
    /** Pictures at a check, at least, for the picture to count as moving: a video's rate, not a slide's now and then. */
    private val videoFrames = MIN_FPS * stepMs / MS_PER_S

    /** What is stuck. */
    enum class Stall { BUFFERING, FROZEN }

    /**
     * How the player is at a check: whether it is [buffering] (and wants to play), [playing], and has [started] this
     * stream; how far it has [buffered] and [position], and how many video [frames] it has shown (null: no video).
     */
    data class Sample(
        val buffering: Boolean,
        val playing: Boolean,
        val started: Boolean,
        val buffered: Long,
        val position: Long,
        val frames: Long?,
    )

    private var last: Sample? = null
    private var waitingMs = 0L
    private var frozenForMs = 0L

    /** Whether the picture has moved at a video's rate since the start: until then, a still picture is not a freeze. */
    private var pictureMoved = false

    /** Starts again (a new stream, or one opened again). */
    fun reset() {
        last = null
        waitingMs = 0
        frozenForMs = 0
        pictureMoved = false
    }

    /** Takes [now], and says what is stuck, if anything has been for too long. */
    fun check(now: Sample): Stall? {
        val before = last
        last = now
        waitingMs = if (now.buffering && before?.buffering == true && now.buffered == before.buffered) waitingMs + stepMs else 0
        val still = before != null && before.playing && still(before, now)
        frozenForMs = if (now.playing && still) frozenForMs + stepMs else 0
        return when {
            waitingMs >= if (now.started) rebufferMs else startMs -> Stall.BUFFERING
            frozenForMs >= frozenMs -> Stall.FROZEN
            else -> null
        }.also { if (it != null) reset() }
    }

    /** Whether the stream stood still from [before] to [now] while it played: its picture, or its time. */
    private fun still(before: Sample, now: Sample): Boolean {
        val sameFrames = now.frames != null && now.frames == before.frames
        if (before.frames != null && now.frames != null && now.frames - before.frames >= videoFrames) pictureMoved = true
        // Before the picture has moved, a still time is not a freeze either (a first picture held, or the sound's output
        // starting again, as when the TV's HDMI link changes); with no video, the time is all there is.
        val timeStill = now.position == before.position && (now.frames == null || pictureMoved)
        return timeStill || pictureMoved && sameFrames
    }

    private companion object {
        const val MIN_FPS = 5L
        const val MS_PER_S = 1_000L
    }
}
