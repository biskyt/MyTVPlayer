package com.biskyt.mytvplayer.launcher

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.edit
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest

/**
 * The posters and logos on the launcher's cards, served by the app: the launcher does not fetch them itself (a playlist's
 * proxy and the app's User-Agent would be left out). A card has `content://<app>.launcherimages/<key>`; the app fetches
 * the picture the first time it is asked for and keeps a copy. Only the pictures of the app's own cards are served: a
 * key that the app did not give out finds nothing, so other apps cannot have it fetch addresses of their choice.
 */
object LauncherImages {
    private const val PREFS = "launcher_images"

    /** A copy is fetched again after a week (a provider's poster can change). */
    private const val KEEP_MS = 7L * 24 * 60 * 60 * 1000

    /** The largest picture kept: a poster or logo is far smaller. */
    const val MAX_BYTES = 4 * 1024 * 1024

    fun authority(context: Context) = "${context.packageName}.launcherimages"

    /** The cards' pictures, [urls], are the ones served from now on (the others are forgotten); their addresses by URL. */
    fun publish(context: Context, urls: Collection<String>): Map<String, Uri> {
        val keys = urls.associateWith(::keyOf)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Written again only when the pictures change: a card's position changes far more often.
        if (prefs.all.keys != keys.values.toSet()) {
            prefs.edit {
                clear()
                keys.forEach { (url, key) -> putString(key, url) }
            }
            val kept = keys.values.toSet()
            dir(context).listFiles()?.filter { it.name !in kept }?.forEach(File::delete)
        }
        return keys.mapValues { (_, key) -> Uri.Builder().scheme("content").authority(authority(context)).appendPath(key).build() }
    }

    /** No pictures served any more, and the copies deleted (the launcher rows turned off). */
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { clear() }
        dir(context).deleteRecursively()
    }

    /** The picture for [key], fetched now if there is no recent copy; null if the key is not a card's, or it can't be had. */
    internal fun file(context: Context, key: String): File? {
        val url = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, null) ?: return null
        val file = File(dir(context), key)
        if (file.exists() && System.currentTimeMillis() - file.lastModified() < KEEP_MS) return file
        return fetch(context, url, file) ?: file.takeIf { it.exists() } // an old copy rather than none
    }

    /** [url]'s picture, fetched by the app and kept as [file]; null if it can't be had. */
    private fun fetch(context: Context, url: String, file: File): File? {
        val bytes = (context.applicationContext as? LauncherHost)?.launcherImage(url)?.takeIf { it.size in 1..MAX_BYTES } ?: return null
        // Its own file: the launcher can ask for the same picture twice at once.
        val temp = File.createTempFile(file.name, ".tmp", dir(context).apply { mkdirs() })
        temp.writeBytes(bytes)
        if (!temp.renameTo(file)) temp.delete() // another request kept it first
        return file.takeIf { it.exists() }
    }

    internal fun keyOf(url: String): String =
        MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }.take(KEY_CHARS)

    internal fun isKey(s: String) = s.length == KEY_CHARS && s.all { it in '0'..'9' || it in 'a'..'f' }

    private fun dir(context: Context) = File(context.cacheDir, "launcher-images")

    private const val KEY_CHARS = 32
}

/** Serves [LauncherImages] to the launcher. Turned off (in the manifest and by the app) while the rows are off. */
class LauncherImageProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Read only")
        val ctx = context ?: throw FileNotFoundException()
        val key = uri.lastPathSegment?.takeIf(LauncherImages::isKey) ?: throw FileNotFoundException()
        val file = LauncherImages.file(ctx, key) ?: throw FileNotFoundException(key)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = "image/*"

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? =
        null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0

    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
}
