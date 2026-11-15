package com.biskyt.mytvplayer.player

import android.content.Context
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider
import androidx.media3.exoplayer.audio.DefaultAudioTrackProvider
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.util.concurrent.ConcurrentHashMap

/**
 * AAC sound sent unchanged to the TV's audio output, for the amplifier to decode (setting "Send AAC unchanged", a test,
 * the owner 2026-11-15). Media3 sends only Dolby and DTS so (its passthrough table has no AAC), so this uses the parts
 * it has for its "offload" mode:
 * - [Sink] says that it takes AAC as it is, so the renderer sends AAC without a decoder, and it turns offload on;
 * - the offload support says yes for AAC only, so Dolby, DTS and PCM are played as before;
 * - the track is a plain direct AAC track, not an offloaded one, which Android sends on as it is (AacPassthrough checks
 *   that the TV takes such a track).
 */
@OptIn(UnstableApi::class)
object AacUnchanged {
    /** An AAC buffer is played from this far ahead: as for passthrough, not offload's many seconds. */
    private const val BUFFER_US = 250_000

    /** At least this many bytes, so that low-bitrate AAC (radio) is not a frame or two at a time. */
    private const val MIN_BUFFER_BYTES = 16 * 1024

    /** The sample rate asked about when a format has none. */
    private const val SAMPLE_RATE = 48_000

    private val SUPPORTED = AudioOffloadSupport.Builder().setIsFormatSupported(true).build()

    /** The AAC types that HDMI carries unchanged. */
    private val ENCODINGS = setOf(C.ENCODING_AAC_LC, C.ENCODING_AAC_HE_V1, C.ENCODING_AAC_HE_V2)

    /** What Android said about each AAC type and channel layout, for this player (each question asks the audio service). */
    private val direct = ConcurrentHashMap<Pair<Int, Int>, Boolean>()

    /** Whether this can be on: Media3 uses offload, which this needs, only from Android 10. */
    val available: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    fun isAac(format: Format) = format.sampleMimeType == MimeTypes.AUDIO_AAC

    /**
     * Whether [format] is sent unchanged: an AAC type that HDMI carries, which the TV's audio output takes as it is (else
     * the TV's decoder plays it, as without the setting).
     */
    fun sendsUnchanged(format: Format): Boolean {
        if (!available || !isAac(format)) return false
        val encoding = MimeTypes.getEncoding(MimeTypes.AUDIO_AAC, format.codecs).takeIf { it in ENCODINGS } ?: return false
        val layout = Util.getAudioTrackChannelConfig(format.channelCount.takeIf { it != Format.NO_VALUE } ?: 2)
        return direct.getOrPut(encoding to layout) { takesDirectly(encoding, layout, format.sampleRate) }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun takesDirectly(encoding: Int, layout: Int, sampleRate: Int): Boolean = try {
        val rate = sampleRate.takeIf { it != Format.NO_VALUE } ?: SAMPLE_RATE
        val format = AudioFormat.Builder().setEncoding(encoding).setSampleRate(rate).setChannelMask(layout).build()
        AudioTrack.isDirectPlaybackSupported(format, PlaybackEngine.MOVIE.audioAttributesV21.audioAttributes)
    } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
        Log.w("AacUnchanged", "Could not ask about direct AAC", e)
        false
    }

    /** Asks Android again (an amplifier turned on or off since): each new player does. */
    internal fun forget() = direct.clear()

    /** The audio output, with AAC sent unchanged where the TV takes it. */
    fun sink(context: Context, floatOutput: Boolean, playbackParams: Boolean): AudioSink = forget().let {
        Sink(
            DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(floatOutput)
                .setEnableAudioTrackPlaybackParams(playbackParams)
                .setAudioOffloadSupportProvider { format, _ ->
                    if (sendsUnchanged(format)) SUPPORTED else AudioOffloadSupport.DEFAULT_UNSUPPORTED
                }
                .setAudioTrackProvider(DirectTracks())
                .setAudioTrackBufferSizeProvider(Buffers())
                .build()
        )
    }

    /** Buffer sizes as Media3 sets them, except for AAC sent unchanged: [BUFFER_US] ahead, [MIN_BUFFER_BYTES] at least. */
    private class Buffers : DefaultAudioTrackBufferSizeProvider(
        DefaultAudioTrackBufferSizeProvider.Builder().setOffloadBufferDurationUs(BUFFER_US)
    ) {
        override fun getOffloadBufferSizeInBytes(encoding: Int): Int = maxOf(MIN_BUFFER_BYTES, super.getOffloadBufferSizeInBytes(encoding))
    }

    /**
     * Tracks as Media3 makes them, except that one for offload (only AAC is sent in offload mode here) is a plain direct
     * track. (Before Android 10, Media3 asks for no offload.)
     */
    internal class DirectTracks : DefaultAudioTrackProvider() {
        override fun customizeAudioTrackBuilder(builder: AudioTrack.Builder): AudioTrack.Builder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setOffloadedPlayback(false) else builder
    }

    /** Takes AAC as it is where the TV does, and keeps offload on (the player turns it off while video plays). */
    internal class Sink(sink: AudioSink) : ForwardingAudioSink(sink) {
        override fun setOffloadMode(offloadMode: Int) = super.setOffloadMode(AudioSink.OFFLOAD_MODE_ENABLED_GAPLESS_NOT_REQUIRED)

        override fun supportsFormat(format: Format) = sendsUnchanged(format) || super.supportsFormat(format)

        override fun getFormatSupport(format: Format) =
            if (sendsUnchanged(format)) AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY else super.getFormatSupport(format)
    }
}
