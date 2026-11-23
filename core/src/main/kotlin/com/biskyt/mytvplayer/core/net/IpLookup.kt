package com.biskyt.mytvplayer.core.net

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/** The address that the internet sees for this device, and its country code (e.g. "NL"), when the service gives one. */
@Serializable
data class PublicIp(val ip: String, val country: String? = null)

/**
 * ipinfo.io answered 429 (too many requests from this address for now), or is left alone after one, and the other
 * services answered with errors. The services answered, so the network (or the proxy it went through) works.
 */
class IpLookupLimited(message: String) : IOException(message)

/**
 * Asks for the public IP address that the requests of [http] come from: ipinfo.io first, then, when it fails or is
 * busy, ipwho.is, then api.ipify.org (the address only). With a client that goes through a proxy, that is the proxy's
 * address. No key is needed for this light use.
 */
class IpLookup(
    private val http: OkHttpClient,
    private val services: List<Service> = SERVICES,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** A service: its address, and how to read its answer. */
    class Service(val name: String, val url: String, val read: (String) -> PublicIp?)

    /**
     * Throws an [IOException] when no service can be reached or answers. It is [IpLookupLimited] when ipinfo.io is
     * busy and the others answered too, with an error: the network works. A service that could not be reached (no
     * network yet, after a change) is the failure, so that the caller tries again.
     */
    suspend fun lookup(): PublicIp = withContext(Dispatchers.IO) {
        var unreached: IOException? = null
        var answered: IOException? = null
        var limited: String? = null
        for (s in services) {
            // A service that said "too many requests" is left alone for a while, not asked again at each check.
            if (clock() < (resting[s.name] ?: 0)) {
                limited = limited ?: s.name
                continue
            }
            try {
                return@withContext ask(s)
            } catch (_: IpLookupLimited) {
                resting[s.name] = clock() + REST_MS
                limited = limited ?: s.name
            } catch (e: Answered) {
                // It answered, but not with an address: the network works.
                answered = answered ?: e
            } catch (e: IOException) {
                unreached = unreached ?: e
            }
        }
        throw unreached
            ?: limited?.let { IpLookupLimited("$it answered 429 (too many requests)") }
            ?: answered
            ?: IOException("No IP address service")
    }

    /** A service answered, but with an error or something that is not an address. */
    private class Answered(message: String) : IOException(message)

    private fun ask(s: Service): PublicIp {
        val body = http.newCall(Request.Builder().url(s.url).header("Accept", "application/json").build()).execute().use { r ->
            if (r.code == TOO_MANY) throw IpLookupLimited("${s.name} answered 429")
            if (!r.isSuccessful) throw Answered("${s.name} answered ${r.code}")
            r.body?.string().orEmpty()
        }
        return read(s, body) ?: throw Answered("Unexpected answer from ${s.name}")
    }

    /** [s]'s answer [body] read; null when it is not what the service sends. */
    private fun read(s: Service, body: String): PublicIp? = try {
        s.read(body)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    @Serializable
    private class IpWho(val ip: String = "", val success: Boolean = false, @SerialName("country_code") val country: String? = null)

    companion object {
        private const val TOO_MANY = 429

        /** How long a busy service is left alone: ipinfo.io's limit is per day, so an hour keeps from asking it in vain. */
        private const val REST_MS = 60 * 60_000L

        /** Busy services, until when: kept for the app as a whole (the badge and the proxy check share the limit). */
        private val resting = java.util.concurrent.ConcurrentHashMap<String, Long>()

        private val json = Json { ignoreUnknownKeys = true }

        val SERVICES = listOf(
            Service("ipinfo.io", "https://ipinfo.io/json") { json.decodeFromString(PublicIp.serializer(), it) },
            Service("ipwho.is", "https://ipwho.is/") { body ->
                json.decodeFromString(IpWho.serializer(), body).takeIf {
                    it.success && it.ip.isNotBlank()
                }?.let { PublicIp(it.ip, it.country) }
            },
            Service("api.ipify.org", "https://api.ipify.org?format=json") { json.decodeFromString(PublicIp.serializer(), it) },
        )

        /** Forgets which services are busy (for tests). */
        fun forgetResting() = resting.clear()
    }
}
