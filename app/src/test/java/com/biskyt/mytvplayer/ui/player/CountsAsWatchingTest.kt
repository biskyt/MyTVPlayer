package com.biskyt.mytvplayer.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A title is saved (and so goes on Carry on watching) once it has played 30 seconds. */
class CountsAsWatchingTest {
    @Test
    fun `a look at the start isn't watching it`() {
        assertFalse(countsAsWatching(playedMs = 29_500, onCarryOn = false, finished = false))
        assertTrue(countsAsWatching(playedMs = 30_000, onCarryOn = false, finished = false))
    }

    @Test
    fun `one on the row already, or played to the end, is saved at once`() {
        assertTrue(countsAsWatching(playedMs = 0, onCarryOn = true, finished = false))
        assertTrue(countsAsWatching(playedMs = 0, onCarryOn = false, finished = true))
    }
}
