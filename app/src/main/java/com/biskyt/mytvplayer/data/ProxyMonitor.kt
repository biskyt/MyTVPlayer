package com.biskyt.mytvplayer.data

import com.biskyt.mytvplayer.core.net.ProxyCheck
import com.biskyt.mytvplayer.core.net.ProxySettings
import com.biskyt.mytvplayer.data.ExternalIp.Companion.RETRY_MS
import com.biskyt.mytvplayer.data.ExternalIp.Companion.SETTLE_MS
import com.biskyt.mytvplayer.data.ExternalIp.Companion.STOP_AFTER_MS
import com.biskyt.mytvplayer.data.ExternalIp.Companion.TRIES
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

/**
 * Whether the active playlist's proxy works (setting "Warn if the proxy is not working", the owner, 2026-11-13): the
 * address that the internet sees through it, compared with the one without it ([check], ipinfo.io). It is checked only
 * while it shows: again when the [network] or the [proxy] changes, and when the last answer is old.
 */
class ProxyMonitor(
    network: Flow<NetworkState>,
    proxy: Flow<ProxySettings?>,
    private val check: suspend (ProxySettings) -> ProxyCheck,
    scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Answer(val network: NetworkState, val proxy: ProxySettings, val result: ProxyCheck, val at: Long) {
        /** When it is old. A failure is kept for a shorter time, so a proxy that comes back is seen sooner. */
        val until = at + if (result is ProxyCheck.Failed) KEEP_FAILED_MS else KEEP_MS
    }

    @Volatile private var last: Answer? = null

    /** Null while there is nothing to show: no proxy, no network, or a first check that is not done yet. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val status: StateFlow<ProxyCheck?> =
        // Each change clears the old answer at once, and a newer change cancels the wait or the check for the last one.
        combine(network, proxy) { n, p -> n to p }.distinctUntilChanged().flatMapLatest { (n, p) ->
            flow {
                if (p == null || n.id == null) return@flow emit(null)
                // The same network and proxy as a short time ago (the top bar is back after playback): its answer stands.
                var answer = last?.takeIf { it.network == n && it.proxy == p && clock() < it.until }
                if (answer == null) {
                    emit(null)
                    delay(SETTLE_MS)
                }
                // While it shows, checked again once the answer is old; the old answer shows until the new one comes.
                while (true) {
                    val current = answer ?: Answer(n, p, checkWithRetries(p), clock()).also { last = it }
                    emit(current.result)
                    delay(current.until - clock())
                    answer = null
                }
            }
            // Shown again after a stop: nothing until the new answer, not the last one (perhaps for another proxy).
        }.stateIn(scope, SharingStarted.WhileSubscribed(STOP_AFTER_MS, replayExpirationMillis = 0), null)

    /** Just after a change, the new network may not carry traffic yet: a failure is tried again a few times. */
    private suspend fun checkWithRetries(p: ProxySettings): ProxyCheck {
        repeat(TRIES - 1) {
            checkOnce(p).takeIf { it !is ProxyCheck.Failed }?.let { return it }
            delay(RETRY_MS)
        }
        return checkOnce(p)
    }

    private suspend fun checkOnce(p: ProxySettings): ProxyCheck = try {
        check(p)
    } catch (e: CancellationException) {
        throw e
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        ProxyCheck.Failed(e.message ?: e.javaClass.simpleName)
    }

    private companion object {
        /** How long an answer stands: a proxy that stops working shows within this time of the status showing again. */
        const val KEEP_MS = 10 * 60_000L
        const val KEEP_FAILED_MS = 2 * 60_000L
    }
}
