package com.biskyt.mytvplayer.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.data.db.AppDatabase
import com.biskyt.mytvplayer.data.db.EpgSourceEntity
import com.biskyt.mytvplayer.testutil.PlainApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A guide is read again when its stored programmes run short (less than 3 days ahead), at most daily. Up to 7 days of
 * programmes are kept, so a guide with a week of data is read every 4 days.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = PlainApp::class)
class GuideRefreshTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    private val now = 100 * DAY
    private val epg = EpgRepository(db, OkHttpClient(), context.cacheDir.resolve("refresh-epg"), scope, clock = { now })

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    /** A guide read [readAgo] ago, whose programmes then reached [ahead] past that read. */
    private fun guide(readAgo: Long, ahead: Long) =
        EpgSourceEntity(name = "Guide", url = "http://guide", lastImportAt = now - readAgo, programmesUntil = now - readAgo + ahead)

    @Test
    fun `a guide never read is due`() {
        assertTrue(epg.isOutOfDate(EpgSourceEntity(name = "Guide", url = "http://guide")))
    }

    @Test
    fun `a guide with a week of programmes is read again after about 4 days, not daily`() {
        val week = 7 * DAY
        assertFalse(epg.isOutOfDate(guide(readAgo = 1 * DAY, ahead = week)))
        assertFalse(epg.isOutOfDate(guide(readAgo = 3 * DAY + HOUR, ahead = week)))
        // Less than 3 days left.
        assertTrue(epg.isOutOfDate(guide(readAgo = 4 * DAY + HOUR, ahead = week)))
    }

    @Test
    fun `programmes past the 7 days kept do not delay the next read`() {
        // The file reaches 10 days ahead, but only 7 are stored: 2 days left after 5.
        assertTrue(epg.isOutOfDate(guide(readAgo = 5 * DAY, ahead = 10 * DAY).copy(lastImportAt = now - 5 * DAY + HOUR)))
        assertFalse(epg.isOutOfDate(guide(readAgo = 3 * DAY, ahead = 10 * DAY)))
    }

    @Test
    fun `a guide with only 2 days of programmes is read daily, not more often`() {
        val twoDays = 2 * DAY
        assertFalse(epg.isOutOfDate(guide(readAgo = 12 * HOUR, ahead = twoDays)))
        assertTrue(epg.isOutOfDate(guide(readAgo = 21 * HOUR, ahead = twoDays)))
    }

    @Test
    fun `a guide whose channels have no programmes is read daily`() {
        assertFalse(epg.isOutOfDate(guide(readAgo = 12 * HOUR, ahead = 0).copy(programmesUntil = 0)))
        assertTrue(epg.isOutOfDate(guide(readAgo = 21 * HOUR, ahead = 0).copy(programmesUntil = 0)))
    }

    @Test
    fun `any guide is read again after 4 days`() {
        // Programmes far ahead by the guide's own count: only 7 days were stored, so read anyway.
        assertTrue(epg.isOutOfDate(guide(readAgo = 4 * DAY + HOUR, ahead = 30 * DAY).copy(programmesUntil = now + 30 * DAY)))
    }

    private companion object {
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR
    }
}
