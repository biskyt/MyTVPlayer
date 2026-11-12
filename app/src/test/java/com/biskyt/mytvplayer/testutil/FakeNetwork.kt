package com.biskyt.mytvplayer.testutil

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities

/** What Android tells the app as the default network changes: a network with or without a VPN, or ([vpn] null) none. */
fun ConnectivityManager.reportNetwork(vpn: Boolean?) {
    val network = ShadowNetwork.newInstance(1)
    shadowOf(this).networkCallbacks.forEach { callback ->
        if (vpn == null) {
            callback.onLost(network)
        } else {
            val caps = ShadowNetworkCapabilities.newInstance()
            if (vpn) shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            callback.onCapabilitiesChanged(network, caps)
        }
    }
}
