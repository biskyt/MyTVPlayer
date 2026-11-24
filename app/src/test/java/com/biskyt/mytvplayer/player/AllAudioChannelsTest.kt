package com.biskyt.mytvplayer.player

import android.media.MediaFormat
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import com.biskyt.mytvplayer.testutil.PlainApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Before Android 12L (API 32), an audio decoder is asked for all of a track's channels (the owner's Android 11 TV, 2026-11-15). */
@RunWith(RobolectricTestRunner::class)
@Config(application = PlainApp::class)
class AllAudioChannelsTest {
    /** The format [allAudioChannels] hands to the decoder, for a decoder of [mime]. */
    private fun configured(mime: String): MediaFormat {
        var seen: MediaFormat? = null
        val factory = allAudioChannels { configuration ->
            seen = configuration.mediaFormat
            throw UnsupportedOperationException("not made in a test")
        }
        val info = MediaCodecInfo.newInstance("test.decoder", mime, mime, null, false, true, false, false, false)
        val configuration = MediaCodecAdapter.Configuration.createForAudioDecoding(
            info,
            MediaFormat.createAudioFormat(mime, 48_000, 6),
            Format.Builder().setSampleMimeType(mime).build(),
            null,
            null
        )
        runCatching { factory.createAdapter(configuration) }
        return checkNotNull(seen)
    }

    @Test
    @Config(sdk = [30, 31])
    fun `on Android 11 and 12 an audio decoder is asked for all channels`() {
        val format = configured(MimeTypes.AUDIO_AAC)
        assertEquals(99, format.getInteger("max-output-channel-count"))
        assertEquals(8, format.getInteger(AAC_MAX_OUTPUT_CHANNELS))
    }

    @Test
    @Config(sdk = [34])
    fun `from Android 12L Media3 asks itself`() {
        assertFalse(configured(MimeTypes.AUDIO_AAC).containsKey("max-output-channel-count"))
    }
}
