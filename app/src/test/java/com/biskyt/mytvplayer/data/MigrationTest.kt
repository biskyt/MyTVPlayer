package com.biskyt.mytvplayer.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.biskyt.mytvplayer.data.db.AppDatabase
import com.biskyt.mytvplayer.testutil.PlainApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Upgrading from an earlier release keeps the user's playlists and channels. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = PlainApp::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test
    fun `version 1 to 2 keeps data and marks old playlists as Xtream`() {
        helper.createDatabase("migration-test", 1).use { db ->
            db.execSQL(
                "INSERT INTO playlist (id, name, server, username, password, lastSyncAt) VALUES (1, 'Home', 'http://x', 'u', 'p', 0)"
            )
            db.execSQL(
                "INSERT INTO channel (playlistId, streamId, number, name, categoryId, archiveDays, hidden, position, syncStamp) " +
                    "VALUES (1, 101, 1, 'One', '1', 0, 0, 1, 0)",
            )
        }
        helper.runMigrationsAndValidate("migration-test", 2, true).use { db ->
            db.query("SELECT kind FROM playlist WHERE id = 1").use {
                it.moveToFirst()
                assertEquals("xtream", it.getString(0))
            }
            db.query("SELECT name, streamUrl FROM channel WHERE streamId = 101").use {
                it.moveToFirst()
                assertEquals("One", it.getString(0))
                assertEquals(true, it.isNull(1))
            }
        }
    }

    @Test
    fun `version 2 to 3 keeps categories as live ones and adds the movie tables`() {
        helper.createDatabase("migration-test-3", 2).use { db ->
            db.execSQL(
                "INSERT INTO category (playlistId, categoryId, name, lang, included, sortOrder, channelCount) VALUES (1, '5', 'UK | NEWS', 'UK', 1, 0, 3)"
            )
        }
        helper.runMigrationsAndValidate("migration-test-3", 3, true).use { db ->
            db.query("SELECT type, included FROM category WHERE categoryId = '5'").use {
                it.moveToFirst()
                assertEquals("live", it.getString(0))
                assertEquals(1, it.getInt(1))
            }
            db.query("SELECT COUNT(*) FROM movie").use {
                it.moveToFirst()
                assertEquals(0, it.getInt(0))
            }
        }
    }

    @Test
    fun `version 3 to 4 keeps programmes, of unknown kind until the next guide update`() {
        helper.createDatabase("migration-test-4", 3).use { db ->
            db.execSQL(
                "INSERT INTO programme (sourceId, xmlId, startMs, stopMs, title, subtitle, description, stamp) " +
                    "VALUES (1, 'one.uk', 1000, 2000, 'News', NULL, NULL, 0)"
            )
        }
        helper.runMigrationsAndValidate("migration-test-4", 4, true).use { db ->
            db.query("SELECT title, kind, season FROM programme").use {
                it.moveToFirst()
                assertEquals("News", it.getString(0))
                assertEquals(0, it.getInt(1))
                assertEquals(true, it.isNull(2))
            }
        }
    }

    @Test
    fun `version 4 to 5 keeps channels and adds the catch-up index`() {
        helper.createDatabase("migration-test-5", 4).use { db ->
            db.execSQL(
                "INSERT INTO channel (playlistId, streamId, number, name, categoryId, archiveDays, hidden, position) " +
                    "VALUES (1, 101, 1, 'One', '1', 3, 0, 1)"
            )
        }
        helper.runMigrationsAndValidate("migration-test-5", 5, true).use { db ->
            db.query("SELECT archiveDays FROM channel").use {
                it.moveToFirst()
                assertEquals(3, it.getInt(0))
            }
        }
    }

    @Test
    fun `version 5 to 6 keeps playlists and movies, and adds M3U movie and series playlists`() {
        helper.createDatabase("migration-test-6", 5).use { db ->
            db.execSQL(
                "INSERT INTO playlist (id, name, kind, server, username, password, lastSyncAt) VALUES (1, 'Home', 'm3u', 'http://a', '', '', 0)"
            )
            db.execSQL(
                "INSERT INTO movie (playlistId, streamId, title, categoryId, added, extension, position, syncStamp) " +
                    "VALUES (1, 501, 'Dune', '10', 0, 'mkv', 0, 0)"
            )
        }
        helper.runMigrationsAndValidate("migration-test-6", 6, true).use { db ->
            db.query("SELECT moviesUrl, seriesUrl, vodFeedHash FROM playlist").use {
                it.moveToFirst()
                assertTrue(it.isNull(0) && it.isNull(1) && it.isNull(2))
            }
            db.query("SELECT title, streamUrl FROM movie").use {
                it.moveToFirst()
                assertEquals("Dune", it.getString(0))
                assertTrue(it.isNull(1))
            }
            db.query("SELECT COUNT(*) FROM episode").use {
                it.moveToFirst()
                assertEquals(0, it.getInt(0))
            }
        }
    }

    @Test
    fun `version 6 to 7 keeps favourites, in their old order until moved`() {
        helper.createDatabase("migration-test-7", 6).use { db ->
            db.execSQL("INSERT INTO favourite (playlistId, streamId, addedAt) VALUES (1, 101, 5)")
            db.execSQL("INSERT INTO vod_favourite (playlistId, kind, itemId, addedAt) VALUES (1, 'movie', 501, 6)")
        }
        helper.runMigrationsAndValidate("migration-test-7", 7, true).use { db ->
            db.query("SELECT streamId, addedAt, position FROM favourite").use {
                it.moveToFirst()
                assertEquals(listOf(101L, 5L, 0L), listOf(it.getLong(0), it.getLong(1), it.getLong(2)))
            }
            db.query("SELECT itemId, position FROM vod_favourite").use {
                it.moveToFirst()
                assertEquals(listOf(501L, 0L), listOf(it.getLong(0), it.getLong(1)))
            }
        }
    }

    @Test
    fun `version 7 to 8 keeps each guide that was on for the playlists that used it`() {
        helper.createDatabase("migration-test-8", 7).use { db ->
            listOf(1, 2).forEach {
                db.execSQL(
                    "INSERT INTO playlist (id, name, server, username, password, lastSyncAt, kind) VALUES ($it, 'P$it', 'http://x', 'u', 'p', 0, 'xtream')"
                )
            }
            // A provider's own guide on, another provider's off, a guide added by hand on, and one off.
            listOf("10, 1, 1", "20, 2, 0", "30, NULL, 1", "40, NULL, 0").forEach {
                db.execSQL(
                    "INSERT INTO epg_source (id, playlistId, enabled, name, url, lastImportAt, channelCount) VALUES ($it, 'g', 'http://g', 0, 0)"
                )
            }
        }
        helper.runMigrationsAndValidate("migration-test-8", 8, true, AppDatabase.MIGRATION_7_8).use { db ->
            val links = db.query("SELECT playlistId, sourceId FROM playlist_epg_source ORDER BY sourceId, playlistId").use {
                List(it.count) { _ ->
                    it.moveToNext()
                    it.getLong(0) to it.getLong(1)
                }
            }
            assertEquals(listOf(1L to 10L, 1L to 30L, 2L to 30L), links)
        }
    }
}
