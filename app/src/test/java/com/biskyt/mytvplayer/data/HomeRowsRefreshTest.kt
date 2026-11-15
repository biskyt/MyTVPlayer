package com.biskyt.mytvplayer.data

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.biskyt.mytvplayer.HomeRowsWorker
import com.biskyt.mytvplayer.testutil.FakeProvider
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.addHomeTv
import com.biskyt.mytvplayer.testutil.eventually
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A launcher's request to refresh the home rows (MyTVLauncher, the owner 2026-11-15): it reaches the app while it is
 * not running, writes the rows again when that setting is on, does nothing when it is off, and is done at most once in
 * a few minutes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class)
class HomeRowsRefreshTest {
    private val provider = FakeProvider()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()

    private var pid = 0L

    @Before
    fun setUp() = runBlocking<Unit> {
        pid = app.container.addHomeTv(provider)
        app.container.channels.setFavourite(pid, 102, true)
    }

    @After
    fun tearDown() = provider.close()

    private fun refresh(): ListenableWorker.Result = runBlocking { TestListenableWorkerBuilder<HomeRowsWorker>(app).build().doWork() }

    /** The app's receiver for the request, if it is on. */
    private fun receiver() = app.packageManager.queryBroadcastReceivers(
        Intent(HomeRowsReceiver.action(app)).setPackage(app.packageName),
        0
    ).singleOrNull()?.activityInfo

    @Test
    fun `the request reaches the app's receiver, exported, while the rows are on, and not while they and the sync are off`() = runBlocking {
        app.container.settings.update { it.copy(launcherRows = true) }
        eventually("the receiver on") { receiver() != null }
        assertEquals(HomeRowsReceiver::class.java.name, receiver()?.name)
        assertTrue(receiver()!!.exported)
        app.container.settings.update { it.copy(launcherRows = false) }
        eventually("the receiver off") { receiver() == null }
    }

    @Test
    fun `with the rows on, what changed is written at once, at most once in a few minutes`() = runBlocking {
        app.getSharedPreferences(HomeRowsWorker.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        app.container.settings.update { it.copy(launcherRows = true) }
        eventually("the first write") { app.launcherWrites.isNotEmpty() }
        // Written otherwise only once changes settle (20 s).
        app.container.channels.setFavourite(pid, 101, true)
        val before = app.launcherWrites.size
        assertEquals(ListenableWorker.Result.success(), refresh())
        assertEquals("written at once", before + 1, app.launcherWrites.size)
        // Asked again at once, with another change: not done again.
        app.container.channels.setFavourite(pid, 101, false)
        refresh()
        assertEquals(before + 1, app.launcherWrites.size)
    }

    @Test
    fun `with the rows off (and no sync), nothing is written`() {
        app.getSharedPreferences(HomeRowsWorker.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        assertEquals(ListenableWorker.Result.success(), refresh())
        assertEquals(0, app.launcherWrites.size)
    }
}
