package com.biskyt.mytvplayer.core.net

import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.Credentials
import okhttp3.OkHttpClient

/**
 * How a playlist reaches its provider: through an HTTP proxy, or a SOCKS5 one. [usualPort] is the port taken when none
 * is typed (the port box shows it).
 */
enum class ProxyType(val usualPort: Int) { HTTP(8080), SOCKS(1080) }

/** A playlist's proxy. [username] and [password] are optional: blank when the proxy needs none. */
data class ProxySettings(
    val type: ProxyType,
    val host: String,
    val port: Int,
    val username: String = "",
    val password: String = "",
) {
    /** A host name or IP address alone (no scheme, port or path), and a port from 1 to 65535. */
    val valid: Boolean get() = host.isNotBlank() && host.none { it.isWhitespace() || it == '/' } && ':' !in host && port in PORTS

    private val hasLogin: Boolean get() = username.isNotEmpty()

    /**
     * [base] (its connections, threads and settings), with all its traffic sent through this proxy. A SOCKS5 login is
     * kept by [ProxyClients], not here.
     */
    fun applyTo(base: OkHttpClient): OkHttpClient {
        // Unresolved: the proxy looks the provider's name up, so this device's DNS server does not see it.
        val address = InetSocketAddress.createUnresolved(host.trim(), port)
        val builder = base.newBuilder().proxy(Proxy(if (type == ProxyType.SOCKS) Proxy.Type.SOCKS else Proxy.Type.HTTP, address))
        if (hasLogin) {
            if (type == ProxyType.HTTP) {
                builder.proxyAuthenticator { _, response ->
                    // Once: a refusal of these details is final.
                    if (response.request.header(PROXY_AUTH) != null) {
                        null
                    } else {
                        response.request.newBuilder().header(PROXY_AUTH, Credentials.basic(username, password)).build()
                    }
                }
            }
        }
        return builder.build()
    }

    companion object {
        private val PORTS = 1..65535
        private const val PROXY_AUTH = "Proxy-Authorization"
        private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")

        /**
         * A proxy from what the user typed: a scheme ("http://", "socks5://") and a path are dropped, and "host:port"
         * fills in a [port] left empty. With neither, the type's usual port (the owner, 2026-11-13: the box showed it, but
         * the check said a port was needed). The result can still be invalid ([valid]).
         */
        fun typed(type: ProxyType, host: String, port: String, username: String, password: String): ProxySettings {
            val address = host.trim().replaceFirst(SCHEME, "").substringBefore('/')
            val hostPart = address.substringBeforeLast(':', address)
            // ("host:" with nothing after the colon: no port typed.)
            val typedPort = address.substringAfterLast(':', "").takeIf { hostPart != address && it.isNotEmpty() }
            val portNumber = port.trim().ifEmpty { typedPort ?: type.usualPort.toString() }.toIntOrNull() ?: 0
            return ProxySettings(type, hostPart, portNumber, username.trim(), password)
        }
    }
}

/**
 * The user names and passwords of SOCKS5 proxies, by their address. Java's SOCKS code asks
 * [Authenticator.requestPasswordAuthentication] for them; this answers only for the proxies that the app set up, and
 * hands any other request to nothing. Java cannot tell which client asks, so there is one login for each proxy
 * address: two playlists on one SOCKS5 proxy with different logins both use the last one set up.
 */
internal object SocksLogins : Authenticator() {
    private val logins = ConcurrentHashMap<String, PasswordAuthentication>()

    @Volatile private var installed = false

    fun set(proxy: ProxySettings) {
        val key = key(proxy.host, proxy.port)
        if (proxy.username.isEmpty()) {
            logins.remove(key)
            return
        }
        logins[key] = PasswordAuthentication(proxy.username, proxy.password.toCharArray())
        if (!installed) {
            synchronized(this) {
                if (!installed) {
                    setDefault(this)
                    installed = true
                }
            }
        }
    }

    override fun getPasswordAuthentication(): PasswordAuthentication? = if (requestingProtocol?.startsWith("SOCKS", ignoreCase = true) ==
        true
    ) {
        logins[key(requestingHost.orEmpty(), requestingPort)]
    } else {
        null
    }

    private fun key(host: String, port: Int) = "${host.lowercase()}:$port"
}

/**
 * One client for each proxy, made once, so that each keeps its connections. Without a proxy: [base] itself. Only the
 * last few are kept: one tried with Check proxy, or one changed, does not stay in memory.
 */
class ProxyClients(private val base: OkHttpClient) {
    private val clients = ConcurrentHashMap<ProxySettings, OkHttpClient>()

    fun forProxy(proxy: ProxySettings?): OkHttpClient {
        if (proxy == null || !proxy.valid) return base
        // Java's SOCKS code asks the process-wide Authenticator for the login: OkHttp has no setting for it.
        if (proxy.type == ProxyType.SOCKS) SocksLogins.set(proxy)
        clients[proxy]?.let { return it }
        if (clients.size >= KEEP) clients.clear()
        return clients.getOrPut(proxy) { proxy.applyTo(base) }
    }

    private companion object {
        const val KEEP = 8
    }
}

/** What a proxy check found: the address that the internet sees without the proxy and through it. */
sealed interface ProxyCheck {
    /** The proxy answered, and the internet sees [via], not [direct] (null when that could not be found). */
    data class Working(val via: PublicIp, val direct: PublicIp?) : ProxyCheck

    /** The proxy answered, but the address is the same as without it. */
    data class SameAddress(val ip: PublicIp) : ProxyCheck

    /** The proxy could not be used: [reason] is the error, in English (for the message, after the app's words). */
    data class Failed(val reason: String) : ProxyCheck

    companion object {
        /** Asks for the public address [direct]ly and [via] the proxy, then compares them. */
        suspend fun run(direct: suspend () -> PublicIp, via: suspend () -> PublicIp): ProxyCheck = coroutineScope {
            // Both at once: neither needs the other.
            val without = async {
                try {
                    direct()
                } catch (_: java.io.IOException) {
                    null
                }
            }
            val through = try {
                via()
            } catch (e: java.io.IOException) {
                without.cancel()
                return@coroutineScope Failed(e.message ?: e.javaClass.simpleName)
            }
            val direct = without.await()
            if (direct != null && direct.ip == through.ip) SameAddress(through) else Working(through, direct)
        }
    }
}
