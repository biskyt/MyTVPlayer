package com.biskyt.mytvplayer.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.stateIn

/**
 * Whether the app's traffic goes through a VPN, as Android reports it (the default network's transport). A VPN on
 * the router, or on another device, can't be seen. Android tells of each change: nothing is checked over and over,
 * and only while something shows it (the top bar, with the setting on).
 */
class VpnMonitor(context: Context, scope: CoroutineScope) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    /** Made when first shown (the setting on), starting from the state now, so it doesn't show "not connected" first. */
    val connected: StateFlow<Boolean> by lazy {
        callbackFlow {
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    trySend(caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
                }

                override fun onLost(network: Network) {
                    trySend(false)
                }
            }
            trySend(isConnected())
            connectivity.registerDefaultNetworkCallback(callback)
            awaitClose { connectivity.unregisterNetworkCallback(callback) }
        }.stateIn(scope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), isConnected())
    }

    private fun isConnected() =
        connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true

    private companion object {
        const val STOP_AFTER_MS = 5_000L
    }
}
