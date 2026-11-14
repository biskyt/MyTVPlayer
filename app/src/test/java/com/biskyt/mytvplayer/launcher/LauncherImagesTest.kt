package com.biskyt.mytvplayer.launcher

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.addHomeTv
import java.io.FileNotFoundException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The launcher's pictures come from the app (its proxy, its User-Agent), and only those of the app's own cards. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class)
class LauncherImagesTest {
    private val provider = FakeProvider()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private val poster = byteArrayOf(1, 2, 3, 4)

    @Before
    fun setUp() = runBlocking<Unit> {
        app.container.addHomeTv(provider)
        provider.images = { if (it == "poster.jpg") poster else null }
        Robolectric.setupContentProvider(LauncherImageProvider::class.java, LauncherImages.authority(app))
    }

    @After
    fun tearDown() {
        LauncherImages.clear(app)
        provider.close()
    }

    private fun read(uri: Uri) = app.contentResolver.openInputStream(uri)!!.use { it.readBytes() }

    @Test
    fun `a card's picture is fetched by the app and kept`() {
        val url = provider.url + "art/poster.jpg"
        val uri = LauncherImages.publish(app, listOf(url)).getValue(url)
        assertEquals("content", uri.scheme)
        assertArrayEquals(poster, read(uri))
        assertEquals("one request", 1, provider.requests.count { it.contains("poster.jpg") })
        // Kept: no second request.
        assertArrayEquals(poster, read(uri))
        assertEquals(1, provider.requests.count { it.contains("poster.jpg") })
    }

    @Test
    fun `an address that is not a card's is not fetched`() {
        val stranger = Uri.parse("content://${LauncherImages.authority(app)}/${LauncherImages.keyOf(provider.url + "art/other.jpg")}")
        assertThrows(FileNotFoundException::class.java) { read(stranger) }
        // A card's picture, then no longer a card's.
        val url = provider.url + "art/poster.jpg"
        val uri = LauncherImages.publish(app, listOf(url)).getValue(url)
        LauncherImages.publish(app, emptyList())
        assertThrows(FileNotFoundException::class.java) { read(uri) }
        assertEquals(0, provider.requests.count { it.contains("other.jpg") || it.contains("poster.jpg") })
    }
}
