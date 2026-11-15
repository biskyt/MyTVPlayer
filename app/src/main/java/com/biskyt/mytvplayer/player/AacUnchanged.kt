package com.biskyt.mytvplayer.player

import android.content.Context
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import androidx.annotation.ChecksSdkIntAtLeast
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
import java.nio.ByteBuffer
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
    internal const val SAMPLE_RATE = 48_000

    private val SUPPORTED = AudioOffloadSupport.Builder().setIsFormatSupported(true).build()

    /** The AAC types that HDMI carries unchanged (also what AacPassthrough asks about). */
    internal val ENCODINGS = setOf(C.ENCODING_AAC_LC, C.ENCODING_AAC_HE_V1, C.ENCODING_AAC_HE_V2)

    /**
     * What Android said about each AAC type, channel layout and sample rate (each question asks the audio service), or a
     * track it refused. Asked again for each new player (see [sink]).
     */
    private val direct = ConcurrentHashMap<Triple<Int, Int, Int>, Boolean>()

    /** Whether this can be on: Media3 uses offload, which this needs, only from Android 10. */
    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.Q)
    val available: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    fun isAac(format: Format) = format.sampleMimeType == MimeTypes.AUDIO_AAC

    /**
     * Whether [format] is sent unchanged: an AAC type that HDMI carries, which the TV's audio output takes as it is (else
     * the TV's decoder plays it, as without the setting).
     */
    fun sendsUnchanged(format: Format): Boolean {
        if (!available) return false
        val key = key(format) ?: return false
        // (Two threads can ask at once: then both ask Android, which is better than one waiting for the other.)
        return direct.getOrPut(key) { takesDirectly(key.first, key.second, key.third) }
    }

    /** The AAC type, channel layout and sample rate of [format], if it is AAC that HDMI carries (from Android 10). */
    private fun key(format: Format): Triple<Int, Int, Int>? {
        if (!available || !isAac(format)) return null
        val encoding = MimeTypes.getEncoding(MimeTypes.AUDIO_AAC, format.codecs).takeIf { it in ENCODINGS } ?: return null
        val layout = Util.getAudioTrackChannelConfig(format.channelCount.takeIf { it != Format.NO_VALUE } ?: 2)
        return Triple(encoding, layout, format.sampleRate.takeIf { it != Format.NO_VALUE } ?: SAMPLE_RATE)
    }

    /** A direct track of [format] was refused: it goes to the decoder when the player tries again. */
    internal fun refused(format: Format) {
        val key = key(format) ?: return
        Log.w("AacUnchanged", "The TV refused AAC sent unchanged ($format): decoded from now on")
        direct[key] = false
    }

    /** Whether the TV's audio output takes this AAC as it is (also AacPassthrough's check). */
    @RequiresApi(Build.VERSION_CODES.Q)
    internal fun takesDirectly(encoding: Int, layout: Int, rate: Int): Boolean = try {
        val format = AudioFormat.Builder().setEncoding(encoding).setSampleRate(rate).setChannelMask(layout).build()
        AudioTrack.isDirectPlaybackSupported(format, PlaybackEngine.MOVIE.audioAttributesV21.audioAttributes)
    } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
        Log.w("AacUnchanged", "Could not ask about direct AAC", e)
        false
    }

    /** Asks Android again (an amplifier turned on or off since): each new player does (see [sink]). */
    internal fun forget() = direct.clear()

    /** The audio output, with AAC sent unchanged where the TV takes it. */
    fun sink(context: Context, floatOutput: Boolean, playbackParams: Boolean): AudioSink {
        forget()
        return Sink(
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

    /**
     * Takes AAC as it is where the TV does, and keeps offload on (the player turns it off while video plays). If the TV
     * then refuses the track, the player's error is a plain one (its offload fallback does not run): the format is noted
     * as refused, so the player's next try decodes it.
     */
    internal class Sink(sink: AudioSink) : ForwardingAudioSink(sink) {
        override fun setOffloadMode(offloadMode: Int) = super.setOffloadMode(AudioSink.OFFLOAD_MODE_ENABLED_GAPLESS_NOT_REQUIRED)

        override fun supportsFormat(format: Format) = sendsUnchanged(format) || super.supportsFormat(format)

        override fun getFormatSupport(format: Format) =
            if (sendsUnchanged(format)) AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY else super.getFormatSupport(format)

        override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean = try {
            super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        } catch (e: AudioSink.InitializationException) {
            refused(e.format)
            throw e
        } catch (e: AudioSink.WriteException) {
            // A track made, but its sound refused: the format is the one configured.
            lastFormat?.let(::refused)
            throw e
        }

        /** The format given to [configure] last. */
        private var lastFormat: Format? = null

        override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
            lastFormat = inputFormat
            super.configure(inputFormat, specifiedBufferSize, outputChannels)
        }
    }
}
