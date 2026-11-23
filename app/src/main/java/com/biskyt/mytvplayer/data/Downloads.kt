package com.biskyt.mytvplayer.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import com.biskyt.mytvplayer.USER_AGENT
import com.biskyt.mytvplayer.core.vod.DownloadNames
import java.io.File

/**
 * Downloads (setting "Downloads", off by default; the owner, 2026-11-22): a film or an episode saved to the device's
 * Download folder, in Movies or TV Shows, a folder for each title (see DownloadNames). Android's own download manager
 * fetches it, with the app's User-Agent (the owner, 2026-11-23): it goes on in the background, shows its progress in a
 * notification and tries again after a network failure, and the app needs no permission to run in the background. It
 * cannot go through a playlist's proxy: the Download option says so for a playlist that has one. Subtitles come inside
 * the video file from these providers: there are no separate files to fetch.
 */
object Downloads {
    /**
     * Starts downloading [url] to [place]: the download manager's id for it, or null when the same file, asked for
     * again while it downloads, is not fetched twice.
     */
    fun start(context: Context, url: String, place: DownloadNames.Place): Long? {
        val manager = context.getSystemService(DownloadManager::class.java) ?: return null
        if (place.file in running(manager)) return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) makeFolder(place)
        val request = DownloadManager.Request(Uri.parse(url))
            .addRequestHeader("User-Agent", USER_AGENT)
            .setTitle(place.file)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "${place.folder}/${place.file}")
        return manager.enqueue(request)
    }

    /** The names of the downloads that are waiting or under way. */
    private fun running(manager: DownloadManager): Set<String> {
        val query = DownloadManager.Query()
            .setFilterByStatus(DownloadManager.STATUS_PENDING or DownloadManager.STATUS_RUNNING or DownloadManager.STATUS_PAUSED)
        return manager.query(query)?.use { c ->
            val title = c.getColumnIndex(DownloadManager.COLUMN_TITLE)
            buildSet { while (title >= 0 && c.moveToNext()) c.getString(title)?.let(::add) }
        }.orEmpty()
    }

    /**
     * Android 9: the title's folder, which the download manager does not make itself (with the storage permission
     * asked for when Downloads was turned on). Later versions make it.
     */
    @Suppress("DEPRECATION") // the only way on Android 9
    private fun makeFolder(place: DownloadNames.Place) {
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), place.folder).mkdirs()
    }
}
