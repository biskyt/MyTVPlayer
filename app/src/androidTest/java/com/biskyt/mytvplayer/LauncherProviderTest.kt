package com.biskyt.mytvplayer

import android.content.Intent
import android.provider.BaseColumns
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.tvprovider.media.tv.TvContractCompat
import com.biskyt.mytvplayer.launcher.ChannelCard
import com.biskyt.mytvplayer.launcher.LauncherRows
import com.biskyt.mytvplayer.launcher.PlayNextCard
import com.biskyt.mytvplayer.launcher.TvLauncher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The TV's own TV provider takes the app's cards: Play Next and the channel of favourites, then none once cleared. The
 * JVM tests can't run it (it is part of the TV).
 */
@RunWith(AndroidJUnit4::class)
class LauncherProviderTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val launcher = TvLauncher(context)
    private val open = Intent(context, MainActivity::class.java)

    @After
    fun tearDown() = launcher.clear()

    private fun keys(uri: android.net.Uri, column: String): List<String> =
        context.contentResolver.query(uri, arrayOf(BaseColumns._ID, column), null, null, null)?.use { c ->
            buildList { while (c.moveToNext()) c.getString(1)?.let(::add) }
        }.orEmpty()

    private fun playNext() =
        keys(TvContractCompat.WatchNextPrograms.CONTENT_URI, TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID)

    private fun channels() = keys(TvContractCompat.Channels.CONTENT_URI, TvContractCompat.Channels.COLUMN_INTERNAL_PROVIDER_ID)

    @Test
    fun the_tv_takes_the_cards_and_clear_takes_them_off() {
        assumeTrue("a TV with the TV provider", context.packageManager.resolveContentProvider(TvContractCompat.AUTHORITY, 0) != null)
        val film = PlayNextCard("test:1", "A film", null, false, 60_000, 6_000_000, System.currentTimeMillis(), null, open)
        val episode = PlayNextCard("test:2", "A series", PlayNextCard.Episode(1, 2, "Second"), true, 0, 1_440_000, 1, null, open)
        val rows = LauncherRows(listOf(film, episode), "Favourite channels", listOf(ChannelCard("test:c", "A channel", null, open)), open)

        launcher.publish(rows)
        assertTrue("the launcher's parts are on", launcher.isOn())
        assertEquals(setOf("test:1", "test:2"), playNext().toSet())
        assertEquals(listOf("favourites"), channels())

        // Again, with one card gone: no second channel, and the card taken off.
        launcher.publish(rows.copy(playNext = listOf(film)))
        assertEquals(listOf("test:1"), playNext())
        assertEquals(listOf("favourites"), channels())

        launcher.clear()
        assertEquals(emptyList<String>(), playNext())
        assertEquals(emptyList<String>(), channels())
        assertFalse("the launcher's parts are off", launcher.isOn())
    }
}
