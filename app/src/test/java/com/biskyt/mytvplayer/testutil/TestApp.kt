package com.biskyt.mytvplayer.testutil

import android.os.SystemClock
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import com.biskyt.mytvplayer.MyTvApp
import com.biskyt.mytvplayer.launcher.LauncherRows
import com.biskyt.mytvplayer.launcher.LauncherWriter
import com.biskyt.mytvplayer.launcher.Published
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.cancel
import org.robolectric.shadows.ShadowLooper

/** The real app wiring, minus the background scheduler (WorkManager isn't set up under Robolectric). */
class TestApp : MyTvApp() {
    override fun onCreate() {
        lookUpNioClasses() // before any image is loaded
        super.onCreate()
    }

    override fun scheduleDailyRefresh() = Unit

    // Tests set their settings themselves; nothing reads them at start (the library is not there on the JVM).
    override fun loadDolbyEncoder() = Unit

    /**
     * The JVM has no TV provider: the app's own launcher sync writes nowhere, so that a test that turns the setting on
     * (see LauncherSyncTest, with a writer of its own) leaves nothing running into the next test.
     */
    override fun launcherWriter(): LauncherWriter = object : LauncherWriter {
        override fun isOn() = false

        override fun publish(rows: LauncherRows): Published {
            launcherWrites += rows
            return Published(emptySet(), emptySet())
        }

        override fun clear() = Unit
    }

    /** What the app's own launcher sync wrote (to nowhere): for a test of when it writes. */
    val launcherWrites = CopyOnWriteArrayList<LauncherRows>()

    // A process-wide handler: each test's app would wrap the last one's (tests that need it install it themselves).
    override fun installCrashLog() = Unit

    /**
     * Robolectric calls this after each test. Each test gets a new app, but the database and coroutine threads are
     * shared by the whole run: a test's syncs and queries left running would hold them up in the next test (seen as a
     * Room query that never answers, now and then, on busy runners).
     */
    @OptIn(DelicateCoilApi::class)
    override fun onTerminate() {
        try {
            // The image loader is process-wide: the next test's app makes its own, on its own container.
            SingletonImageLoader.reset()
            stopMainThreadEffects()
            runMainLooperDown()
            container.appScope.cancel()
            container.http.dispatcher.cancelAll()
            // Again, for what stopping posted (before the database it may still use is closed).
            runMainLooperDown()
            container.db.close()
        } finally {
            try {
                resetSettings(this)
            } finally {
                super.onTerminate()
            }
        }
    }

    /**
     * Runs what's posted to the main thread, frame by frame, until nothing is due within a frame. Robolectric then
     * empties the main looper and resets the Choreographer, dropping whatever is left. Compose's main-thread dispatcher
     * (AndroidUiDispatcher.Main) is a lazy that lives as long as the test process: once its posted run is dropped it
     * never posts again, and every later test's paged lists (collectAsLazyPagingItems runs there) stay empty.
     */
    private fun runMainLooperDown() {
        val looper = ShadowLooper.shadowMainLooper()
        fun dueSoon(): Boolean {
            val next = looper.nextScheduledTaskTime.toMillis()
            return !looper.isIdle || (next != 0L && next <= SystemClock.uptimeMillis() + FRAME_MS)
        }
        var frames = 0
        while (dueSoon()) {
            check(++frames <= MAX_FRAMES) { "The main thread still had work due after $MAX_FRAMES frames" }
            looper.idleFor(FRAME_MS, TimeUnit.MILLISECONDS)
        }
    }

    private companion object {
        /** A display frame, a little over 16ms. */
        const val FRAME_MS = 17L
        const val MAX_FRAMES = 100
    }
}
