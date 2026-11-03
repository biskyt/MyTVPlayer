package com.biskyt.mytvplayer.core.sync

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun `list reads the app folder`() = runTest {
        server.enqueue(MockResponse().setBody("""{"files":[{"id":"1","name":"progress-a.json.gz","modifiedTime":"t1"}]}"""))
        assertEquals(listOf(SyncFile("1", "progress-a.json.gz", "t1")), store().list())
        val r = server.takeRequest()
        assertTrue(r.path!!, r.path!!.startsWith("/drive/v3/files?spaces=appDataFolder"))
        assertEquals("Bearer old", r.getHeader("Authorization"))
    }

    @Test
    fun `create puts the file in the app folder, and update and download send the bytes`() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":"9"}"""))
        assertEquals("9", store().create("progress-a.json.gz", byteArrayOf(1, 2)))
        val created = server.takeRequest()
        assertEquals("POST", created.method)
        assertTrue(created.body.readUtf8().contains(""""parents":["appDataFolder"]"""))

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

    @Test(expected = SyncException::class)
    fun `an error status fails`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        store().list()
    }
}
