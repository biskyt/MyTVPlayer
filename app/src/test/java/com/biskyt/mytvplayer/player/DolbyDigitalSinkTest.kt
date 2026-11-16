package com.biskyt.mytvplayer.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import com.biskyt.mytvplayer.testutil.PlainApp
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Decoded surround sound is sent as Dolby Digital 5.1 frames, with the right times; other sound is not changed. */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = PlainApp::class)
class DolbyDigitalSinkTest {
    /** The frames and times the output sink was given, and the format it was set up with. */
    private val written = mutableListOf<Pair<ByteBuffer, Long>>()
    private var configured: Format? = null
    private var accepts = true
    private var takesDolby = true
    private val encoder = FakeEncoder()
    private val sink = DolbyDigitalSink(output()) { channels, _, _ -> encoder.takeIf { channels == 6 } }

    @After
    fun tearDown() {
        DolbyDigitalSink.failed = false
    }

    @Test
    fun `surround sound is sent as Dolby Digital 5_1, one frame for each 4 samples`() {
        sink.configure(pcm(6), 0, null)
        assertEquals(MimeTypes.AUDIO_AC3, configured?.sampleMimeType)
        assertEquals(6, configured?.channelCount)
        assertEquals(640_000, configured?.averageBitrate)
        // 10 frames of input: two whole encoder frames (4 each), and two samples kept for the next buffer.
        assertTrue(sink.handleBuffer(samples(6, 10), 1_000_000, 1))
        assertEquals(listOf(1_000_000L, 1_000_000L + 4 * 1_000_000L / 48_000), written.map { it.second })
        assertTrue(written.all { it.first.order() == ByteOrder.LITTLE_ENDIAN })
        // The next buffer completes the third frame, timed from its first sample (in the buffer before).
        assertTrue(sink.handleBuffer(samples(6, 2, from = 10), 1_000_000 + 10 * 1_000_000L / 48_000, 1))
        assertEquals(1_000_000L + 8 * 1_000_000L / 48_000, written.last().second)
        assertEquals(listOf(0, 4, 8).map { it * 6 }, encoder.frames.map { it[0].toInt() })
    }

    @Test
    fun `stereo, mono and Dolby are not changed`() {
        listOf(pcm(2), pcm(1), Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AC3).setChannelCount(6).build()).forEach {
            sink.configure(it, 0, null)
            assertEquals(it, configured)
        }
        val buffer = samples(2, 4)
        assertTrue(sink.handleBuffer(buffer, 5, 1))
        assertEquals(buffer to 5L, written.single())
    }

    @Test
    fun `sound with a channel map from the player is not encoded`() {
        sink.configure(pcm(6), 0, intArrayOf(0, 2, 1, 4, 5, 3))
        assertEquals(pcm(6), configured)
    }

    @Test
    fun `a TV that doesn't take Dolby Digital gets the sound as before`() {
        takesDolby = false
        sink.configure(pcm(6), 0, null)
        assertEquals(pcm(6), configured)
    }

    @Test
    fun `a frame the output doesn't take yet is sent again, and the rest of the buffer waits`() {
        sink.configure(pcm(6), 0, null)
        accepts = false
        val buffer = samples(6, 6)
        assertFalse(sink.handleBuffer(buffer, 0, 1))
        assertEquals(1, encoder.frames.size)
        accepts = true
        assertTrue(sink.handleBuffer(buffer, 0, 1))
        assertEquals(1, encoder.frames.size) // not encoded twice
        assertEquals(1, written.size)
        assertFalse(buffer.hasRemaining())
    }

    @Test
    fun `a flush drops the samples gathered so far`() {
        sink.configure(pcm(6), 0, null)
        sink.handleBuffer(samples(6, 3), 0, 1)
        sink.flush()
        sink.handleBuffer(samples(6, 4, from = 100), 2_000_000, 1)
        assertEquals(2_000_000L, written.single().second)
        assertEquals(600, encoder.frames.single()[0].toInt())
    }

    @Test
    fun `when the encoder fails, playback stops with an error, and sound then plays as before`() {
        sink.configure(pcm(6), 0, null)
        encoder.fails = true
        try {
            sink.handleBuffer(samples(6, 4), 0, 1)
            fail("No error")
        } catch (e: AudioSink.WriteException) {
            assertFalse(e.isRecoverable)
        }
        sink.configure(pcm(6), 0, null)
        assertEquals(pcm(6), configured)
    }

    @Test
    fun `channels are put in 5_1 order, and extra channels are mixed into the rears`() {
        // Quad: front left, front right, rear left, rear right.
        assertArrayEquals(shortArrayOf(1, 2, 0, 0, 3, 4), mixed(4, 1, 2, 3, 4))
        // 7.1: the sides go into the rears at -3 dB each, kept in range.
        assertArrayEquals(shortArrayOf(1, 2, 3, 4, 141, 0), mixed(8, 1, 2, 3, 4, 100, -100, 100, 100))
        assertEquals(Short.MAX_VALUE, mixed(7, 0, 0, 0, 0, Short.MAX_VALUE, 0, Short.MAX_VALUE)[4])
        assertArrayEquals(shortArrayOf(1, 2, 3, 4, 5, 6), mixed(6, 1, 2, 3, 4, 5, 6))
        assertNull(DolbyDigitalSink.ac3For(pcm(9)))
    }

    private fun mixed(channels: Int, vararg values: Short): ShortArray {
        val block = ShortArray(6)
        val samples = ByteBuffer.allocate(channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { samples.putShort(it) }
        DolbyDigitalSink.mixInto(block, 0, samples, 0, DolbyDigitalSink.mixFor(channels))
        return block
    }

    private fun pcm(channels: Int) = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW)
        .setPcmEncoding(C.ENCODING_PCM_16BIT).setChannelCount(channels).setSampleRate(48_000).build()

    /** [frames] frames of [channels] channels, each sample the frame's number (from [from]) times its channel count. */
    private fun samples(channels: Int, frames: Int, from: Int = 0): ByteBuffer {
        val b = ByteBuffer.allocateDirect(frames * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames) { f -> repeat(channels) { b.putShort(((from + f) * channels).toShort()) } }
        b.flip()
        return b
    }

    /** An encoder with 4 samples in a frame, which keeps each frame it was given. */
    private class FakeEncoder : FrameEncoder {
        override val frameSize = 4
        val frames = mutableListOf<ShortArray>()
        var fails = false

        override fun encode(pcm: ShortArray): ByteArray {
            check(!fails) { "failed" }
            frames += pcm.copyOf()
            return byteArrayOf(0x0B, 0x77)
        }

        override fun close() = Unit
    }

    /** The output sink: it takes every buffer while [accepts], and Dolby Digital while [takesDolby]. */
    private fun output(): AudioSink = Proxy.newProxyInstance(AudioSink::class.java.classLoader, arrayOf(AudioSink::class.java)) {
            _,
            m,
            args
        ->
        val a = args.orEmpty()
        when (m.name) {
            "supportsFormat" -> (a[0] as Format).sampleMimeType != MimeTypes.AUDIO_AC3 || takesDolby
            "configure" -> {
                configured = a[0] as Format
                null
            }
            "handleBuffer" -> accepts && take(a[0] as ByteBuffer, a[1] as Long)
            else -> defaultFor(m.returnType)
        }
    } as AudioSink

    private fun take(buffer: ByteBuffer, timeUs: Long): Boolean {
        written += buffer to timeUs
        buffer.position(buffer.limit())
        return true
    }

    private fun defaultFor(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        else -> null
    }
}
