package com.biskyt.mytvplayer.core.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkReturnTest {
    private val network = NetworkReturn<String>()

    @Test
    fun `a network counts once when it becomes validated, not at each report after`() {
        assertFalse(network.changed("wifi", isValidated = false)) // connected, not yet on the internet
        assertTrue(network.changed("wifi", isValidated = true))
        assertFalse(network.changed("wifi", isValidated = true)) // a new signal strength
        assertFalse(network.changed("wifi", isValidated = true))
    }

    @Test
    fun `it counts again after the network lost the internet, or was lost`() {
        assertTrue(network.changed("wifi", isValidated = true))
        assertFalse(network.changed("wifi", isValidated = false))
        assertTrue(network.changed("wifi", isValidated = true))
        network.lost("wifi")
        assertTrue(network.changed("wifi", isValidated = true))
        network.clear() // no longer listening: the state on listening again is a change
        assertTrue(network.changed("wifi", isValidated = true))
    }

    @Test
    fun `another network that becomes validated counts`() {
        assertTrue(network.changed("wifi", isValidated = true))
        assertTrue(network.changed("ethernet", isValidated = true))
        network.lost("wifi") // not the one last validated: nothing changes
        assertFalse(network.changed("ethernet", isValidated = true))
    }
}
