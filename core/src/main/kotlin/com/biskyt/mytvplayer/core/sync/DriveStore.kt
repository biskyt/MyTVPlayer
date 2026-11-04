package com.biskyt.mytvplayer.core.sync

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** One file in the sync folder. [modifiedTime] is the service's own stamp: it changes each time the file changes. */
@Serializable
data class SyncFile(val id: String, val name: String, val modifiedTime: String = "")

/** A small folder of files that only this app can see, in the user's own storage. */
interface SyncStore {
    suspend fun list(): List<SyncFile>

    suspend fun download(id: String): ByteArray

    /** Makes a new file and returns its id. */
    suspend fun create(name: String, content: ByteArray): String

    suspend fun update(id: String, content: ByteArray)

    suspend fun delete(id: String)
}

/**
 * The app's hidden folder in the user's Google Drive (`appDataFolder`). The user cannot see it in Drive, and other apps
 * cannot read it. [token] gives an access token; with `true`, it must get a new one (Drive refused the last one).
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

    override suspend fun list(): List<SyncFile> {
        val body = call(Request.Builder().url("$base/drive/v3/files?spaces=appDataFolder&pageSize=1000&fields=files(id,name,modifiedTime)"))
        return json.decodeOrFail(Listing.serializer(), body.decodeToString(), "Drive").files
    }

    override suspend fun download(id: String): ByteArray = call(Request.Builder().url("$base/drive/v3/files/$id?alt=media"))

    override suspend fun create(name: String, content: ByteArray): String {
        val body = MultipartBody.Builder().setType("multipart/related".toMediaType())
            .addPart("""{"name":"$name","parents":["appDataFolder"]}""".toRequestBody(JSON))
            .addPart(content.toRequestBody(BINARY))
            .build()
        val answer = call(Request.Builder().url("$base/upload/drive/v3/files?uploadType=multipart&fields=id").post(body))
        return json.decodeOrFail(Created.serializer(), answer.decodeToString(), "Drive").id
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
            else -> throw SyncException("Google Drive answered $code")
        }
    }

    private companion object {
        val OK = 200..299
        const val UNAUTHORISED = 401
        val JSON = "application/json; charset=UTF-8".toMediaType()
        val BINARY = "application/octet-stream".toMediaType()
    }
}
