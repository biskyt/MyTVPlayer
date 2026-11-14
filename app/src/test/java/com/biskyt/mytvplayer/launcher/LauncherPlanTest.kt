package com.biskyt.mytvplayer.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

/** From the cards the launcher has to the ones the app shows now (see plan). */
class LauncherPlanTest {
    private data class Card(val key: String, val watchedAt: Long = 0)

    private fun plan(stored: List<StoredCard>, vararg wanted: Card) = plan(stored, wanted.toList(), { it.key }, { it.watchedAt })

    @Test
    fun `new cards are added, cards still shown are changed, and cards gone are taken off`() {
        val p = plan(listOf(StoredCard(1, "a", false, 5), StoredCard(2, "b", false, 5)), Card("a", 6), Card("c", 7))
        assertEquals(listOf(Card("c", 7)), p.insert)
        assertEquals(listOf(1L to Card("a", 6)), p.update)
        assertEquals(listOf(2L), p.delete)
    }

    @Test
    fun `a card the user took off stays off until it is watched again`() {
        val off = listOf(StoredCard(1, "a", removed = true, watchedAt = 5))
        assertEquals(Plan<Card>(emptyList(), emptyList(), emptyList()), plan(off, Card("a", 5)))
        // Watched again: its old row goes and it comes back.
        assertEquals(Plan(listOf(Card("a", 9)), emptyList(), listOf(1L)), plan(off, Card("a", 9)))
    }

    @Test
    fun `a key stored twice keeps one row`() {
        val p = plan(listOf(StoredCard(1, "a", false, 0), StoredCard(2, "a", false, 0)), Card("a"))
        assertEquals(listOf(1L to Card("a")), p.update)
        assertEquals(listOf(2L), p.delete)
    }
}
