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
