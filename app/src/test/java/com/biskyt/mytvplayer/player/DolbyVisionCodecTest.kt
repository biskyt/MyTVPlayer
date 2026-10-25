package com.biskyt.mytvplayer.player

import android.media.MediaFormat
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import com.biskyt.mytvplayer.testutil.PlainApp
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A Dolby Vision decoder gets no HDR10+ while the screen shows Dolby Vision; any other decoder gets the sample as it is. */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = PlainApp::class)
class DolbyVisionCodecTest {
    private val hdr10Plus =
        byteArrayOf(0, 0, 0, 1, (39 shl 1).toByte(), 1, 4, 9, 0xB5.toByte(), 0, 0x3C, 0, 1, 4, 1, 0x55, 0x66, 0x80.toByte())
    private val slice = byteArrayOf(0, 0, 0, 1, (1 shl 1).toByte(), 1, 0x11)
    private val sample = hdr10Plus + slice

    /** A decoder that holds one input buffer. */
    private inner class Decoder {
        val input: ByteBuffer = ByteBuffer.allocateDirect(4 + sample.size).apply { put(byteArrayOf(9, 9, 9, 9)).put(sample).flip() }
        var queued = 0
        var asked = 0

        val adapter = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(MediaCodecAdapter::class.java)) { _, method, _ ->
            when (method.name) {
                "getInputBuffer" -> input.also { asked++ }
                "queueInputBuffer" -> queued++.let { null }
                else -> null
            }
        } as MediaCodecAdapter

        /** The adapter made for a decoder of [codecType], queueing the sample. */
        fun play(
            codecType: String,
            codecs: String = "dvhe.08.06",
            asRendererDoes: Boolean = false,
            dolbyVisionScreen: Boolean = true
        ): MediaCodecAdapter {
            val info = MediaCodecInfo.newInstance("decoder", MimeTypes.VIDEO_H265, codecType, null, true, false, true, false, false)
            val format = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION).setCodecs(codecs).build()
            val configuration = MediaCodecAdapter.Configuration.createForVideoDecoding(info, MediaFormat(), format, null, null)
            return dolbyVisionWithoutHdr10Plus({ adapter }) { dolbyVisionScreen }.createAdapter(configuration).also {
                if (asRendererDoes) it.getInputBuffer(0)
                it.queueInputBuffer(0, 4, sample.size, 0, 0)
            }
        }

        fun sample() = ByteArray(sample.size).also { input.duplicate().apply { position(4) }.get(it) }
    }

    @Test
    fun `a Dolby Vision decoder gets the sample with HDR10+ switched off`() {
        val decoder = Decoder()
        decoder.play(MimeTypes.VIDEO_DOLBY_VISION)
        assertEquals(1, decoder.queued)
        assertArrayEquals(hdr10Plus.copyOf().also { it[4] = (48 shl 1).toByte() } + slice, decoder.sample())
    }

    @Test
    fun `the buffer the renderer asked for is the one read, without asking again`() {
        val decoder = Decoder()
        decoder.play(MimeTypes.VIDEO_DOLBY_VISION, asRendererDoes = true)
        assertEquals(1, decoder.asked)
        assertArrayEquals(hdr10Plus.copyOf().also { it[4] = (48 shl 1).toByte() } + slice, decoder.sample())
    }

    @Test
    fun `an HEVC decoder gets HDR10+`() {
        val decoder = Decoder()
        decoder.play(MimeTypes.VIDEO_H265)
        assertArrayEquals(sample, decoder.sample())
    }

    @Test
    fun `an AVC-based Dolby Vision stream is left as it is`() {
        val decoder = Decoder()
        decoder.play(MimeTypes.VIDEO_DOLBY_VISION, codecs = "dvav.09.05")
        assertArrayEquals(sample, decoder.sample())
    }

    @Test
    fun `an AV1-based Dolby Vision stream is left as it is`() {
        val decoder = Decoder()
        decoder.play(MimeTypes.VIDEO_DOLBY_VISION, codecs = "dav1.10.06")
        assertArrayEquals(sample, decoder.sample())
    }

    @Test
    fun `HDR10+ stays for a screen without Dolby Vision`() {
        val decoder = Decoder()
        decoder.play(MimeTypes.VIDEO_DOLBY_VISION, dolbyVisionScreen = false)
        assertArrayEquals(sample, decoder.sample())
    }
}
