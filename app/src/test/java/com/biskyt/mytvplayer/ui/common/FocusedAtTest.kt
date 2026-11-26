package com.biskyt.mytvplayer.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

/** Which option of a panel takes the focus as it shows again, back from the panel one of them opened (see focusedAt). */
class FocusedAtTest {
    // A heading (null), then the options, as an episode's options with two Cast and crew.
    private val labels = listOf(null, "Episode description", "Cast and crew", "Add to favourites", null, "Cast and crew", "About")

    @Test
    fun `with no option used, the first option`() = assertEquals(1, focusedAt(labels, null))

    @Test
    fun `the option used, by its place, of two with the same label`() {
        assertEquals(2, focusedAt(labels, OptionPlace(2, "Cast and crew")))
        assertEquals(5, focusedAt(labels, OptionPlace(5, "Cast and crew")))
    }

    @Test
    fun `the option used, by its label, when an option read later came in above it`() =
        assertEquals(6, focusedAt(labels, OptionPlace(5, "About")))

    @Test
    fun `the option used, by its place, when its own label changed`() = assertEquals(
        3,
        focusedAt(
            labels.map {
                if (it ==
                    "Add to favourites"
                ) {
                    "Remove from favourites"
                } else {
                    it
                }
            },
            OptionPlace(3, "Add to favourites")
        )
    )

    @Test
    fun `the first option, when the one used is gone`() = assertEquals(1, focusedAt(labels.take(3), OptionPlace(6, "About")))
}
