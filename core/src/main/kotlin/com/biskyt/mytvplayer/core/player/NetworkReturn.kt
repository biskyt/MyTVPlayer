package com.biskyt.mytvplayer.core.player

/**
 * Tells when the device's network comes back: when a network becomes able to reach the internet ("validated"). Android
 * reports a network's state again and again while nothing changes (a new signal strength, every few seconds on some
 * Wi-Fi), so only the change from not validated to validated counts (MyPlexPlayer's review, 2026-11-23: each report
 * made a film try again at once and used up its few tries). [N] is the network's type (Android's Network).
 */
class NetworkReturn<N> {
    /** The network last found validated, or null. Android's callbacks come on another thread. */
    @Volatile private var validated: N? = null

    /** Takes [network]'s state: true when it has just become validated. */
    fun changed(network: N, isValidated: Boolean): Boolean {
        if (!isValidated) {
            if (validated == network) validated = null
            return false
        }
        if (validated == network) return false
        validated = network
        return true
    }

    /** [network] is gone. */
    fun lost(network: N) {
        if (validated == network) validated = null
    }

    /** Forgets every network (no longer told about them). */
    fun clear() {
        validated = null
    }
}
