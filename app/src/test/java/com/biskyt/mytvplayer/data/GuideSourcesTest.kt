package com.biskyt.mytvplayer.data

import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.AppContainer
import com.biskyt.mytvplayer.data.db.PlaylistEpgSourceEntity
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.addHomeTv
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A guide switched off is off: its programmes aren't shown, and channels aren't matched to it. Guides are switched on
 * per playlist: a playlist uses only its own, and only those of the playlist in use are updated.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class)
class GuideSourcesTest {
    private val provider = FakeProvider()
    private val other = FakeProvider()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()

    @After
    fun tearDown() {
        provider.close()
        other.close()
    }

    /** A second playlist, "Other TV" (from [other]), with its own guide loaded. Adding it switches to it. */
    private suspend fun AppContainer.addOtherTv(): Long {
        val id = playlists.add("Other TV", other.url, "me", "secret")
        playlists.syncChannels(id)
        sync.updateOutOfDateGuides(id).getOrThrow() // as the switch does, settled before the test goes on
        return id
    }

    private fun FakeProvider.guideDownloads() = requests.count { it.startsWith("/xmltv.php") }

    private fun AppContainer.onNow(pid: Long) = runBlocking {
        val now = System.currentTimeMillis()
        epg.programmes(pid, listOf(101), now, now + 60_000)[101].orEmpty().map { it.title }
    }

    @Test
    fun `a guide switched off is neither shown nor matched, and switched on again comes back`() = runBlocking {
        val c = app.container
        val id = c.addHomeTv(provider, guide = true)
        val now = System.currentTimeMillis()
        suspend fun onNow() = c.epg.programmes(id, listOf(101), now, now + 60_000)[101].orEmpty().map { it.title }
        assertEquals(listOf("Now on one.uk"), onNow())
        val source = c.db.epg().sourcesFor(listOf(id)).single()

        c.epg.setSourceOn(id, source, false)
        assertEquals(listOf<String>(), onNow())
        assertEquals(listOf<String>(), c.epg.searchGuideChannels(id, "one").map { it.xmlId }) // nor offered to choose
        c.epg.autoMatch(id, MatchScope.ALL)
        assertTrue(c.epg.mappingInfo(id, listOf(101L)).all { it.sourceId == null })

        c.epg.setSourceOn(id, source, true)
        c.epg.importAll(listOf(id))
        assertEquals(listOf("Now on one.uk"), onNow())
    }

    @Test
    fun `a guide channel chosen by hand comes back with its guide`() = runBlocking {
        val c = app.container
        val id = c.addHomeTv(provider, guide = true)
        val source = c.db.epg().sourcesFor(listOf(id)).single()
        c.epg.setManual(id, 103, source.id, "cine.fr")
        val now = System.currentTimeMillis()
        suspend fun onNow() = c.epg.programmes(id, listOf(103), now, now + 60_000)[103].orEmpty().map { it.title }
        // The choice's programmes are loaded shortly after (debounced, in case of more choices).
        withTimeout(10_000) { while (onNow().isEmpty()) delay(100) }

        c.epg.setSourceOn(id, source, false)
        c.epg.setSourceOn(id, source, true)
        c.epg.importAll(listOf(id)) // an ordinary update, the guide unchanged on the server
        assertEquals(listOf("Now on cine.fr"), onNow())
    }

    @Test
    fun `a playlist uses only the guides switched on for it, and one used by two is stored once`() = runBlocking {
        val c = app.container
        val home = c.addHomeTv(provider, guide = true)
        val homeGuide = c.db.epg().sourcesFor(listOf(home)).single()
        val otherTv = c.addOtherTv()
        val otherGuide = c.db.epg().sourcesFor(listOf(otherTv)).single()

        // Its own guide off: Home TV's (on for Home TV) isn't used for it.
        c.epg.setSourceOn(otherTv, otherGuide, false)
        c.epg.importAll(listOf(otherTv))
        assertEquals(listOf<String>(), c.onNow(otherTv))
        assertTrue(c.epg.mappingInfo(otherTv, listOf(101L)).all { it.sourceId == null }) // not matched to it
        assertEquals(listOf<String>(), c.epg.searchGuideChannels(otherTv, "one").map { it.xmlId })
        assertEquals(listOf("Now on one.uk"), c.onNow(home))
        // Nor shown for a channel set to it by hand, until it's switched on for Other TV.
        c.epg.setManual(otherTv, 101, homeGuide.id, "one.uk")
        assertEquals(listOf<String>(), c.onNow(otherTv))

        c.epg.setSourceOn(otherTv, homeGuide, true)
        c.epg.importAll(listOf(otherTv))
        assertEquals(listOf("Now on one.uk"), c.onNow(otherTv))
        assertEquals(homeGuide.id, c.epg.mappingInfo(otherTv, listOf(101L)).single().sourceId)
    }

    @Test
    fun `an update downloads only the guides of the playlist in use`() = runBlocking {
        val c = app.container
        val home = c.addHomeTv(provider, guide = true)
        c.addOtherTv()
        c.settings.update { it.copy(activePlaylistId = home) }
        val homeBefore = provider.guideDownloads()
        val otherBefore = other.guideDownloads()

        c.sync.updateGuide().getOrThrow()
        assertTrue(provider.guideDownloads() > homeBefore)
        assertEquals(otherBefore, other.guideDownloads())
    }

    @Test
    fun `switching playlist updates its guides that are out of date, and only those`() = runBlocking {
        val c = app.container
        val home = c.addHomeTv(provider, guide = true)
        val otherTv = c.addOtherTv()
        val otherGuide = c.db.epg().sourcesFor(listOf(otherTv)).single()
        val dayAgo = System.currentTimeMillis() - 24 * 3_600_000L
        c.db.epg().setLastImport(otherGuide.id, dayAgo) // last loaded yesterday
        val homeBefore = provider.guideDownloads()
        val otherBefore = other.guideDownloads()

        c.settings.update { it.copy(activePlaylistId = home) } // its guide is up to date
        c.settings.update { it.copy(activePlaylistId = otherTv) }
        withTimeout(10_000) { while (c.db.epg().sourcesFor(listOf(otherTv)).single().lastImportAt == dayAgo) delay(50) }
        assertTrue(other.guideDownloads() > otherBefore)
        assertEquals(homeBefore, provider.guideDownloads())
    }

    @Test
    fun `a backup keeps which playlists use which guides, and one from before restores those switched on`() = runBlocking {
        val c = app.container
        val home = c.addHomeTv(provider, guide = true)
        val homeGuide = c.db.epg().sourcesFor(listOf(home)).single()
        val otherTv = c.addOtherTv()
        val otherGuide = c.db.epg().sourcesFor(listOf(otherTv)).single()
        c.epg.setSourceOn(otherTv, homeGuide, true)
        c.epg.setSourceOn(otherTv, otherGuide, false)
        suspend fun links() = c.db.backup().guideLinks().toSet()
        val links = links()
        val file = c.backup.export().decodeToString()

        c.backup.apply(c.backup.read(file.byteInputStream()))
        assertEquals(links, links())

        // From before 1.4.0, the guides that were on: a provider's own for its playlist, any other for every playlist.
        val old = Regex(""","guideLinks":\[[^\]]*\]""").replace(file, "")
        assertTrue(old != file)
        c.backup.apply(c.backup.read(old.byteInputStream()))
        assertEquals(setOf(PlaylistEpgSourceEntity(home, homeGuide.id)), links())
    }

    @Test
    fun `a guide switched back on is loaded again if its programmes went, and a shared one isn't added twice`() = runBlocking {
        val c = app.container
        val home = c.addHomeTv(provider, guide = true)
        val homeGuide = c.db.epg().sourcesFor(listOf(home)).single()
        c.epg.setSourceOn(home, homeGuide, false) // no playlist uses it: its programmes go
        assertTrue("needs loading", c.epg.setSourceOn(home, homeGuide, true))

        val otherTv = c.addOtherTv()
        // Still used by Home TV, and up to date: nothing to load.
        assertFalse(c.epg.setSourceOn(otherTv, homeGuide, true))
        c.epg.setSourceOn(otherTv, homeGuide, false)
        // Added again by address for Other TV: the same guide, switched on for it.
        assertEquals(homeGuide.id, c.epg.addSource("Mine", homeGuide.url, otherTv))
        assertEquals(2, c.db.epg().allSourceIds().size)
        assertTrue(homeGuide.id in c.db.epg().sourcesFor(listOf(otherTv)).map { it.id })
    }

    @Test
    fun `a guide only a deleted playlist used loses its programmes`() = runBlocking {
        val c = app.container
        val home = c.addHomeTv(provider, guide = true)
        val extra = c.epg.addSource("Extra", provider.url + "xmltv.php?username=me&password=secret&extra=1", home)
        c.epg.importAll(listOf(home), only = extra)
        c.epg.setManual(home, 103, extra, "cine.fr") // its programmes are loaded shortly after
        fun programmes() = c.db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM programme WHERE sourceId = $extra").use {
            it.moveToFirst()
            it.getInt(0)
        }
        withTimeout(10_000) { while (programmes() == 0) delay(100) }
        c.addOtherTv()
        c.playlists.delete(home)
        assertEquals(0, programmes())
    }

    @Test
    fun `the daily update doesn't download again a guide just loaded`() = runBlocking {
        val c = app.container
        c.addHomeTv(provider, guide = true)
        val before = provider.guideDownloads()
        c.sync.refreshAllNow()
        assertEquals(before, provider.guideDownloads())
    }
}
