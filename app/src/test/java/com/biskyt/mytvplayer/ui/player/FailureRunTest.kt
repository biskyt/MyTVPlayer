package com.biskyt.mytvplayer.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FailureRunTest {
    private val run = FailureRun(maxRetries = 3)
    private val minute = 60_000L

    /** The device's time: never 0 on a device, where 0 means "not failing". */
    private val start = 100_000L

    @Test
    fun `play time with nothing that moved does not give fresh tries`() {
        // A film that reopens where its decoder does not start: "playing" a held picture for a minute, then frozen.
        repeat(3) { assertTrue(run.failed(start + it * minute, playedMs = minute, live = false, played = true, passing = true)) }
        assertFalse(run.failed(start + 3 * minute, playedMs = minute, live = false, played = true, passing = true))
    }

    @Test
    fun `play that really moved for a while gives fresh tries`() {
        repeat(3) { run.failed(start + it * 1_000L, playedMs = 0, live = false, played = true, passing = true) }
        run.moving()
        assertTrue(run.failed(start + minute, playedMs = STEADY_MS, live = false, played = true, passing = true))
    }

    @Test
    fun `live TV paused by the viewer after a drop-out gets its full time again`() {
        repeat(5) { run.failed(start + it * 5_000L, playedMs = 0, live = true, played = true, passing = true) }
        run.paused() // the reconnect worked, and the viewer paused for 6 minutes
        assertTrue(run.failed(start + 6 * minute, playedMs = 0, live = true, played = true, passing = true))
    }

    @Test
    fun `live TV with no picture gives up after GIVE_UP_MS`() {
        repeat(5) { run.failed(start + it * 5_000L, playedMs = 0, live = true, played = true, passing = true) }
        assertFalse(run.failed(start + GIVE_UP_MS, playedMs = 0, live = true, played = true, passing = true))
    }
}
