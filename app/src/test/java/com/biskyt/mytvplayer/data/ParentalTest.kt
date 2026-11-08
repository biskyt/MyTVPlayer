package com.biskyt.mytvplayer.data

import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.data.db.CAT_LIVE
import com.biskyt.mytvplayer.data.db.CAT_MOVIE
import com.biskyt.mytvplayer.data.db.ParentalEntity
import com.biskyt.mytvplayer.data.db.RecentEntity
import com.biskyt.mytvplayer.data.db.VOD_MOVIE
import com.biskyt.mytvplayer.data.db.WatchProgressEntity
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.addHomeTv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Parental controls: adult categories start restricted; restricted channels, movies and series are left out while
 * locked (a PIN set and not entered), and from search while that setting is on. What is watched in them is not kept for
 * recently watched or "Continue watching" while those settings are on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class)
class ParentalTest {
    private val provider = FakeProvider()
    private val c = ApplicationProvider.getApplicationContext<TestApp>().container

    @After
    fun tearDown() = provider.close()

    /** Waits for the parental row (written in the background) to be [expected]. */
    private suspend fun stateIs(expected: ParentalEntity) = withTimeout(5_000) {
        while (c.db.parental().get() != expected) delay(20)
    }

    private fun state(locked: Boolean = false, recent: Boolean = true, carryOn: Boolean = true, search: Boolean = true) =
        ParentalEntity(locked = locked, hideRecent = recent, hideCarryOn = carryOn, hideSearch = search)

    /** UK | ENTERTAINMENT (channels 101 and 103) restricted. */
    private suspend fun restrictEntertainment(id: Long) = c.parental.setRestricted(id, CAT_LIVE, "1", true)

    @Test
    fun `a PIN is kept hashed, and only the same PIN matches`() {
        val stored = Pin.hash("1234")
        assertFalse("1234" in stored)
        assertTrue(Pin.matches("1234", stored))
        assertFalse(Pin.matches("4321", stored))
        assertNotEquals(stored, Pin.hash("1234")) // a new salt each time
    }

    @Test
    fun `adult categories start restricted, and the user's choice is kept`() = runBlocking {
        provider.categories += listOf("5" to "UK | XXX")
        val id = c.addHomeTv(provider)
        suspend fun restricted() = c.db.categories().all(id, CAT_LIVE).associate { it.categoryId to it.restricted }
        assertEquals(mapOf("1" to false, "2" to false, "3" to false, "4" to false, "5" to true), restricted())

        c.parental.setRestricted(id, CAT_LIVE, "5", false)
        restrictEntertainment(id)
        c.playlists.syncChannels(id)
        assertEquals(mapOf("1" to true, "2" to false, "3" to false, "4" to false, "5" to false), restricted())
    }

    @Test
    fun `restricted channels are hidden while locked, and shown once the PIN is entered`() = runBlocking {
        val id = c.addHomeTv(provider)
        restrictEntertainment(id)
        val all = listOf(101L, 102L, 103L) // the UK categories, chosen to start with
        // No PIN: nothing to unlock, so nothing is hidden from the lists.
        stateIs(state())
        assertEquals(all, c.db.channels().idsAll(id))

        c.parental.setPin("1234")
        c.parental.lock() // as leaving the app does
        stateIs(state(locked = true))
        assertEquals(listOf(102L), c.db.channels().idsAll(id))
        assertEquals(listOf<Long>(), c.db.channels().idsCategory(id, "1"))
        assertEquals(1, c.db.channels().observeCount(id).first())

        assertFalse(c.parental.unlock("0000"))
        assertTrue(c.parental.unlock("1234"))
        stateIs(state())
        assertEquals(all, c.db.channels().idsAll(id))
    }

    /** Waits until [check] is true: what is forgotten after a settings change goes in the background. */
    private suspend fun eventually(check: suspend () -> Boolean) = withTimeout(5_000) {
        while (!check()) delay(20)
    }

    @Test
    fun `search leaves restricted channels out while its setting is on`() = runBlocking {
        val id = c.addHomeTv(provider)
        restrictEntertainment(id)
        stateIs(state())
        assertEquals(listOf<Long>(), c.db.channels().search(id, "ONE", 10).map { it.streamId })

        c.settings.update { it.copy(hideRestrictedSearch = false) }
        stateIs(state(search = false))
        assertEquals(listOf(101L), c.db.channels().search(id, "ONE", 10).map { it.streamId })
    }

    @Test
    fun `restricted channels are not kept in recently watched while its setting is on`() = runBlocking {
        val id = c.addHomeTv(provider)
        stateIs(state())
        c.channels.markWatched(id, 101)
        c.channels.markWatched(id, 102)
        restrictEntertainment(id) // forgets 101, watched before it was restricted
        c.channels.markWatched(id, 103) // restricted: not kept
        c.settings.update { it.copy(hideRestrictedRecent = false) }
        stateIs(state(recent = false))
        // Turning the setting off shows nothing that was watched in restricted categories.
        assertEquals(listOf(102L), c.db.channels().idsRecent(id))

        c.channels.markWatched(id, 101)
        assertEquals(listOf(101L, 102L), c.db.channels().idsRecent(id))
        // Turning it on again forgets it.
        c.settings.update { it.copy(hideRestrictedRecent = true) }
        eventually { c.db.channels().idsRecent(id) == listOf(102L) }
    }

    @Test
    fun `turning a hide setting off first forgets restricted history that was kept some other way`() = runBlocking {
        val id = c.addHomeTv(provider)
        restrictEntertainment(id)
        stateIs(state())
        c.channels.markWatched(id, 102)
        // As if a sync had moved a watched channel into a restricted category since.
        c.db.favourites().touchRecent(RecentEntity(id, 101, System.currentTimeMillis()))
        c.settings.update { it.copy(hideRestrictedRecent = false) }
        stateIs(state(recent = false))
        assertEquals(listOf(102L), c.db.channels().idsRecent(id))
    }

    @Test
    fun `a restricted channel watched does not push a kept one out of recently watched`() = runBlocking {
        val id = c.addHomeTv(provider)
        restrictEntertainment(id)
        stateIs(state())
        // A full list (30 is ChannelRepository's limit) of other channels.
        val kept = (1L..30L).map { 1_000 + it }
        kept.forEach { c.db.favourites().touchRecent(RecentEntity(id, it, it)) }
        c.channels.markWatched(id, 101)
        val left = withContext(Dispatchers.IO) {
            c.db.query("SELECT streamId FROM recent WHERE playlistId = ?", arrayOf(id)).use { cur ->
                buildList { while (cur.moveToNext()) add(cur.getLong(0)) }
            }
        }
        assertEquals(kept, left.sorted())
    }

    @Test
    fun `a restore with the hide setting off keeps the restricted history from the backup`() = runBlocking {
        val id = c.addHomeTv(provider, vod = true)
        c.parental.setRestricted(id, CAT_MOVIE, "10", true) // UK | MOVIES: 501 and 502
        c.settings.update { it.copy(hideRestrictedCarryOn = false) }
        stateIs(state(carryOn = false))
        c.vod.saveProgress(WatchProgressEntity(id, VOD_MOVIE, 501, 600_000, 3_600_000, 1_000, "t", null))
        val backup = c.backup.export()

        c.settings.update { it.copy(hideRestrictedCarryOn = true) }
        eventually { c.vod.progress(id, VOD_MOVIE, 501) == null }
        c.backup.apply(c.backup.read(backup.inputStream()))
        assertFalse(c.settings.current().hideRestrictedCarryOn)
        assertEquals(600_000L, c.vod.progress(id, VOD_MOVIE, 501)?.positionMs)
    }

    @Test
    fun `restricted movies are not kept for carry on watching while its setting is on`() = runBlocking {
        val id = c.addHomeTv(provider, vod = true)
        stateIs(state())
        c.parental.setRestricted(id, CAT_MOVIE, "10", true) // UK | MOVIES: 501 and 502
        suspend fun save(movie: Long, at: Long) =
            c.vod.saveProgress(WatchProgressEntity(id, VOD_MOVIE, movie, 600_000, 3_600_000, at, "t", null))
        save(501, 1_000)
        save(701, 1_001)
        assertEquals(null, c.vod.progress(id, VOD_MOVIE, 501))
        suspend fun carryOn() = c.db.vod().observeCarryOn(id, 10, WatchProgressEntity.FINISHED_FRACTION).first().map { it.itemId }

        c.settings.update { it.copy(hideRestrictedCarryOn = false) }
        stateIs(state(carryOn = false))
        assertEquals(listOf(701L), carryOn())

        save(501, 1_000)
        assertEquals(listOf(701L, 501L), carryOn())
        c.settings.update { it.copy(hideRestrictedCarryOn = true) }
        eventually { c.vod.progress(id, VOD_MOVIE, 501) == null }
        assertEquals(listOf(701L), carryOn())
    }

    @Test
    fun `film search names an adult category with XXX first, as the category lists do`() = runBlocking {
        provider.movieCategories += listOf("13" to "GLOBAL: ADULT MOVIES")
        provider.movies += listOf(Triple(901L, "Night Film", "13"))
        val id = c.addHomeTv(provider, vod = true)
        // Adult categories are left out to start with: include it and download its films.
        c.db.categories().setIncluded(id, CAT_MOVIE, listOf("13"), true)
        c.parental.setRestricted(id, CAT_MOVIE, "13", false)
        c.vod.sync(c.playlists.get(id)!!)
        val hit = c.db.vod().searchMovies(id, "Night", 10).single()
        assertEquals("XXX: ADULT MOVIES", categoryLabel(hit.category!!, hit.categoryLang))
    }

    @Test
    fun `leaving the app after unlocking closes what may show restricted content`() = runBlocking {
        c.parental.setPin("1234") // whoever sets it is let in
        val closed = async(Dispatchers.Default) { withTimeout(5_000) { c.parental.relocked.first() } }
        c.parental.relocked.subscriptionCount.first { it > 0 }
        c.parental.lock()
        closed.await()
        assertTrue(c.parental.locked.first { it })
    }

    @Test
    fun `backups leave the PIN out, and a restore with a PIN set lifts no restriction`() = runBlocking {
        val id = c.addHomeTv(provider)
        c.parental.setPin("1234")
        val backup = c.backup.export() // Entertainment not restricted, the hide settings on
        assertEquals("", c.backup.read(backup.inputStream()).settings.pinHash)

        val pin = c.settings.current().pinHash
        restrictEntertainment(id)
        c.settings.update { it.copy(hideRestrictedSearch = false) }
        c.backup.apply(c.backup.read(backup.inputStream()))
        assertEquals(pin, c.settings.current().pinHash)
        assertTrue(c.db.categories().all(id, CAT_LIVE).single { it.categoryId == "1" }.restricted)
        assertFalse(c.settings.current().hideRestrictedSearch)
    }

    @Test
    fun `a backup from before parental controls restricts its adult categories`() = runBlocking {
        provider.categories += listOf("5" to "UK | XXX")
        val id = c.addHomeTv(provider)
        c.parental.setRestricted(id, CAT_LIVE, "5", false)
        // As 1.8 wrote it: no "restrictions", nothing restricted.
        val json = Json.parseToJsonElement(c.backup.export().decodeToString()).jsonObject
        val old = JsonObject(json - "restrictions").toString().toByteArray()

        c.backup.apply(c.backup.read(old.inputStream()))
        assertTrue(c.db.categories().all(id, CAT_LIVE).single { it.categoryId == "5" }.restricted)
        // A backup from now keeps the choice.
        c.parental.setRestricted(id, CAT_LIVE, "5", false)
        c.backup.apply(c.backup.read(c.backup.export().inputStream()))
        assertFalse(c.db.categories().all(id, CAT_LIVE).single { it.categoryId == "5" }.restricted)
    }
}
