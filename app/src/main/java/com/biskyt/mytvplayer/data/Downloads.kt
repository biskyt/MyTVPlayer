package com.biskyt.mytvplayer.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.USER_AGENT
import com.biskyt.mytvplayer.appString
import com.biskyt.mytvplayer.container
import com.biskyt.mytvplayer.core.vod.DownloadNames
import com.biskyt.mytvplayer.data.db.proxy
import com.biskyt.mytvplayer.online
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlin.coroutines.cancellation.CancellationException
import okhttp3.Request

/**
 * Downloads (setting "Downloads", off by default; the owner, 2026-11-22): a film or an episode saved to the device's
 * Download folder, in Movies or TV Shows, a folder for each title (see DownloadNames). The file is fetched as the player
 * fetches the stream: through its playlist's proxy, with the app's User-Agent. It goes on in the background, with a
 * notification that shows how far it got, and starts again from the beginning if it is stopped (no network, the TV
 * restarted). Subtitles come inside the video file from these providers: there are no separate files to fetch.
 */
object Downloads {
    private const val KEY_URL = "url"
    private const val KEY_FOLDER = "folder"
    private const val KEY_FILE = "file"
    private const val KEY_PLAYLIST = "playlist"
    internal const val CHANNEL = "downloads"

    /** Starts downloading [url] (of playlist [playlistId]) to [place]; the same file asked for again is not fetched twice. */
    fun start(context: Context, playlistId: Long, url: String, place: DownloadNames.Place) {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>().setInputData(data(playlistId, url, place)).setConstraints(online).build()
        WorkManager.getInstance(context).enqueueUniqueWork("download:${place.folder}/${place.file}", ExistingWorkPolicy.KEEP, request)
    }

    /** What the worker is given: the download's address, place and playlist. */
    internal fun data(playlistId: Long, url: String, place: DownloadNames.Place) =
        workDataOf(KEY_URL to url, KEY_FOLDER to place.folder, KEY_FILE to place.file, KEY_PLAYLIST to playlistId)

    internal fun read(params: WorkerParameters) = Job(
        params.inputData.getString(KEY_URL).orEmpty(),
        DownloadNames.Place(params.inputData.getString(KEY_FOLDER).orEmpty(), params.inputData.getString(KEY_FILE).orEmpty()),
        params.inputData.getLong(KEY_PLAYLIST, 0)
    )

    internal data class Job(val url: String, val place: DownloadNames.Place, val playlistId: Long)
}

/** Fetches one download (see [Downloads]). */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val job = Downloads.read(params)
    private val notificationId = job.place.file.hashCode()

    override suspend fun doWork(): Result {
        if (job.url.isEmpty() || job.place.file.isEmpty()) return Result.failure()
        setForeground(progress(0))
        return try {
            fetch()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            // The network went or the server stopped: tried again later, a few times.
            if (runAttemptCount < TRIES) Result.retry() else Result.failure()
        }
    }

    private suspend fun fetch() {
        val c = applicationContext.container
        val proxy = c.playlists.get(job.playlistId)?.proxy
        val request = Request.Builder().url(job.url).header("User-Agent", USER_AGENT).build()
        c.playlists.proxies.forProxy(proxy).newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw IOException("The server answered ${r.code}")
            val body = r.body ?: throw IOException("The server sent nothing")
            val length = body.contentLength()
            val target = Target.create(applicationContext, job.place)
            try {
                target.out.use { out -> body.byteStream().use { input -> copy(input, out, length) } }
                target.finish()
            } catch (e: IOException) {
                target.abandon()
                throw e
            }
        }
    }

    /** [input] into [out], showing how far it got of [length] bytes (0 or less: not known). */
    private suspend fun copy(input: java.io.InputStream, out: OutputStream, length: Long) {
        val buffer = ByteArray(BUFFER)
        var done = 0L
        var shown = -1
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            done += n
            val percent = if (length > 0) (done * PERCENT / length).toInt() else -1
            if (percent != shown) {
                shown = percent
                setForeground(progress(percent))
            }
        }
    }

    /** The notification while it downloads: the file's name and how far it got ([percent] -1: not known). */
    private fun progress(percent: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(Downloads.CHANNEL) == null) {
            val name = appString(R.string.settings_downloads)
            manager.createNotificationChannel(NotificationChannel(Downloads.CHANNEL, name, NotificationManager.IMPORTANCE_LOW))
        }
        val notification = NotificationCompat.Builder(applicationContext, Downloads.CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(appString(R.string.vod_downloading, job.place.file))
            .setProgress(PERCENT, percent.coerceAtLeast(0), percent < 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    /**
     * Where the file is written: on Android 10 and later a Download entry that shows only once it is complete; on
     * Android 9 a file in the Download folder (with the storage permission asked for when Downloads was turned on).
     */
    private class Target(val out: OutputStream, val finish: () -> Unit, val abandon: () -> Unit) {
        companion object {
            fun create(context: Context, place: DownloadNames.Place): Target =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) mediaStore(context, place) else file(place)

            @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
            private fun mediaStore(context: Context, place: DownloadNames.Place): Target {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, place.file)
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/${place.folder}")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("No room for the file")
                val out = resolver.openOutputStream(uri) ?: throw IOException("The file could not be written")
                return Target(
                    out,
                    finish = { resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) },
                    abandon = { resolver.delete(uri, null, null) }
                )
            }

            @Suppress("DEPRECATION") // the only way on Android 9, which has no MediaStore.Downloads
            private fun file(place: DownloadNames.Place): Target {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), place.folder)
                if (!dir.isDirectory && !dir.mkdirs()) throw IOException("The folder could not be made")
                val part = File(dir, place.file + ".part")
                return Target(
                    part.outputStream(),
                    finish = { if (!part.renameTo(File(dir, place.file))) throw IOException("The file could not be saved") },
                    abandon = { part.delete() }
                )
            }
        }
    }

    private companion object {
        const val TRIES = 3
        const val BUFFER = 64 * 1024
        const val PERCENT = 100
    }
}
