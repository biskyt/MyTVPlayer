package com.biskyt.mytvplayer.core.sync

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class DriveStoreTest {
    private val server = MockWebServer()
    private val renewals = mutableListOf<Boolean>()

    private fun store() = DriveStore(OkHttpClient(), { renew ->
        renewals += renew
        if (renew) "new" else "old"
    }, server.url("").toString().trimEnd('/'))

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `list reads the app's own files, not its folders`() = runTest {
        server.enqueue(MockResponse().setBody("""{"files":[{"id":"1","name":"progress-a.json.gz","modifiedTime":"t1"}]}"""))
        assertEquals(listOf(SyncFile("1", "progress-a.json.gz", "t1")), store().list())
        val r = server.takeRequest()
        val q = r.requestUrl!!.queryParameter("q")!!
        assertTrue(q, q.contains("trashed = false") && q.contains("mimeType != 'application/vnd.google-apps.folder'"))
        assertEquals("Bearer old", r.getHeader("Authorization"))
    }

    @Test
    fun `create puts the file in the app folder, and update and download send the bytes`() = runTest {
        // No MyTVPlayer folder yet: it is made, and the file goes in it.
        server.enqueue(MockResponse().setBody("""{"files":[]}"""))
        server.enqueue(MockResponse().setBody("""{"id":"f1"}"""))
        server.enqueue(MockResponse().setBody("""{"id":"9"}"""))
        val store = store()
        assertEquals("9", store.create("progress-a.json.gz", byteArrayOf(1, 2)))
        assertTrue(server.takeRequest().requestUrl!!.queryParameter("q")!!.contains("name = 'MyTVPlayer'"))
        val folder = server.takeRequest()
        assertTrue(folder.body.readUtf8().contains(""""mimeType":"application/vnd.google-apps.folder""""))
        val created = server.takeRequest()
        assertEquals("POST", created.method)
        assertTrue(created.body.readUtf8().contains(""""parents":["f1"]"""))
        // Once the folder is there, it is found and used, not made again.
        server.enqueue(MockResponse().setBody("""{"files":[{"id":"f1","name":"MyTVPlayer"}]}"""))
        server.enqueue(MockResponse().setBody("""{"id":"10"}"""))
        store.create("progress-b.json.gz", byteArrayOf(1))
        server.takeRequest()
        val second = server.takeRequest()
        assertTrue(second.path!!.startsWith("/upload/"))
        assertTrue(second.body.readUtf8().contains(""""parents":["f1"]"""))

        server.enqueue(MockResponse())
        store().update("9", byteArrayOf(3))
        val updated = server.takeRequest()
        assertEquals("PATCH", updated.method)
        assertEquals("/upload/drive/v3/files/9?uploadType=media", updated.path)
        assertArrayEquals(byteArrayOf(3), updated.body.readByteArray())

        server.enqueue(MockResponse().setBody("abc"))
        assertArrayEquals("abc".toByteArray(), store().download("9"))
        assertEquals("/drive/v3/files/9?alt=media", server.takeRequest().path)
    }

    @Test
    fun `a refused token is renewed once`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse())
        store().delete("9")
        assertEquals(listOf(false, true), renewals)
        assertEquals("Bearer new", server.takeRequest().let { server.takeRequest() }.getHeader("Authorization"))
    }

    @Test
    fun `a refusal keeps Drive's reason`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(403)
                .setBody("""{"error":{"code":403,"errors":[{"reason":"accessNotConfigured"}],"message":"Drive API has not been used"}}""")
        )
        try {
            store().list()
            fail("No exception")
        } catch (e: SyncException) {
            assertEquals("accessNotConfigured", e.code)
        }
        server.enqueue(MockResponse().setResponseCode(502).setBody("<html>"))
        try {
            store().list()
            fail("No exception")
        } catch (e: SyncException) {
            assertEquals("HTTP 502", e.code)
        }
    }

    @Test(expected = SyncException::class)
    fun `an error status fails`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        store().list()
    }
}
