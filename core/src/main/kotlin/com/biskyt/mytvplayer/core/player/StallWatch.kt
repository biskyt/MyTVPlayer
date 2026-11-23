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
 *   radio channel's picture can be a slide that changes now and then. Neither is frozen. Until the picture has moved,
 *   a picture and a time that both stand still are given [startMs], as a stream that has not started yet
 *   (MyPlexPlayer's review, 2026-11-23: otherwise that stream was never seen as stuck).
 */
class StallWatch(
    private val stepMs: Long,
    var startMs: Long = 20_000L,
    var rebufferMs: Long = 12_000L,
    private val frozenMs: Long = 8_000L,
) {
    /**
     * Whether a still picture can be a freeze: live TV's. A film's still scenes can send no new pictures (a variable
     * frame rate), so for a film only a still time counts (MyPlexPlayer's review, 2026-11-23).
     */
    var watchPicture = true

    /** Pictures at a check, at least, for the picture to count as moving: a video's rate, not a slide's now and then. */
    private val videoFrames = MIN_FPS * stepMs / MS_PER_S

    /**
     * Called once the stream really plays after the start (or a reopen, or a seek): its picture moves at a video's rate,
     * or with no video, its time moves.
     */
    var onMoving: () -> Unit = {}

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

    /** Whether [onMoving] was called since the start. */
    private var moving = false

    /**
     * Whether this stream's picture has moved at a video's rate, at any time since [newStream]: then it is video, and
     * after a reopen or a seek its picture must move again (MyPlexPlayer's review, 2026-11-23). A radio channel's slide
     * never moves at that rate.
     */
    private var isVideo = false

    /** Video whose picture is watched, and that showed it moves (see [isVideo]). */
    private val liveVideo get() = watchPicture && isVideo

    /** Starts again (a stream opened again, or a seek). */
    fun reset() {
        last = null
        waitingMs = 0
        frozenForMs = 0
        pictureMoved = false
        moving = false
    }

    /** Starts again for another stream. */
    fun newStream() {
        reset()
        isVideo = false
    }

    /** Takes [now], and says what is stuck, if anything has been for too long. */
    fun check(now: Sample): Stall? {
        val before = last
        last = now
        waitingMs = if (now.buffering && before?.buffering == true && now.buffered == before.buffered) waitingMs + stepMs else 0
        val limit = if (before?.playing == true) {
            noteMoving(before, now)
            stillLimit(before, now)
        } else {
            null
        }
        frozenForMs = if (now.playing && limit != null) frozenForMs + stepMs else 0
        return when {
            waitingMs >= if (now.started) rebufferMs else startMs -> Stall.BUFFERING
            limit != null && frozenForMs >= limit -> Stall.FROZEN
            else -> null
        }.also { if (it != null) reset() }
    }

    /**
     * Notes whether the picture moved at a video's rate from [before] to [now], and calls [onMoving] the first time the
     * stream plays.
     */
    private fun noteMoving(before: Sample, now: Sample) {
        if (before.frames != null && now.frames != null && now.frames - before.frames >= videoFrames) {
            pictureMoved = true
            isVideo = true
        }
        // The time moving counts too: a radio channel's slide changes only now and then, yet its sound plays
        // (MyPlexPlayer's review, 2026-11-23). Not for live video that showed it moves: its picture must move again.
        val timeCounts = now.frames == null || !liveVideo
        if (!moving && (pictureMoved || timeCounts && now.position != before.position)) {
            moving = true
            onMoving()
        }
    }

    /**
     * How long the stream may stand still as it did from [before] to [now] while it played (its picture, or its time),
     * or null when it moved.
     */
    private fun stillLimit(before: Sample, now: Sample): Long? {
        val sameFrames = now.frames != null && now.frames == before.frames
        val timeStill = now.position == before.position
        return when {
            // With no video, the time is all there is.
            now.frames == null -> frozenMs.takeIf { timeStill }
            pictureMoved -> frozenMs.takeIf { timeStill || watchPicture && sameFrames }
            // Before the picture has moved, a still time alone is not a freeze (a first picture held, or the sound's
            // output starting again, as when the TV's HDMI link changes). With a still picture too, it is given as
            // long as a start. So is a still picture alone, on live video that showed it moves (a picture that froze
            // again after a reopen, while the sound plays).
            else -> startMs.takeIf { sameFrames && (timeStill || liveVideo) }
        }
    }

    private companion object {
        const val MIN_FPS = 5L
        const val MS_PER_S = 1_000L
    }
}
