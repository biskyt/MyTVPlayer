package com.biskyt.mytvplayer.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    version = 8,
    exportSchema = true,
    // 2: M3U playlists (playlist.kind; channel.streamUrl/userAgent/referrer).
    // 3: movies and series (category.type; movie, series, watch_progress, vod_favourite).
    // 4: what a programme is (programme.kind, season, episode).
    // 5: an index for catch-up channels.
    // 6: M3U movies and series (playlist.moviesUrl/seriesUrl/vodFeedHash; movie.streamUrl; episode) and catch-up
    //    (channel.catchupType/catchupSource).
    // 7: the user's order of favourites (favourite.position, vod_favourite.position).
    // 8: guides switched on per playlist (playlist_epg_source; see MIGRATION_7_8).
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7)
    ],
    entities = [
        PlaylistEntity::class, CategoryEntity::class, ChannelEntity::class, FavouriteEntity::class, RecentEntity::class,
        EpgSourceEntity::class, EpgChannelEntity::class, ProgrammeEntity::class, ChannelEpgMappingEntity::class,
        MovieEntity::class, SeriesEntity::class, EpisodeEntity::class, WatchProgressEntity::class, VodFavouriteEntity::class,
        PlaylistEpgSourceEntity::class
    ]
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun playlists(): PlaylistDao
    abstract fun categories(): CategoryDao
    abstract fun channels(): ChannelDao
    abstract fun favourites(): FavouriteDao
    abstract fun epg(): EpgDao
    abstract fun vod(): VodDao
    abstract fun backup(): BackupDao

    /**
     * Gives space back to the system after large deletes (old programmes, removed playlists). SQLite never shrinks the
     * file on its own; this only runs VACUUM when at least a quarter of the file is free pages, so it is usually a no-op.
     */
    fun compactIfWorthwhile() {
        val db = openHelper.writableDatabase
        val pages = db.query("PRAGMA page_count").use { if (it.moveToFirst()) it.getLong(0) else 0L }
        val free = db.query("PRAGMA freelist_count").use { if (it.moveToFirst()) it.getLong(0) else 0L }
        if (pages > 0 && free * 4 >= pages) db.execSQL("VACUUM")
    }

    /**
     * Empties every table in the current transaction (unlike clearAllTables, which can't run inside one). Room's
     * change tracking uses triggers, so open lists still update.
     */
    fun clearEveryTable() {
        val db = openHelper.writableDatabase
        val tables = db.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' " +
                "AND name NOT IN ('android_metadata', 'room_master_table')"
        ).use { c ->
            List(c.count) {
                c.moveToNext()
                c.getString(0)
            }
        }
        tables.forEach { db.execSQL("DELETE FROM `$it`") }
    }

    companion object {
        fun create(context: Context): AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, "mytvplayer.db")
            // Write-ahead logging lets the list read while a background sync writes.
            .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(MIGRATION_7_8)
            .build()

        /**
         * Guides switched on per playlist. Each guide that was on stays on where it was used: a provider's own guide for
         * its playlist, any other for every playlist.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `playlist_epg_source` (`playlistId` INTEGER NOT NULL, `sourceId` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`playlistId`, `sourceId`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_epg_source_sourceId` ON `playlist_epg_source` (`sourceId`)")
                db.execSQL(
                    """INSERT OR IGNORE INTO playlist_epg_source (playlistId, sourceId)
                       SELECT p.id, s.id FROM epg_source s JOIN playlist p ON s.playlistId IS NULL OR p.id = s.playlistId
                       WHERE s.enabled = 1"""
                )
            }
        }
    }
}
