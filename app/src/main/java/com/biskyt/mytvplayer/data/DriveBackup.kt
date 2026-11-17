package com.biskyt.mytvplayer.data

import com.biskyt.mytvplayer.core.sync.SyncFile
import com.biskyt.mytvplayer.core.sync.SyncStore
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * The backup in the user's Google Drive: one file with a fixed name ([NAME]), in the app's folder with the sync's
 * files, replaced each time. All the user's TVs share it, as they share the sign-in's Google account. [drive] is the
 * folder, with the sign-in of [ProgressSync].
 */
class DriveBackup(private val drive: () -> SyncStore, private val clock: () -> Long = System::currentTimeMillis) {
    /** A backup in Drive; [modifiedAt] is 0 when Drive's time can't be read. */
    data class Saved(val file: SyncFile, val modifiedAt: Long)

    /** The newest backup file (two TVs can make one each at the same time); null when there is none. */
    suspend fun find(): Saved? = drive().list().filter { it.name == NAME }.maxByOrNull { it.modifiedTime }
        ?.let { Saved(it, millis(it.modifiedTime)) }

    /** Saves [bytes] over the backup there, or as a new one. Returns it, saved now. */
    suspend fun save(bytes: ByteArray): Saved {
        val store = drive()
        val old = find()?.file
        val file = if (old == null) SyncFile(store.create(NAME, bytes), NAME) else old.also { store.update(it.id, bytes) }
        return Saved(file, clock())
    }

    suspend fun read(saved: Saved): ByteArray = drive().download(saved.file.id)

    private fun millis(time: String) = try {
        Instant.parse(time).toEpochMilli()
    } catch (_: DateTimeParseException) {
        0L
    }

    companion object {
        const val NAME = "mytvplayer-backup.json"
    }
}
