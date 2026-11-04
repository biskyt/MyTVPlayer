package com.biskyt.mytvplayer.core.sync

import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * A failure of the sync. [message] is in English, for logs. [reason] lets the app tell the user in their language.
 */
class SyncException(
    message: String,
    cause: Throwable? = null,
    val reason: Reason = Reason.OTHER,
    /** Google's own error code when Google refused (e.g. `invalid_scope`), else the HTTP status: shown to help find the cause. */
    val code: String? = null,
) : IOException(message, cause) {
    enum class Reason {
        /** The user said no on the sign-in page. */
        DENIED,

        /** The code was not used in time. */
        EXPIRED,

        /** Google no longer accepts the sign-in (the user removed the app's access, for example). */
        SIGNED_OUT,
        OTHER,
    }
}

/** [text] as [serializer]'s type; a [SyncException] if [from] answered something else. */
internal fun <T> Json.decodeOrFail(serializer: KSerializer<T>, text: String, from: String): T = try {
    decodeFromString(serializer, text)
} catch (e: SerializationException) {
    throw SyncException("Unexpected answer from $from", e)
}

/**
 * What the TV shows: the user goes to [verificationUrl] on a phone or a computer and types [userCode], or scans a QR
 * code of [urlWithCode], which opens the page with the code filled in.
 */
@Serializable
data class DeviceCode(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_url") val verificationUrl: String,
    @SerialName("expires_in") val expiresInSec: Int,
    @SerialName("interval") val intervalSec: Int = 5,
) {
    /** Google's page with the code filled in, for a QR code: the user only confirms it. A bad address stays as it is. */
    val urlWithCode: String
        get() = verificationUrl.toHttpUrlOrNull()?.newBuilder()?.setQueryParameter("user_code", userCode)?.build()?.toString()
            ?: verificationUrl
}

/** [email] is the Google account's address, when Google sends it. [refreshToken] comes only with the first sign-in. */
data class GoogleTokens(val accessToken: String, val expiresAt: Long, val refreshToken: String?, val email: String?)

/**
 * Google's sign-in for TVs (the OAuth device flow). It asks only for the app's own hidden folder in the user's Drive
 * (`drive.appdata`) and the account's email address. It needs no Google Play services, so it works on any TV.
 */
class GoogleAuth(
    private val http: OkHttpClient,
    private val clientId: String,
    private val clientSecret: String,
    private val base: String = "https://oauth2.googleapis.com",
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Asks Google for a code to show on the TV. */
    suspend fun start(): DeviceCode {
        val answer = post("device/code", "scope" to SCOPES)
        answer.error?.let { throw SyncException("Google refused the sign-in: $it", code = it) }
        return json.decodeOrFail(DeviceCode.serializer(), answer.body, "Google")
    }

    /** Waits until the user signs in with [code]. It asks Google again at the interval Google gives. */
    suspend fun await(code: DeviceCode): GoogleTokens {
        var interval = code.intervalSec
        val deadline = clock() + code.expiresInSec * MS
        while (clock() < deadline) {
            delay(interval * MS)
            val answer = post("token", "device_code" to code.deviceCode, "grant_type" to DEVICE_GRANT)
            when (answer.error) {
                null -> return tokens(answer.body, null)
                "authorization_pending" -> Unit
                "slow_down" -> interval += SLOW_DOWN_SEC
                "access_denied" -> throw SyncException("Sign-in refused", reason = SyncException.Reason.DENIED)
                "expired_token" -> break
                else -> throw SyncException("Sign-in failed: ${answer.error}", code = answer.error)
            }
        }
        throw SyncException("The sign-in code expired", reason = SyncException.Reason.EXPIRED)
    }

    /** A new access token for [refreshToken]. */
    suspend fun refresh(refreshToken: String): GoogleTokens {
        val answer = post("token", "refresh_token" to refreshToken, "grant_type" to "refresh_token")
        return when (answer.error) {
            null -> tokens(answer.body, refreshToken)
            "invalid_grant" -> throw SyncException("Google signed this TV out", reason = SyncException.Reason.SIGNED_OUT)
            else -> throw SyncException("Token refresh failed: ${answer.error}", code = answer.error)
        }
    }

    private class Answer(val body: String, val error: String?)

    private suspend fun post(path: String, vararg fields: Pair<String, String>): Answer = withContext(Dispatchers.IO) {
        val form = FormBody.Builder().add("client_id", clientId).add("client_secret", clientSecret)
        fields.forEach { (k, v) -> form.add(k, v) }
        val request = Request.Builder().url("$base/$path").post(form.build()).build()
        try {
            http.newCall(request).execute().use { r ->
                val body = r.body?.string().orEmpty()
                // Google answers a pending sign-in with an error status (428) and a JSON error code.
                val error = if (r.isSuccessful) {
                    null
                } else {
                    errorCode(body)
                        ?: throw SyncException("Google answered ${r.code}", code = "HTTP ${r.code}")
                }
                Answer(body, error)
            }
        } catch (e: SyncException) {
            throw e
        } catch (e: IOException) {
            throw SyncException("Couldn't reach Google: ${e.message}", e)
        }
    }

    private fun errorCode(body: String): String? = try {
        json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.content
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun tokens(body: String, refreshToken: String?): GoogleTokens {
        val t = json.decodeOrFail(TokenAnswer.serializer(), body, "Google")
        return GoogleTokens(t.accessToken, clock() + t.expiresInSec * MS, t.refreshToken ?: refreshToken, t.idToken?.let(::email))
    }

    @Serializable
    private data class TokenAnswer(
        @SerialName("access_token") val accessToken: String,
        @SerialName("expires_in") val expiresInSec: Long,
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("id_token") val idToken: String? = null,
    )

    companion object {
        /**
         * Google allows only some permissions for a TV sign-in. It refuses `drive.appdata` (the hidden app folder), and
         * accepts `drive.file`: only the files the app made itself (checked against Google on 2026-11-04).
         */
        const val SCOPES = "openid email https://www.googleapis.com/auth/drive.file"
        private const val DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"
        private const val SLOW_DOWN_SEC = 5
        private const val MS = 1000L

        /** The email address in an ID token (a JWT). It came straight from Google over HTTPS, so it is not checked. */
        internal fun email(idToken: String): String? = try {
            val payload = String(Base64.getUrlDecoder().decode(idToken.split('.')[1]))
            Json.parseToJsonElement(payload).jsonObject["email"]?.jsonPrimitive?.content
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IndexOutOfBoundsException) {
            null
        }
    }
}
