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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** The default network as Android reports it: which one ([id], null when there is none), and if it goes through a VPN. */
data class NetworkState(val id: Long?, val vpn: Boolean)

/**
 * Whether the app's traffic goes through a VPN, as Android reports it (the default network's transport). A VPN on
 * the router, or on another device, can't be seen. Android tells of each change: nothing is checked over and over,
 * and only while something shows it (the top bar, with the setting on).
 */
class VpnMonitor(context: Context, scope: CoroutineScope) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    /** Made when first used, starting from the state now. A new network or a VPN that starts or stops changes it. */
    val network: StateFlow<NetworkState> by lazy {
        callbackFlow {
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    trySend(NetworkState(network.networkHandle, caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)))
                }

                override fun onLost(network: Network) {
                    trySend(NetworkState(null, false))
                }
            }
            trySend(now())
            connectivity.registerDefaultNetworkCallback(callback)
            awaitClose { connectivity.unregisterNetworkCallback(callback) }
        }.stateIn(scope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), now())
    }

    /** Made when first shown (the setting on), starting from the state now, so it doesn't show "not connected" first. */
    val connected: StateFlow<Boolean> by lazy {
        network.map { it.vpn }.stateIn(scope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), network.value.vpn)
    }

    private fun now(): NetworkState {
        val active = connectivity.activeNetwork
        val vpn = connectivity.getNetworkCapabilities(active)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        return NetworkState(active?.networkHandle, vpn)
    }

    private companion object {
        const val STOP_AFTER_MS = 5_000L
    }
}
