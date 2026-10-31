package com.biskyt.mytvplayer.ui.player

import org.junit.Assert.assertArrayEquals
import org.junit.Test

/** Where the player's button groups go: play/pause in the middle when there's room, never one group over another. */
class ControlsLayoutTest {
    @Test
    fun `play pause is in the middle when there's room`() {
        // 1000 wide; the skips (100, play/pause their middle) centred at 450..550; Stop and the right buttons after.
        assertArrayEquals(intArrayOf(0, 450, 560, 800), controlsX(1000, gap = 10, listOf(200, 100, 40, 200)))
    }

    @Test
    fun `wide left buttons push the play controls right, as little as needed`() {
        assertArrayEquals(intArrayOf(0, 510, 620, 800), controlsX(1000, gap = 10, listOf(500, 100, 40, 200)))
    }

    @Test
    fun `wide right buttons push the play controls left, as little as needed`() {
        assertArrayEquals(intArrayOf(0, 290, 400, 450), controlsX(1000, gap = 10, listOf(100, 100, 40, 550)))
    }

    @Test
    fun `with no room, the groups go one after another, never over each other`() {
        // The right buttons start after Stop and run off the edge, as a row's last ones are cut off.
        assertArrayEquals(intArrayOf(0, 510, 620, 670), controlsX(1000, gap = 10, listOf(500, 100, 40, 500)))
    }
}
