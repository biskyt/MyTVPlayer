package com.biskyt.mytvplayer.testutil

import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Images handled on several threads at once (as Coil decodes them) don't abort the test process (see
 * [lookUpNioClasses]). Without that, this aborts the process whenever it's the first test in it to handle an image.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = PlainApp::class)
class NativeGraphicsTest {
    @Test
    fun `images handled on several threads at once`() {
        val threads = 8
        val together = CyclicBarrier(threads)
        val bitmaps = List(threads) { Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888) }
        val failures = ConcurrentLinkedQueue<Throwable>()
        bitmaps.map { bitmap ->
            Thread {
                runCatching {
                    together.await(WAIT_S, TimeUnit.SECONDS)
                    bitmap.copyPixelsToBuffer(ByteBuffer.allocate(bitmap.byteCount))
                }.onFailure { failures += it }
            }.apply { start() }
        }.forEach {
            it.join(TimeUnit.SECONDS.toMillis(WAIT_S))
            assertFalse("still running", it.isAlive)
        }
        bitmaps.forEach { it.recycle() }
        failures.firstOrNull()?.let { throw it }
    }

    private companion object {
        const val WAIT_S = 10L
    }
}
