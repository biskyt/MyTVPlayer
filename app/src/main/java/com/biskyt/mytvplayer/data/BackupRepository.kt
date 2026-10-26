package com.biskyt.mytvplayer.data

import android.database.SQLException
import android.util.Log
import androidx.room.withTransaction
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.appString
import com.biskyt.mytvplayer.data.db.AppDatabase
import com.biskyt.mytvplayer.data.db.CategoryEntity
import com.biskyt.mytvplayer.data.db.ChannelEntity
import com.biskyt.mytvplayer.data.db.ChannelEpgMappingEntity
import com.biskyt.mytvplayer.data.db.EpgSourceEntity
import com.biskyt.mytvplayer.data.db.FavouriteEntity
import com.biskyt.mytvplayer.data.db.PlaylistEntity
import com.biskyt.mytvplayer.data.db.PlaylistEpgSourceEntity
import com.biskyt.mytvplayer.data.db.VodFavouriteEntity
import com.biskyt.mytvplayer.data.db.WatchProgressEntity
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/** A file that can't be restored, with a message for the user. */
class BackupException(message: String) : Exception(message)

/**
 * The user's set-up as one JSON document: settings, playlists (with their logins), category and channel choices,
 * favourites, guide sources and manual guide choices, and how far into movies and episodes they got. Downloaded data
 * (channel, movie and series lists, guides) is left out: it is fetched again after a restore.
 */
@Serializable
@Suppress("LongParameterList") // a document: one field per part
class Backup(
    /** Always [APP]: tells a MyTVPlayer backup from any other JSON file. */
    val app: String,
    val version: Int,
    val createdAt: Long,
    val settings: Settings,
    val playlists: List<PlaylistEntity>,
    val categories: List<CategoryEntity>,
    val channels: List<ChannelEntity>,
    val favourites: List<FavouriteEntity>,
    val vodFavourites: List<VodFavouriteEntity>,
    val epgSources: List<EpgSourceEntity>,
    val guideChoices: List<ChannelEpgMappingEntity>,
    val progress: List<WatchProgressEntity>,
    /** Which guides are on for which playlists; null from versions before 1.4.0 (see [guideLinks]). */
    val guideLinks: List<PlaylistEpgSourceEntity>? = null
) {
    /**
     * [guideLinks], or for a backup from before 1.4.0, its guides that were on: a provider's own guide for its playlist,
     * any other for every playlist (as when the database was upgraded).
     */
    fun guideLinks(): List<PlaylistEpgSourceEntity> = guideLinks ?: epgSources.filter { it.enabled }.flatMap { s ->
        playlists.filter { s.playlistId == null || it.id == s.playlistId }.map { PlaylistEpgSourceEntity(it.id, s.id) }
    }

    companion object {
        const val APP = "MyTVPlayer"
        const val VERSION = 1
    }
}

/**
 * Writes and restores backups. Restoring replaces the database's contents in one transaction (a bad file changes
 * nothing), then downloads every playlist and guide again.
 */
@OptIn(ExperimentalSerializationApi::class) // stream encoding and decoding
class BackupRepository(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val epg: EpgRepository,
    private val sync: SyncManager
) {
    private val dao = db.backup()
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    /** The backup as a file's bytes (small: a few hundred KB even with thousands of hidden channels). */
    suspend fun export(): ByteArray {
        val current = settings.current()
        val backup = db.withTransaction {
            Backup(
                app = Backup.APP,
                version = Backup.VERSION,
                createdAt = System.currentTimeMillis(),
                settings = current,
                playlists = db.playlists().all(),
                categories = dao.categories(),
                channels = dao.channelChoices(),
                favourites = dao.favourites(),
                vodFavourites = dao.vodFavourites(),
                epgSources = dao.epgSources(),
                guideChoices = dao.manualMappings(),
                progress = dao.progress(),
                guideLinks = dao.guideLinks()
            )
        }
        return ByteArrayOutputStream().also { json.encodeToStream(backup, it) }.toByteArray()
    }

    /** Replaces everything from a backup and starts downloading it all again. Returns whether it has any playlists. */
    suspend fun restore(input: InputStream): Boolean {
        val backup = read(input)
        val hasPlaylists = sync.whileIdle {
            apply(backup).also {
                epg.forgetDownloads()
                // The old lists and guides are gone: give their space back before downloading again (optional, so
                // a failure here mustn't fail the restore).
                try {
                    db.compactIfWorthwhile()
                } catch (e: SQLException) {
                    Log.w("Backup", "Couldn't compact the database", e)
                }
            }
        }
        sync.refreshAll()
        return hasPlaylists
    }

    /**
     * Reads and checks a backup file. The header is checked before the rest, so a file from a newer version is
     * reported as such even if its contents have changed shape.
     */
    fun read(input: InputStream): Backup {
        val tree = notABackupUnless { json.decodeFromStream<JsonObject>(input) }
        if (notABackupUnless { tree["app"]?.jsonPrimitive?.content } != Backup.APP) throw BackupException(NOT_A_BACKUP)
        if (notABackupUnless { tree["version"]?.jsonPrimitive?.int ?: 0 } > Backup.VERSION) throw BackupException(NEWER)
        return notABackupUnless { json.decodeFromJsonElement<Backup>(tree) }
    }

    /** SerializationException (not JSON, fields missing or of the wrong type) is an IllegalArgumentException. */
    private inline fun <T> notABackupUnless(block: () -> T): T = try {
        block()
    } catch (e: IllegalArgumentException) {
        throw BackupException(NOT_A_BACKUP).apply { initCause(e) }
    }

    /** The database and settings part of [restore], in one transaction. Returns whether there are any playlists. */
    suspend fun apply(backup: Backup): Boolean {
        db.withTransaction {
            db.clearEveryTable()
            dao.insert(
                // Never downloaded on this TV: zero times make everything refresh straight away.
                backup.playlists.map { it.copy(lastSyncAt = 0, vodFeedHash = null) },
                backup.categories,
                backup.channels,
                backup.favourites,
                backup.vodFavourites,
                backup.epgSources.map { it.copy(lastImportAt = 0, channelCount = 0, lastError = null) },
                backup.guideLinks(),
                backup.guideChoices,
                backup.progress
            )
        }
        val ids = backup.playlists.map { it.id }
        val active = backup.settings.activePlaylistId.takeIf { it in ids } ?: ids.firstOrNull() ?: 0
        settings.update { backup.settings.copy(activePlaylistId = active) }
        return ids.isNotEmpty()
    }

    private companion object {
        val NOT_A_BACKUP get() = appString(R.string.settings_backup_not_a_backup)
        val NEWER get() = appString(R.string.settings_backup_newer)
    }
}
