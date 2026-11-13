package com.biskyt.mytvplayer.data

import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.core.sync.SyncedChoice
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.addHomeTv
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The order of a playlist's guides (the owner, 2026-11-13): when two guides both match a channel, the guide higher in
 * the order is used. Moving a guide matches the channels again; the user's own choices stay.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class)
class GuideOrderTest {
    private val provider = FakeProvider()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private val c get() = app.container
    private var pid = 0L
    private var providers = 0L
    private var extra = 0L

    @Before
    fun setUp() = runBlocking<Unit> {
        pid = c.addHomeTv(provider, guide = true)
        providers = c.db.epg().providerSource(pid)!!.id
        // A second guide with the same channels: channel 101 matches both, by its guide id.
        extra = c.epg.addSource("Extra", provider.url + "xmltv.php?username=me&password=secret&extra=1", pid)
        c.epg.importAll(listOf(pid), only = extra)
    }

    @After
    fun tearDown() = provider.close()

    private fun guideOf(streamId: Long) = runBlocking { c.epg.mappingInfo(pid, listOf(streamId)).single().sourceId }

    @Test
    fun `until the user sets an order, guides stay at place 0 and the provider's is shown first, and after it a new guide goes last`() =
        runBlocking<Unit> {
            assertEquals(listOf(0, 0), c.db.epg().links(pid).map { it.position })
            assertEquals(listOf(providers, extra), c.db.epg().guideOrder(pid))
            assertEquals(providers, guideOf(101))

            c.epg.moveSource(pid, extra, up = true)
            val third = c.epg.addSource("Third", provider.url + "xmltv.php?username=me&password=secret&extra=2", pid)
            assertEquals(listOf(extra, providers, third), c.db.epg().guideOrder(pid))
        }

    @Test
    fun `moving a guide up makes it the one used for channels both have, and is synced`() = runBlocking<Unit> {
        assertTrue(c.epg.moveSource(pid, extra, up = true))
        assertEquals(listOf(extra, providers), c.db.epg().guideOrder(pid))
        c.epg.rematch(pid)
        assertEquals(extra, guideOf(101))
        val order = c.db.choices().all().filter { it.kind == ChoiceKinds.GUIDE_ORDER }.associate { it.itemId to it.value }
        assertEquals(mapOf(c.db.epg().source(extra)!!.url to "0", "" to "1"), order)
    }

    @Test
    fun `guides from before the order keep theirs when one moves`() = runBlocking<Unit> {
        val third = c.epg.addSource("Third", provider.url + "xmltv.php?username=me&password=secret&extra=2", pid)
        // As before database 14: all at place 0, in the order of the provider's guide first, then as added.
        listOf(providers, extra, third).forEach { c.db.epg().setPosition(pid, it, 0) }
        assertEquals(listOf(providers, extra, third), c.db.epg().guideOrder(pid))
        c.epg.moveSource(pid, providers, up = false)
        assertEquals(listOf(extra, providers, third), c.db.epg().guideOrder(pid))
    }

    @Test
    fun `a guide at the end of the order does not move further`() = runBlocking<Unit> {
        assertFalse(c.epg.moveSource(pid, providers, up = true))
        assertFalse(c.epg.moveSource(pid, extra, up = false))
        assertEquals(listOf(providers, extra), c.db.epg().guideOrder(pid))
    }

    @Test
    fun `a guide channel chosen by hand stays when the order changes`() = runBlocking<Unit> {
        c.epg.setManual(pid, 103, providers, "cine.fr")
        c.epg.moveSource(pid, extra, up = true)
        c.epg.rematch(pid)
        assertEquals(providers, guideOf(103))
    }

    @Test
    fun `an order from another TV is taken, and one for a guide not here waits`() = runBlocking<Unit> {
        val synced = SyncedChoices(c.db, c.channels, c.vod, c.categoryStore, c.parental, c.epg)
        val reload = Reload()
        val url = c.db.epg().source(extra)!!.url
        fun choice(id: String, place: Int) = SyncedChoice("p", ChoiceKinds.GUIDE_ORDER, id, place.toString(), 1)
        val applied = synced.apply(
            pid,
            ChoiceKinds.GUIDE_ORDER,
            listOf(choice(url, 0), choice("", 1), choice("http://not.here/", 2)),
            reload
        )
        assertEquals(listOf(url, ""), applied.map { it.id })
        assertTrue("matched again", reload.rematch)
        assertEquals(listOf(extra, providers), c.db.epg().guideOrder(pid))
    }
}
