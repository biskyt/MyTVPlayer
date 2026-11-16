package com.biskyt.mytvplayer.player

import android.util.Log

/** Encodes frames of interleaved 16-bit samples to Dolby Digital. [DolbyDigitalSink] uses it; tests use a fake. */
interface FrameEncoder : AutoCloseable {
    /** The number of samples for each channel in one frame. */
    val frameSize: Int

    /** Encodes one frame ([frameSize] samples for each channel, interleaved). Returns null while it has no output yet. */
    fun encode(pcm: ShortArray): ByteArray?
}

/**
 * FFmpeg's AC-3 (Dolby Digital) encoder, from `libac3enc.so`. The library is built from source, once, and committed:
 * see native/ac3enc/README.md.
 */
class Ac3Encoder private constructor(private var handle: Long) : FrameEncoder {
    override val frameSize: Int = nativeFrameSize(handle)
    private val out = ByteArray(OUT_BYTES)

    override fun encode(pcm: ShortArray): ByteArray? {
        check(handle != 0L) { "The encoder is closed" }
        val n = nativeEncode(handle, pcm, out)
        check(n >= 0) { "The Dolby Digital encoder failed ($n)" }
        return if (n == 0) null else out.copyOf(n)
    }

    override fun close() {
        if (handle != 0L) nativeClose(handle)
        handle = 0
    }

    companion object {
        /** A frame is at most 3840 bytes (640 kbit/s at 32 kHz). */
        private const val OUT_BYTES = 4096

        /** True when the library loaded: it is built for ARM only, so emulators and JVM tests have no encoder. */
        val available: Boolean by lazy { runCatching { System.loadLibrary("ac3enc") }.isSuccess }

        fun open(channels: Int, rate: Int, bitrate: Int): Ac3Encoder? {
            if (!available) return null
            // A library that does not match this class (its functions are named after it) plays the sound as before.
            val handle = try {
                nativeOpen(channels, rate, bitrate)
            } catch (e: UnsatisfiedLinkError) {
                Log.w("Ac3Encoder", "libac3enc does not match Ac3Encoder", e)
                0L
            }
            return if (handle == 0L) null else Ac3Encoder(handle)
        }

        @JvmStatic private external fun nativeOpen(channels: Int, rate: Int, bitrate: Int): Long

        @JvmStatic private external fun nativeFrameSize(handle: Long): Int

        @JvmStatic private external fun nativeEncode(handle: Long, pcm: ShortArray, out: ByteArray): Int

        @JvmStatic private external fun nativeClose(handle: Long)
    }
}
