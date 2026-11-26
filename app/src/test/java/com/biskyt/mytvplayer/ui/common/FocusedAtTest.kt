package com.biskyt.mytvplayer.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

/** Which option of a panel takes the focus as it shows again, back from the panel one of them opened (see focusedAt). */
class FocusedAtTest {
    // An episode's options, then the show's, each under a heading (null): two Cast and crew.
    private val episode = listOf("Episode description", "Cast and crew", "Download")
    private val show = listOf("Play trailer", "Cast and crew", "About this show")
    private val labels = listOf(null) + episode + listOf(null) + show

    /** The option [index] of [from], used, then the panel shows [now]. */
    private fun back(from: List<String?>, index: Int, now: List<String?> = from) = focusedAt(now, optionPlace(from, index))

    @Test
    fun `with no option used, the first option`() = assertEquals(1, focusedAt(labels, null))

    @Test
    fun `the option used, of two with the same label`() {
        assertEquals(2, back(labels, 2))
        assertEquals(6, back(labels, 6))
    }

    @Test
    fun `the option used, when options read later came in above it`() {
        // The show's options alone, then the episode's come in above them, with the headings.
        assertEquals(6, back(show, 1, labels))
        assertEquals(7, back(show, 2, labels))
    }

    @Test
    fun `the option used, when options read later came in below it`() {
        // The episode's options alone, then the show's come in below them, with the headings.
        assertEquals(2, back(episode, 1, labels))
    }

    @Test
    fun `the option used, when an option above it is gone`() = assertEquals(5, back(labels, 6, labels.drop(1)))

    @Test
    fun `when the label of the option used changed, the option in its place`() {
        val favourite = listOf("Open", "Add to favourites", "About")
        assertEquals(1, back(favourite, 1, listOf("Open", "Remove from favourites", "About")))
        // And with an option read later above it.
        assertEquals(2, back(favourite, 1, listOf("Trailer", "Open", "Remove from favourites", "About")))
    }

    @Test
    fun `an option alone under a heading, renamed, is found by its place, not by another alone under one`() =
        assertEquals(5, back(listOf(null, "A", null, "B", null, "C"), 5, listOf(null, "A", null, "B", null, "C2")))

    @Test
    fun `the option used, by its label, though its neighbours changed`() =
        assertEquals(3, back(listOf("Open", "X", "About"), 1, listOf("Open", "Y", "About", "X")))

    @Test
    fun `when the one used is gone, the option now in its place, else the first`() {
        assertEquals(2, back(labels, 2, labels.take(2) + "Other"))
        assertEquals(1, back(labels, 7, labels.take(3)))
    }
}
