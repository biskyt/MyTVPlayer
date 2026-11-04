package com.biskyt.mytvplayer.core.net

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/** The address that the internet sees for this device, and its country code (e.g. "NL"), when the service gives one. */
@Serializable
data class PublicIp(val ip: String, val country: String? = null)

/**
 * Asks ipinfo.io for the public IP address that the requests of [http] come from. With a client that goes through a
 * proxy, that is the proxy's address. No key is needed for this light use.
 */
class IpLookup(private val http: OkHttpClient, private val url: String = "https://ipinfo.io/json") {
    private val json = Json { ignoreUnknownKeys = true }

    /** Throws an [IOException] when the service cannot be reached or answers something else. */
    suspend fun lookup(): PublicIp = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("ipinfo.io answered ${r.code}")
            try {
                json.decodeFromString(PublicIp.serializer(), r.body?.string().orEmpty())
            } catch (e: SerializationException) {
                throw IOException("Unexpected answer from ipinfo.io", e)
            }
        }
    }
}
