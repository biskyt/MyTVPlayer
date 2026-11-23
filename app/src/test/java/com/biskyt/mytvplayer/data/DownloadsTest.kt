package com.biskyt.mytvplayer.data

import android.app.DownloadManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.core.vod.DownloadNames
import com.biskyt.mytvplayer.testutil.TestApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** A download goes to Android's download manager: to the Download folder, in its title's folder, with the app's User-Agent. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class)
class DownloadsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(DownloadManager::class.java)
    private val place = DownloadNames.movie("Dune", 2021, "mkv")
    private val url = "http://example.com/movie/me/secret/501.mkv"

    private fun requests() = shadowOf(manager).requestCount

    @Test
    fun `the film is saved in Download, in its own folder under Movies, with the app's User-Agent`() {
        val id = Downloads.start(context, url, place)!!
        assertEquals(1, requests())
        val request = shadowOf(shadowOf(manager).getRequest(id))
        assertEquals(url, request.uri.toString())
        assertEquals("Dune (2021).mkv", request.title)
        assertTrue(request.destination.toString(), request.destination.toString().endsWith("/Download/Movies/Dune (2021)/Dune (2021).mkv"))
        val agent = request.requestHeaders.single { it.first == "User-Agent" }.second
        assertTrue(agent, agent.startsWith("MyTVPlayer/"))
        assertEquals(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED, request.notificationVisibility)
    }

    @Test
    fun `the same file asked for again while it downloads is not fetched twice`() {
        assertNotNull(Downloads.start(context, url, place))
        assertNull(Downloads.start(context, url, place))
        assertEquals(1, requests())
    }
}
