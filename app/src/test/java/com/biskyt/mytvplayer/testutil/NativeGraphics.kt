package com.biskyt.mytvplayer.testutil

import android.graphics.Bitmap
import java.nio.ByteBuffer

/**
 * Has Robolectric's native graphics look up the java.nio buffer classes now, on this thread. It does that once per test
 * process, on whichever thread first needs them, and isn't safe when two threads get there together (two images decoded
 * at once): one of them looks the classes up with the other's JNI environment, fails, and aborts the whole test process
 * ("JniConstants … Class not found: java/nio/FloatBuffer", exit 134). On a device the runtime does this at start-up.
 */
fun lookUpNioClasses() {
    val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    bitmap.copyPixelsToBuffer(ByteBuffer.allocate(bitmap.byteCount))
    bitmap.recycle()
}
