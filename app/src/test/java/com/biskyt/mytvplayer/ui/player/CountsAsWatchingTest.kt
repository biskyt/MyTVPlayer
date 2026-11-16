package com.biskyt.mytvplayer.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A title is saved (and so goes to the front of Continue watching) once it has played 30 seconds. */
class CountsAsWatchingTest {
    @Test
    fun `a look at the start isn't watching it`() {
        assertFalse(countsAsWatching(playedMs = 29_500, saving = false, finished = false))
        assertTrue(countsAsWatching(playedMs = 30_000, saving = false, finished = false))
    }

    @Test
    fun `once saved, or played to the end, it is saved at once`() {
        assertTrue(countsAsWatching(playedMs = 0, saving = true, finished = false))
        assertTrue(countsAsWatching(playedMs = 0, saving = false, finished = true))
    }
}
