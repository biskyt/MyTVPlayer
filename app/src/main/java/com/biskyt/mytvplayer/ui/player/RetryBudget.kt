package com.biskyt.mytvplayer.ui.player

/** How many times a failing stream is tried again before the user is told: a few in a row, until [reset]. */
class RetryBudget(private val max: Int) {
    var used = 0
        private set

    /** Counts one try; false when it is past the [max] (live TV can go on trying, and waits longer each time). */
    fun take(): Boolean = ++used <= max

    fun reset() {
        used = 0
    }
}

/**
 * How long the [attempt]th try in a row (from 0) waits: 1, 2, 4 and 8 seconds, then every 15 seconds, so that a feed
 * that is gone a while is not asked many times a second, and one that is back soon plays soon.
 */
fun retryDelayMs(attempt: Int): Long = if (attempt >= RETRY_STEPS) MAX_RETRY_MS else 1_000L shl attempt

private const val RETRY_STEPS = 4
private const val MAX_RETRY_MS = 15_000L

/**
 * Whether a failure past the retry budget is tried again all the same: on live TV that has played ([live], [played]), a
 * failure that passes ([passing], see isPassing), until it has failed with no picture for [GIVE_UP_MS]. Then the error
 * shows, with Try again: a session the server ended without saying so (a playlist that stops) does not say
 * "Reconnecting…" for ever.
 */
fun keepsTrying(live: Boolean, played: Boolean, passing: Boolean, failingForMs: Long): Boolean =
    live && played && passing && failingForMs < GIVE_UP_MS

/** How long live TV goes on trying with no picture before it shows an error. */
const val GIVE_UP_MS = 5 * 60_000L

/** Having played this long (in all) since the last failure counts as working: later failures get fresh retries. */
const val STEADY_MS = 20_000L

/**
 * A stream's run of failures: its [retries], and since when it has failed with no picture. Times are from the device's
 * start, not the clock on the wall, which can jump when the time is set.
 */
class FailureRun(maxRetries: Int) {
    val retries = RetryBudget(maxRetries)

    /** When the stream began to fail, with no picture since (0: it plays). */
    private var failingSince = 0L

    /** Whether the stream really played (its picture or its time moved, see StallWatch) since its last failure. */
    private var moved = false

    /** The stream really plays again: the time with no picture starts again. */
    fun moving() {
        failingSince = 0
        moved = true
    }

    /** Paused by the viewer: the time with no picture stops there, so a later failure has its full [GIVE_UP_MS]. */
    fun paused() {
        failingSince = 0
    }

    /** Another stream: nothing of this one's failures is kept. */
    fun reset() {
        failingSince = 0
        moved = false
        retries.reset()
    }

    /**
     * A failure at [now], after [playedMs] of play since the last one: whether to try again (see [keepsTrying] for
     * [live], [played] and [passing]).
     * - Played steadily since the last failure: a new run of failures. A moment of READY between stalls (a frozen
     *   stream, a playlist that replays old parts) is not enough, or the give-up time would never come. Nor is play time
     *   with no picture and no time that moved (MyPlexPlayer's review, 2026-11-23).
     */
    fun failed(now: Long, playedMs: Long, live: Boolean, played: Boolean, passing: Boolean): Boolean {
        if (moved && playedMs >= STEADY_MS) retries.reset()
        moved = false
        if (failingSince == 0L) failingSince = now
        val keepTrying = keepsTrying(live, played, passing, failingForMs = now - failingSince)
        return retries.take() || keepTrying
    }
}
