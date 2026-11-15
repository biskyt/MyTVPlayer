package com.biskyt.mytvplayer.player

import android.content.Context
import android.media.AudioFormat
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.testutil.PlainApp
import java.lang.reflect.Proxy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioTrack

/** AAC sent unchanged to the TV's audio output (setting "Send AAC unchanged", the owner 2026-11-15). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = PlainApp::class)
class AacUnchangedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val aac = Format.Builder().setSampleMimeType(
        MimeTypes.AUDIO_AAC
    ).setCodecs("mp4a.40.2").setChannelCount(6).setSampleRate(48_000).build()
    private val ac3 = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AC3).setChannelCount(6).setSampleRate(48_000).build()

    /** An audio output that only notes the offload mode it is given, and supports nothing itself. */
    private val offloadModes = mutableListOf<Int>()
    private val inner = Proxy.newProxyInstance(AudioSink::class.java.classLoader, arrayOf(AudioSink::class.java)) { _, method, args ->
        if (method.name == "setOffloadMode") offloadModes += args[0] as Int
        when (method.returnType) {
            Boolean::class.javaPrimitiveType -> false
            Int::class.javaPrimitiveType -> AudioSink.SINK_FORMAT_UNSUPPORTED
            else -> null
        }
    } as AudioSink

    @After
    fun tearDown() {
        ShadowAudioTrack.clearAllowedNonPcmEncodings()
        ShadowAudioTrack.clearDirectPlaybackSupportedFormats()
        AacUnchanged.forget()
    }

    /** The TV takes 5.1 AAC-LC as it is. */
    private fun takeAacDirectly() {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_AAC_LC)
            .setSampleRate(48_000)
            .setChannelMask(AudioFormat.CHANNEL_OUT_5POINT1)
            .build()
        ShadowAudioTrack.addDirectPlaybackSupport(format, PlaybackEngine.MOVIE.audioAttributesV21.audioAttributes)
    }

    @Test
    fun `the output takes AAC as it is where the TV does, and leaves other formats to the player's output`() {
        takeAacDirectly()
        val sink = AacUnchanged.Sink(inner)
        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, sink.getFormatSupport(aac))
        assertTrue(sink.supportsFormat(aac))
        assertEquals(AudioSink.SINK_FORMAT_UNSUPPORTED, sink.getFormatSupport(ac3))
        assertFalse(sink.supportsFormat(ac3))
    }

    @Test
    fun `AAC the TV does not take as it is goes to the decoder, as without the setting`() {
        assertFalse(AacUnchanged.sendsUnchanged(aac))
        assertFalse(AacUnchanged.Sink(inner).supportsFormat(aac))
    }

    @Test
    fun `AAC of a type HDMI does not carry, or with no codec string, goes to the decoder`() {
        takeAacDirectly()
        assertFalse("AAC Main", AacUnchanged.sendsUnchanged(aac.buildUpon().setCodecs("mp4a.40.1").build()))
        assertFalse("no codec string", AacUnchanged.sendsUnchanged(aac.buildUpon().setCodecs(null).build()))
    }

    @Test
    fun `an answer for one sample rate is not used for another`() {
        takeAacDirectly()
        assertTrue(AacUnchanged.sendsUnchanged(aac))
        assertFalse("44.1 kHz", AacUnchanged.sendsUnchanged(aac.buildUpon().setSampleRate(44_100).build()))
    }

    @Test
    fun `a track the TV refuses goes to the decoder when the player tries again`() {
        takeAacDirectly()
        assertTrue(AacUnchanged.sendsUnchanged(aac))
        AacUnchanged.refused(aac)
        assertFalse(AacUnchanged.sendsUnchanged(aac))
        assertFalse(AacUnchanged.Sink(inner).supportsFormat(aac))
    }

    @Test
    fun `a direct track that fails to start is noted as refused, and the error still reaches the player`() {
        takeAacDirectly()
        val failing = Proxy.newProxyInstance(AudioSink::class.java.classLoader, arrayOf(AudioSink::class.java)) { _, method, _ ->
            if (method.name == "handleBuffer") throw AudioSink.InitializationException("refused", 0, aac, false, null)
            when (method.returnType) {
                Boolean::class.javaPrimitiveType -> false
                Int::class.javaPrimitiveType -> AudioSink.SINK_FORMAT_UNSUPPORTED
                else -> null
            }
        } as AudioSink
        val sink = AacUnchanged.Sink(failing)
        assertTrue(AacUnchanged.sendsUnchanged(aac))
        val thrown = runCatching { sink.handleBuffer(java.nio.ByteBuffer.allocate(0), 0, 1) }.exceptionOrNull()
        assertTrue("the player still gets the error", thrown is AudioSink.InitializationException)
        assertFalse("decoded on the next try", AacUnchanged.sendsUnchanged(aac))
    }

    @Test
    @Config(sdk = [28])
    fun `not on Android 9, where Media3 uses no offload`() {
        assertFalse(AacUnchanged.available)
        assertFalse(AacUnchanged.sendsUnchanged(aac))
    }

    @Test
    fun `offload stays on although the player turns it off while video plays`() {
        AacUnchanged.Sink(inner).setOffloadMode(AudioSink.OFFLOAD_MODE_DISABLED)
        assertEquals(listOf(AudioSink.OFFLOAD_MODE_ENABLED_GAPLESS_NOT_REQUIRED), offloadModes)
    }

    @Test
    fun `AAC gets a plain direct track, not an offloaded one`() {
        ShadowAudioTrack.addAllowedNonPcmEncoding(AudioFormat.ENCODING_AAC_LC)
        val config = AudioSink.AudioTrackConfig(AudioFormat.ENCODING_AAC_LC, 48_000, AudioFormat.CHANNEL_OUT_5POINT1, false, true, 16_384)
        val track = AacUnchanged.DirectTracks().getAudioTrack(config, PlaybackEngine.MOVIE, 0, context)
        try {
            assertEquals(AudioFormat.ENCODING_AAC_LC, track.audioFormat)
            assertFalse("not offloaded", track.isOffloadedPlayback)
        } finally {
            track.release()
        }
    }
}
