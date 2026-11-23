package com.biskyt.mytvplayer.core.player

import com.biskyt.mytvplayer.core.player.StallWatch.Sample
import com.biskyt.mytvplayer.core.player.StallWatch.Stall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StallWatchTest {
    private val watch = StallWatch(stepMs = 2_000, startMs = 20_000, rebufferMs = 12_000, frozenMs = 8_000)

    private fun waiting(buffered: Long, started: Boolean = true) = Sample(true, false, started, buffered, 0, null)

    private fun playing(position: Long, frames: Long? = position) = Sample(false, true, true, position + 5_000, position, frames)

    /** Checks [samples] in turn: what the last one says. */
    private fun after(samples: List<Sample>): Stall? = samples.map(watch::check).last()

    @Test
    fun `a stream that played and waits with no new data is stuck after the rebuffer time`() {
        assertNull(after(List(6) { waiting(1_000) })) // 10 s
        assertEquals(Stall.BUFFERING, watch.check(waiting(1_000))) // 12 s
    }

    @Test
    fun `a stream that has not started is given longer`() {
        assertNull(after(List(10) { waiting(1_000, started = false) })) // 18 s
        assertEquals(Stall.BUFFERING, watch.check(waiting(1_000, started = false))) // 20 s
    }

    @Test
    fun `data that keeps coming, however slowly, is not a stall`() {
        assertNull(after(List(30) { waiting(1_000L + it) }))
    }

    @Test
    fun `a picture that stops moving while the time goes on is frozen`() {
        assertNull(after(List(3) { playing(it * 2_000L, frames = it * 50L) })) // it moves
        assertNull(after(List(3) { playing(6_000L + it * 2_000L, frames = 100) })) // still for 6 s
        assertEquals(Stall.FROZEN, watch.check(playing(12_000, frames = 100))) // 8 s
    }

    @Test
    fun `a first picture held while the sound catches up is not frozen, however long`() {
        assertNull(after(List(30) { playing(it * 2_000L, frames = 1) }))
        // Then it moves, and a freeze after that counts.
        assertNull(after(List(2) { playing(60_000L + it * 2_000L, frames = 2L + it * 50) }))
        assertNull(after(List(3) { playing(64_000L + it * 2_000L, frames = 52) })) // still for 6 s
        assertEquals(Stall.FROZEN, watch.check(playing(70_000, frames = 52))) // 8 s
    }

    @Test
    fun `a film's still scene with no new pictures is not frozen, while its time goes on`() {
        watch.watchPicture = false
        assertNull(after(List(3) { playing(it * 2_000L, frames = it * 50L) })) // it moves
        assertNull(after(List(30) { playing(6_000L + it * 2_000L, frames = 100) }))
        // Its time standing still is.
        assertNull(after(List(4) { playing(70_000, frames = 100) }))
        assertEquals(Stall.FROZEN, watch.check(playing(70_000, frames = 100)))
    }

    @Test
    fun `a slide that changes now and then, as on a radio channel, is not frozen`() {
        // A new picture every 10 s, while the sound and the time go on.
        assertNull(after(List(60) { playing(it * 2_000L, frames = it / 5L) }))
    }

    @Test
    fun `the limits can change for each stream`() {
        watch.startMs = 60_000
        assertNull(after(List(30) { waiting(1_000, started = false) })) // 58 s
        assertEquals(Stall.BUFFERING, watch.check(waiting(1_000, started = false))) // 60 s
    }

    @Test
    fun `a still time and a still first picture are given as long as a start`() {
        assertNull(after(List(10) { playing(5_000, frames = 1) })) // 18 s
        assertEquals(Stall.FROZEN, watch.check(playing(5_000, frames = 1))) // 20 s: it never got going
    }

    @Test
    fun `a still time before the picture has moved is not frozen while new pictures come`() {
        assertNull(after(List(30) { playing(5_000, frames = it.toLong()) }))
    }

    @Test
    fun `it says once when the stream really plays, and again after it starts again`() {
        var said = 0
        watch.onMoving = { said++ }
        after(List(5) { playing(5_000, frames = 1) }) // a first picture held, and the time too
        assertEquals(0, said)
        after(List(5) { playing(10_000L + it * 2_000L, frames = 2L + it * 50) }) // it moves
        assertEquals(1, said)
        watch.reset()
        after(List(3) { playing(5_000L + it * 2_000L, frames = null) }) // no video: the time moves
        assertEquals(2, said)
        watch.reset()
        after(List(3) { playing(5_000L + it * 2_000L, frames = 1) }) // a slide that has not changed yet, with sound
        assertEquals(3, said)
    }

    @Test
    fun `time that does not move while it plays is frozen too, also with no video`() {
        assertNull(after(List(4) { playing(5_000, frames = null) }))
        assertEquals(Stall.FROZEN, watch.check(playing(5_000, frames = null)))
    }

    @Test
    fun `a stream that plays, or is paused, is never stuck`() {
        assertNull(after(List(30) { playing(it * 2_000L) }))
        assertNull(after(List(30) { Sample(false, false, true, 9_000, 4_000, 10) }))
    }

    @Test
    fun `after it says so, it starts again`() {
        assertEquals(Stall.BUFFERING, after(List(7) { waiting(1_000) }))
        assertNull(watch.check(waiting(1_000)))
    }
}
