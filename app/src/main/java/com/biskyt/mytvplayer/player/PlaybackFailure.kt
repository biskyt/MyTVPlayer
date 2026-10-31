package com.biskyt.mytvplayer.player

import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.appString
import java.net.UnknownHostException

/**
 * What went wrong ([message]: what happened and what to do) and, when known, why ([reason]). [address] is the address of
 * the stream that failed, when there was one. It is shown so that the viewer can report it.
 */
data class PlaybackError(val message: String, val reason: String? = null, val address: String? = null)

/** "Stream from lb.example.net.", naming where a stream comes from (lenient, like the player, about odd addresses). */
fun streamSource(url: String?): String? = url?.let { android.net.Uri.parse(it).host }?.let { appString(R.string.player_stream_source, it) }

/** A stream that buffered for a long while without getting any further (see PlaybackEngine). */
const val ERROR_CODE_STALLED = PlaybackException.CUSTOM_ERROR_CODE_BASE

/**
 * Why a stream did not play ([e], when the player said). Without [e], where it comes from. The error shows the full
 * address too (see [displayAddress]), so the server is not named twice.
 */
fun failureReason(e: PlaybackException?, url: String?, sentNothing: Boolean = false): String? =
    e?.let { explainFailure(it, sentNothing) } ?: streamSource(url)

/**
 * A stream's address to show: Xtream addresses carry the account's user name and password ("/movie/user/pass/501.mkv",
 * "?username=…&password=…"). These are hidden, so that a photo of the screen does not share them.
 */
fun displayAddress(url: String): String = url
    .replace(xtreamLogin, "/$1/•••/•••/")
    .replace(shortXtreamLogin, "$1/•••/•••/$2")
    .replace(loginQuery, "$1=•••")

private val xtreamLogin = Regex("""/(live|movie|series|timeshift)/[^/?#]+/[^/?#]+/""")

/** The short form of a live address in Xtream M3U playlists: "http://host:port/user/pass/123.ts". */
private val shortXtreamLogin = Regex("""^(https?://[^/]+)/[^/?#]+/[^/?#]+/(\d+(?:\.\w+)?)(?=$|[?#])""")
private val loginQuery = Regex("""\b(username|password)=[^&#]*""")

/**
 * Why a stream didn't play, in words for the viewer: whether the stream itself is unavailable (its server refused,
 * it's gone, it didn't answer) or this TV box can't play what it sent (its format, its encryption).
 */
@OptIn(UnstableApi::class)
fun explainFailure(e: PlaybackException, sentNothing: Boolean = false): String {
    val http = e.causes().firstNotNullOfOrNull { it as? HttpDataSource.InvalidResponseCodeException }
    return when {
        http != null -> httpReason(http.responseCode)
        e.causes().any { it is UnknownHostException } -> appString(R.string.player_error_dns)
        e.errorCode == ERROR_CODE_STALLED -> appString(R.string.player_error_stalled)
        e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> appString(R.string.player_error_timeout)
        e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> appString(R.string.player_error_connection)
        e.errorCode == PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE -> appString(R.string.player_error_not_video)
        e.errorCode in DRM -> appString(R.string.player_error_drm)
        e.errorCode in AUDIO_OUTPUT -> appString(R.string.player_error_audio_output)
        e.errorCode in DECODING -> decodingReason(e)
        // An empty reply is not a format problem: the server has no copy of the stream to send.
        e.errorCode in PARSING -> appString(if (sentNothing) R.string.player_error_empty else R.string.player_error_parsing)
        e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> appString(R.string.player_error_behind_live)
        e.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> appString(R.string.player_error_io)
        // The player's own name for the error: technical, so it isn't translated.
        else -> appString(R.string.player_error_other, e.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' '))
    }
}

private fun httpReason(code: Int) = when (code) {
    401, 403 -> appString(R.string.player_error_http_refused, code)
    404, 410 -> appString(R.string.player_error_http_gone, code)
    in 500..599 -> appString(R.string.player_error_http_server, code)
    else -> appString(R.string.player_error_http_other, code)
}

/** That this TV box can't decode the stream's video, audio or other media, naming its format (e.g. video/hevc) when the player says. */
@OptIn(UnstableApi::class)
private fun decodingReason(e: PlaybackException): String {
    val mime = e.causes().firstNotNullOfOrNull { (it as? MediaCodecRenderer.DecoderInitializationException)?.mimeType }
        ?: (e as? ExoPlaybackException)?.takeIf { it.type == ExoPlaybackException.TYPE_RENDERER }?.rendererFormat?.sampleMimeType
        ?: return appString(R.string.player_error_decode)
    val sentence = when (mime.substringBefore('/')) {
        "video" -> R.string.player_error_decode_video
        "audio" -> R.string.player_error_decode_audio
        else -> R.string.player_error_decode_media
    }
    return appString(sentence, mime)
}

private fun Throwable.causes(): Sequence<Throwable> = generateSequence(this) { it.cause }.take(MAX_CAUSES)

private const val MAX_CAUSES = 10

private val DRM = PlaybackException.ERROR_CODE_DRM_UNSPECIFIED..PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED

private val AUDIO_OUTPUT = setOf(
    PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
    PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
    PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED,
    PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED
)

private val DECODING = setOf(
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
)

private val PARSING = setOf(
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED
)
