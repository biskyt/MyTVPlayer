package com.biskyt.mytvplayer.core.xtream

import com.biskyt.mytvplayer.core.net.ServerReply
import com.biskyt.mytvplayer.core.xtream.XtreamException.Reason
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeToSequence
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * A failure talking to an Xtream server. [message] is in English, for logs; [reason] (with [code], or for
 * [Reason.UNREACHABLE] the cause's message) lets the app tell the user in their language.
 */
class XtreamException(
    message: String,
    cause: Throwable? = null,
    val reason: Reason = Reason.OTHER,
    /** The server's HTTP status, for [Reason.SERVER_ERROR]. */
    val code: Int = 0,
    /** What the server said with that status, when it said something. */
    val reply: ServerReply? = null,
) : IOException(message, cause) {
    enum class Reason {
        LOGIN_FAILED,
        NO_MOVIE_DETAILS,
        NO_EPISODES,
        UNREACHABLE,
        SERVER_ERROR,
        EMPTY_RESPONSE,
        UNEXPECTED_RESPONSE,
        INVALID_ADDRESS,

        /** Raised outside this client, with a message already meant for the user. */
        OTHER,
    }
}

/**
 * Minimal Xtream Codes API client. Large responses (the live stream list can hold tens of thousands of entries)
 * are decoded as a stream, one element at a time, so the full list is never held in memory.
 */
class XtreamClient(private val http: OkHttpClient, private val credentials: XtreamCredentials) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun account(): XtreamAccount = withContext(Dispatchers.IO) {
        get(apiUrl()) { json.decodeFromStream(it).toAccount() }
            ?: throw XtreamException("Login failed: check the server address, username and password", reason = Reason.LOGIN_FAILED)
    }

    suspend fun liveCategories(): List<XtreamCategory> = categories("get_live_categories")

    suspend fun movieCategories(): List<XtreamCategory> = categories("get_vod_categories")

    suspend fun seriesCategories(): List<XtreamCategory> = categories("get_series_categories")

    private suspend fun categories(action: String): List<XtreamCategory> = withContext(Dispatchers.IO) {
        get(apiUrl(action)) { input -> json.decodeArray(input).mapNotNull { it.toCategory() }.toList() }
    }

    /**
     * Streams live channels to [onStream]. Pass [categoryId] to fetch a single category.
     * Returns the number of channels read.
     */
    suspend fun liveStreams(categoryId: String? = null, onStream: suspend (XtreamLiveStream) -> Unit): Int =
        list("get_live_streams", categoryId, { it.toLiveStream() }, onStream)

    /** Streams the movie list to [onMovie], like [liveStreams]. */
    suspend fun movies(categoryId: String? = null, onMovie: suspend (XtreamMovie) -> Unit): Int =
        list("get_vod_streams", categoryId, { it.toMovie() }, onMovie)

    /** Streams the series list to [onSeries], like [liveStreams]. */
    suspend fun series(categoryId: String? = null, onSeries: suspend (XtreamSeries) -> Unit): Int =
        list("get_series", categoryId, { it.toSeries() }, onSeries)

    private suspend fun <T> list(action: String, categoryId: String?, parse: (JsonElement) -> T?, onItem: suspend (T) -> Unit): Int =
        withContext(Dispatchers.IO) {
            val url = apiUrl(action) { if (categoryId != null) addQueryParameter("category_id", categoryId) }
            get(url) { input ->
                var count = 0
                json.decodeArray(input).forEach { el ->
                    parse(el)?.let {
                        onItem(it)
                        count++
                    }
                }
                count
            }
        }

    suspend fun movieDetails(streamId: Long): VodDetails = withContext(Dispatchers.IO) {
        get(apiUrl("get_vod_info") { addQueryParameter("vod_id", streamId.toString()) }) { json.decodeFromStream(it).toMovieDetails() }
            ?: throw XtreamException("No details for this movie", reason = Reason.NO_MOVIE_DETAILS)
    }

    suspend fun seriesDetails(seriesId: Long): SeriesDetails = withContext(Dispatchers.IO) {
        get(apiUrl("get_series_info") { addQueryParameter("series_id", seriesId.toString()) }) {
            json.decodeFromStream(it).toSeriesDetails()
        }
            ?: throw XtreamException("No episodes found for this series", reason = Reason.NO_EPISODES)
    }

    /** A channel's catch-up listing (past days and the coming hours), fetched when catch-up is opened. */
    suspend fun catchupListing(streamId: Long): CatchupListing = withContext(Dispatchers.IO) {
        get(apiUrl("get_simple_data_table") { addQueryParameter("stream_id", streamId.toString()) }) {
            json.decodeFromStream(it).toCatchupListing()
        }
    }

    /**
     * The channel as it was from [startMs], for [durationMin] minutes (to the present, so playback runs on from one
     * programme into the next). [listing] gives the provider's clock.
     */
    fun catchupUrl(streamId: Long, startMs: Long, listing: CatchupListing, durationMin: Int): String =
        "${credentials.base}/timeshift/${credentials.username.enc()}/${credentials.password.enc()}/" +
            "$durationMin/${timeshiftStart(startMs, listing.serverOffsetAt(startMs))}/$streamId.ts"

    fun liveStreamUrl(streamId: Long, extension: String = "ts"): String = streamUrl("live", streamId, extension)

    fun movieUrl(streamId: Long, extension: String): String = streamUrl("movie", streamId, extension)

    fun episodeUrl(episodeId: Long, extension: String): String = streamUrl("series", episodeId, extension)

    private fun streamUrl(type: String, id: Long, extension: String) =
        "${credentials.base}/$type/${credentials.username.enc()}/${credentials.password.enc()}/$id.$extension"

    fun xmltvUrl(): String = "${credentials.base}/xmltv.php".toHttpUrl().newBuilder()
        .addQueryParameter("username", credentials.username)
        .addQueryParameter("password", credentials.password)
        .build().toString()

    private fun apiUrl(action: String? = null, extra: HttpUrl.Builder.() -> Unit = {}): HttpUrl {
        val base = "${credentials.base}/player_api.php".toHttpUrlOrThrow()
        return base.newBuilder()
            .addQueryParameter("username", credentials.username)
            .addQueryParameter("password", credentials.password)
            .apply { if (action != null) addQueryParameter("action", action) }
            .apply(extra)
            .build()
    }

    // Inline so suspending callbacks can run while the response streams.
    @Suppress("ThrowsCount") // each failure gets its own readable message
    private inline fun <T> get(url: HttpUrl, read: (InputStream) -> T): T {
        val response = try {
            http.newCall(Request.Builder().url(url).build()).execute()
        } catch (e: IOException) {
            throw XtreamException("Couldn't reach the server: ${e.message}", e, Reason.UNREACHABLE)
        }
        response.use {
            if (!it.isSuccessful) {
                val reply = ServerReply.of(it, url)
                val said = reply?.let { r -> " ($r)" }.orEmpty()
                throw XtreamException("Server returned error ${it.code}$said", reason = Reason.SERVER_ERROR, code = it.code, reply = reply)
            }
            val body = it.body?.byteStream() ?: throw XtreamException("Empty response from server", reason = Reason.EMPTY_RESPONSE)
            return try {
                read(body)
            } catch (e: SerializationException) {
                throw XtreamException("Unexpected response from server", e, Reason.UNEXPECTED_RESPONSE)
            } catch (e: NoSuchElementException) {
                throw XtreamException("Empty response from server", e, Reason.EMPTY_RESPONSE)
            }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun Json.decodeFromStream(input: InputStream): JsonElement =
        decodeToSequence<JsonElement>(input, DecodeSequenceMode.WHITESPACE_SEPARATED).first()

    @OptIn(ExperimentalSerializationApi::class)
    private fun Json.decodeArray(input: InputStream): Sequence<JsonElement> =
        decodeToSequence<JsonElement>(input, DecodeSequenceMode.ARRAY_WRAPPED)

    private fun String.toHttpUrlOrThrow(): HttpUrl = try {
        toHttpUrl()
    } catch (e: IllegalArgumentException) {
        throw XtreamException("Invalid server address", e, Reason.INVALID_ADDRESS)
    }

    private fun String.enc(): String = java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")
}
