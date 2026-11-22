package com.biskyt.mytvplayer.data

import android.content.Context
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestForegroundUpdater
import androidx.work.testing.TestListenableWorkerBuilder
import com.biskyt.mytvplayer.core.vod.DownloadNames
import com.biskyt.mytvplayer.testutil.TestApp
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A download is saved in the Download folder, in its title's folder, and a failed one leaves nothing behind. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestApp::class) // Android 9: a plain file (later versions go through MediaStore)
class DownloadsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val server = MockWebServer()
    private val place = DownloadNames.movie("Dune", 2021, "mkv")

    @After
    fun tearDown() = server.shutdown()

    @Suppress("DEPRECATION")
    private fun saved(name: String) =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "${place.folder}/$name")

    private fun download(): ListenableWorker.Result = runBlocking {
        TestListenableWorkerBuilder<DownloadWorker>(context)
            .setInputData(Downloads.data(0, server.url("/movie/me/secret/501.mkv").toString(), place))
            .setForegroundUpdater(TestForegroundUpdater())
            .build()
            .doWork()
    }

    @Test
    fun `the film is saved in Download, in its own folder under Movies`() {
        val film = "0123456789".repeat(20_000)
        server.enqueue(MockResponse().setBody(film))
        assertEquals(ListenableWorker.Result.success(), download())
        assertEquals(film, saved("Dune (2021).mkv").readText())
        assertFalse(saved("Dune (2021).mkv.part").exists())
        // As the player asks for the stream: with the app's User-Agent.
        assertEquals(true, server.takeRequest().getHeader("User-Agent")?.startsWith("MyTVPlayer/"))
    }

    @Test
    fun `a server that refuses it is tried again later, with nothing left behind`() {
        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals(ListenableWorker.Result.retry(), download())
        assertFalse(saved("Dune (2021).mkv").exists())
        assertFalse(saved("Dune (2021).mkv.part").exists())
    }
}
