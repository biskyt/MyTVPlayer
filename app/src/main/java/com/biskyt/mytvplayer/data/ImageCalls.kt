package com.biskyt.mytvplayer.data

import com.biskyt.mytvplayer.core.net.ProxyClients
import com.biskyt.mytvplayer.core.net.ProxySettings
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Request

/**
 * Where images (logos, posters) are fetched from: through the active playlist's proxy, as the rest of its traffic is,
 * and directly without one (the owner, 2026-11-12). Each screen shows the active playlist, so its images are that
 * playlist's.
 */
class ImageCalls(scope: CoroutineScope, activeProxy: Flow<ProxySettings?>, proxies: ProxyClients) : Call.Factory {
    /** The client for the active playlist's proxy (or none): null until the active playlist is known. */
    private val client = activeProxy.map { proxies.forProxy(it) }.stateIn(scope, SharingStarted.Eagerly, null)

    // Called on the image loader's own threads, so it can wait. At start-up an image waits until the active playlist
    // is known: none goes out directly when the playlist has a proxy. Not known in time, the image fails (and is asked
    // for again when next shown), so a thread is not held for ever.
    override fun newCall(request: Request): Call {
        val client = client.value
            ?: runBlocking { withTimeoutOrNull(WAIT_MS) { client.filterNotNull().first() } }
            ?: throw IOException("The active playlist is not known yet")
        return client.newCall(request)
    }

    private companion object {
        const val WAIT_MS = 10_000L
    }
}
