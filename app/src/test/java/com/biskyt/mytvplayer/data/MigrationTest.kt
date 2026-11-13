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

    @Test
    fun `version 8 to 9 keeps guides, with when their programmes end not known yet`() {
        helper.createDatabase("migration-test-9", 8).use { db ->
            db.execSQL("INSERT INTO epg_source (id, name, url, enabled, lastImportAt, channelCount) VALUES (10, 'g', 'http://g', 1, 5, 7)")
        }
        helper.runMigrationsAndValidate("migration-test-9", 9, true).use { db ->
            db.query("SELECT name, lastImportAt, channelCount, programmesUntil FROM epg_source WHERE id = 10").use {
                it.moveToFirst()
                assertEquals(listOf("g", "5", "7", "0"), List(4) { i -> it.getString(i) })
            }
        }
    }

    @Test
    fun `version 9 to 10 restricts the adult categories already stored`() {
        helper.createDatabase("migration-test-10", 9).use { db ->
            db.execSQL(
                "INSERT INTO playlist (id, name, server, username, password, lastSyncAt, kind) VALUES (1, 'P', 'http://x', 'u', 'p', 0, 'xtream')"
            )
            listOf("'a', 'ADULT'", "'b', 'UK'", "'c', NULL").forEachIndexed { i, v ->
                db.execSQL(
                    "INSERT INTO category (playlistId, categoryId, lang, name, included, sortOrder, channelCount, type) VALUES (1, $v, 'n', 0, $i, 0, 'live')"
                )
            }
        }
        helper.runMigrationsAndValidate("migration-test-10", 10, true).use { db ->
            val restricted = db.query("SELECT categoryId, restricted FROM category ORDER BY categoryId").use {
                List(it.count) { _ ->
                    it.moveToNext()
                    it.getString(0) to it.getInt(1)
                }
            }
            assertEquals(listOf("a" to 1, "b" to 0, "c" to 0), restricted)
        }
    }

    @Test
    fun `version 10 to 11 keeps progress and adds an empty list of forgotten items`() {
        helper.createDatabase("migration-test-11", 10).use { db ->
            db.execSQL(
                "INSERT INTO watch_progress (playlistId, kind, itemId, positionMs, durationMs, updatedAt, title, hasNext) " +
                    "VALUES (1, 'movie', 5, 10, 100, 7, 't', 0)"
            )
        }
        helper.runMigrationsAndValidate("migration-test-11", 11, true).use { db ->
            db.query("SELECT COUNT(*) FROM watch_progress").use {
                it.moveToFirst()
                assertEquals(1, it.getInt(0))
            }
            db.query("SELECT COUNT(*) FROM sync_tombstone").use {
                it.moveToFirst()
                assertEquals(0, it.getInt(0))
            }
        }
    }

    @Test
    fun `version 11 to 12 keeps playlists, with no proxy`() {
        helper.createDatabase("migration-test-12", 11).use { db ->
            db.execSQL(
                "INSERT INTO playlist (id, name, server, username, password, lastSyncAt, kind) VALUES (1, 'P', 'http://x', 'u', 'p', 0, 'xtream')"
            )
        }
        helper.runMigrationsAndValidate("migration-test-12", 12, true).use { db ->
            db.query("SELECT name, proxyType, proxyHost, proxyPort FROM playlist WHERE id = 1").use {
                it.moveToFirst()
                assertEquals("P", it.getString(0))
                assertTrue(it.isNull(1) && it.isNull(2) && it.isNull(3))
            }
        }
    }

    @Test
    fun `version 12 to 13 adds an empty list of choices to sync`() {
        helper.createDatabase("migration-test-13", 12).close()
        helper.runMigrationsAndValidate("migration-test-13", 13, true).use { db ->
            db.query("SELECT COUNT(*) FROM sync_choice").use {
                it.moveToFirst()
                assertEquals(0, it.getInt(0))
            }
        }
    }

    @Test
    fun `version 13 to 14 keeps the guides switched on, in no order of their own yet`() {
        helper.createDatabase("migration-test-14", 13).use { db ->
            db.execSQL("INSERT INTO epg_source (id, name, url, enabled, lastImportAt, channelCount) VALUES (7, 'G', 'http://g', 1, 0, 0)")
            db.execSQL("INSERT INTO playlist_epg_source (playlistId, sourceId) VALUES (1, 7)")
        }
        helper.runMigrationsAndValidate("migration-test-14", 14, true).use { db ->
            db.query("SELECT sourceId, position FROM playlist_epg_source WHERE playlistId = 1").use {
                it.moveToFirst()
                assertEquals(7, it.getInt(0))
                assertEquals(0, it.getInt(1))
            }
        }
    }
}
