package com.biskyt.mytvplayer.core.sync

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/** One file in the sync folder. [modifiedTime] is the service's own stamp: it changes each time the file changes. */
@Serializable
data class SyncFile(val id: String, val name: String, val modifiedTime: String = "")

/** A small folder of the app's own files, in the user's own storage. */
interface SyncStore {
    suspend fun list(): List<SyncFile>

    suspend fun download(id: String): ByteArray

    /** Makes a new file and returns its id. */
    suspend fun create(name: String, content: ByteArray): String

    suspend fun update(id: String, content: ByteArray)

    suspend fun delete(id: String)
}

/**
 * The app's own files in the user's Google Drive, in a folder named [FOLDER_NAME]. With the `drive.file` permission,
 * Drive shows the app only the files that the app made itself, so [list] needs no folder: a second folder with the
 * same name (two TVs that made one at the same time) does no harm. (Google refuses the hidden app folder,
 * `drive.appdata`, for a TV sign-in.) [token] gives an access token; with `true`, it must get a new one (Drive refused
 * the last one).
 */
class DriveStore(
    private val http: OkHttpClient,
    private val token: suspend (renew: Boolean) -> String,
    private val base: String = "https://www.googleapis.com",
) : SyncStore {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Listing(val files: List<SyncFile> = emptyList())

    @Serializable
    private data class Created(val id: String)

    /** Drive's answer to a refusal: its [Error.errors] name the reason, e.g. `accessNotConfigured`. */
    @Serializable
    private data class Refusal(val error: Error) {
        @Serializable
        data class Error(val errors: List<Reason> = emptyList())

        @Serializable
        data class Reason(val reason: String = "")
    }

    override suspend fun list(): List<SyncFile> = files("trashed = false and mimeType != '$FOLDER'")

    private suspend fun files(query: String): List<SyncFile> {
        val url = "$base/drive/v3/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("pageSize", "1000")
            .addQueryParameter("fields", "files(id,name,modifiedTime)")
            .build()
        return json.decodeOrFail(Listing.serializer(), call(Request.Builder().url(url)).decodeToString(), "Drive").files
    }

    /** The app's folder: found, or made the first time. Only a new file needs it, so this runs seldom. */
    private suspend fun folder(): String = files("trashed = false and mimeType = '$FOLDER' and name = '$FOLDER_NAME'").firstOrNull()?.id
        ?: createdId(
            Request.Builder().url("$base/drive/v3/files?fields=id"),
            """{"name":"$FOLDER_NAME","mimeType":"$FOLDER"}""".toRequestBody(JSON)
        )

    private suspend fun createdId(request: Request.Builder, body: RequestBody): String =
        json.decodeOrFail(Created.serializer(), call(request.post(body)).decodeToString(), "Drive").id

    override suspend fun download(id: String): ByteArray = call(Request.Builder().url("$base/drive/v3/files/$id?alt=media"))

    override suspend fun create(name: String, content: ByteArray): String {
        val body = MultipartBody.Builder().setType("multipart/related".toMediaType())
            .addPart("""{"name":"$name","parents":["${folder()}"]}""".toRequestBody(JSON))
            .addPart(content.toRequestBody(BINARY))
            .build()
        return createdId(Request.Builder().url("$base/upload/drive/v3/files?uploadType=multipart&fields=id"), body)
    }

    override suspend fun update(id: String, content: ByteArray) {
        call(Request.Builder().url("$base/upload/drive/v3/files/$id?uploadType=media").patch(content.toRequestBody(BINARY)))
    }

    override suspend fun delete(id: String) {
        call(Request.Builder().url("$base/drive/v3/files/$id").delete())
    }

    private suspend fun call(request: Request.Builder, renew: Boolean = false): ByteArray {
        val auth = token(renew)
        val (code, body) = withContext(Dispatchers.IO) {
            try {
                http.newCall(request.header("Authorization", "Bearer $auth").build()).execute().use { r ->
                    r.code to (r.body?.bytes() ?: ByteArray(0))
                }
            } catch (e: IOException) {
                throw SyncException("Couldn't reach Google Drive: ${e.message}", e)
            }
        }
        return when {
            code in OK -> body
            // The access token ran out early: one more try with a new one.
            code == UNAUTHORISED && !renew -> call(request, renew = true)
            else -> throw SyncException("Google Drive answered $code", code = reason(body) ?: "HTTP $code")
        }
    }

    /** Drive's reason for a refusal, if its answer gives one. */
    private fun reason(body: ByteArray): String? = try {
        json.decodeFromString(Refusal.serializer(), body.decodeToString()).error.errors.firstOrNull()?.reason?.ifBlank { null }
    } catch (_: IllegalArgumentException) {
        null
    }

    private companion object {
        const val FOLDER_NAME = "MyTVPlayer"
        val OK = 200..299
        const val UNAUTHORISED = 401
        val JSON = "application/json; charset=UTF-8".toMediaType()
        val BINARY = "application/octet-stream".toMediaType()
        const val FOLDER = "application/vnd.google-apps.folder"
    }
}
