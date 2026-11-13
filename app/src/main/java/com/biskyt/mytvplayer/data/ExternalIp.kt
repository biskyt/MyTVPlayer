package com.biskyt.mytvplayer.data

import com.biskyt.mytvplayer.core.net.PublicIp
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

/**
 * The public IP address that the internet sees for this TV (setting "Show your external IP address", next to the VPN
 * badge). It is asked for ([lookup], ipinfo.io) only while it shows, and again only when the [network] changes: a
 * new network, or a VPN that starts or stops.
 */
class ExternalIp(
    private val network: Flow<NetworkState>,
    private val lookup: suspend () -> PublicIp,
    private val scope: CoroutineScope,
) {
    /** Null while it is not known: no network, a lookup that is not done yet, or one that failed. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val current: StateFlow<PublicIp?> by lazy {
        // Each change clears the old address at once (it may be the one without the VPN), and a newer change cancels
        // the wait or the lookup for the last one.
        network.flatMapLatest { n ->
            flow {
                // The same network as last time (the top bar is back after playback): its answer stands.
                last?.takeIf { it.first == n }?.let { return@flow emit(it.second) }
                emit(null)
                if (n.id != null) {
                    delay(SETTLE_MS)
                    val ip = lookupWithRetries()
                    if (ip != null) last = n to ip
                    emit(ip)
                }
            }
        }.stateIn(scope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), null)
    }

    /** The last answer, and the network it is for. */
    @Volatile private var last: Pair<NetworkState, PublicIp>? = null

    /** Just after a change, the new network may not carry traffic yet: a few tries. Never a crash: only the badge shows it. */
    private suspend fun lookupWithRetries(): PublicIp? {
        repeat(TRIES) { attempt ->
            try {
                return lookup()
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
                if (attempt < TRIES - 1) delay(RETRY_MS)
            }
        }
        return null
    }

    /** Also the proxy check's timing (see ProxyMonitor). */
    internal companion object {
        /** A VPN that starts makes several changes at once: wait for them to settle. */
        const val SETTLE_MS = 1_500L
        const val TRIES = 3
        const val RETRY_MS = 5_000L
        const val STOP_AFTER_MS = 5_000L
    }
}
