package com.biskyt.mytvplayer.player

import android.media.AudioTrack
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Sends decoded surround sound (16-bit PCM with 3 to 8 channels, mostly AAC) as Dolby Digital 5.1, encoded in the
 * app ("Send surround sound as Dolby Digital"). This is for a TV that mixes the app's sound down to stereo but sends
 * Dolby Digital on to the soundbar unchanged. Stereo and mono, and sound sent unchanged (Dolby, DTS), are not changed.
 * It is used only when the TV accepts Dolby Digital. If the encoder fails, it is turned off until the app restarts,
 * and the player's retry plays the sound as before.
 */
@OptIn(UnstableApi::class)
class DolbyDigitalSink(
    sink: AudioSink,
    private val openEncoder: (channels: Int, rate: Int, bitrate: Int) -> FrameEncoder? = Ac3Encoder::open
) : ForwardingAudioSink(sink) {
    /** What is being encoded: set by [configure] for surround sound, else null. */
    private class Encoding(val encoder: FrameEncoder, val input: Format) {
        val mix = mixFor(input.channelCount)
        val frameBytes = Util.getPcmFrameSize(input.pcmEncoding, input.channelCount)

        /** One frame of samples being gathered (interleaved, in 5.1). */
        val block = ShortArray(encoder.frameSize * CHANNELS)
    }

    private var encoding: Encoding? = null
    private var filled = 0

    /** The time of the block's first sample; also of [pending], which is sent before the next block starts. */
    private var blockPtsUs = 0L

    /** An encoded frame the output did not take yet. */
    private var pending: ByteBuffer? = null

    /** The input buffer being read (Media3 gives it again until it is all read), where it started, and its samples. */
    private var current: ByteBuffer? = null
    private var currentStart = 0
    private var samples: ByteBuffer? = null

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        close()
        // A channel map (Media3's fix for decoders that put channels in another order) is left to the output sink.
        val ac3 = if (failed || outputChannels != null) null else ac3For(inputFormat)?.takeIf { super.supportsFormat(it) }
        val e = ac3?.let { openEncoder(CHANNELS, it.sampleRate, it.averageBitrate) }
        if (ac3 == null || e == null) {
            super.configure(inputFormat, specifiedBufferSize, outputChannels)
            return
        }
        Log.i(TAG, "Sending ${inputFormat.channelCount} channels as Dolby Digital 5.1")
        encoding = Encoding(e, inputFormat)
        super.configure(ac3, 0, null)
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        val enc = encoding ?: return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        if (buffer !== current) {
            current = buffer
            currentStart = buffer.position()
            samples = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        }
        val samples = checkNotNull(samples)
        while (true) {
            pending?.let {
                if (!super.handleBuffer(it, blockPtsUs, 1)) return false
                pending = null
            }
            if (buffer.remaining() < enc.frameBytes) {
                buffer.position(buffer.limit())
                current = null
                return true
            }
            if (filled == 0) {
                val read = (buffer.position() - currentStart) / enc.frameBytes
                blockPtsUs = presentationTimeUs + Util.sampleCountToDurationUs(read.toLong(), enc.input.sampleRate)
            }
            mixInto(enc.block, filled * CHANNELS, samples, buffer.position(), enc.mix)
            buffer.position(buffer.position() + enc.frameBytes)
            if (++filled == enc.encoder.frameSize) {
                filled = 0
                // Media3 takes little-endian buffers only.
                pending = encode(enc)?.let { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN) }
            }
        }
    }

    private fun encode(enc: Encoding): ByteArray? = try {
        enc.encoder.encode(enc.block)
    } catch (e: IllegalStateException) {
        Log.w(TAG, "The Dolby Digital encoder failed: it is off until the app restarts", e)
        failed = true
        throw AudioSink.WriteException(AudioTrack.ERROR, enc.input, false)
    }

    override fun flush() {
        clearGathered()
        super.flush()
    }

    override fun reset() {
        close()
        super.reset()
    }

    override fun release() {
        close()
        super.release()
    }

    private fun close() {
        encoding?.encoder?.close()
        encoding = null
        clearGathered()
    }

    private fun clearGathered() {
        filled = 0
        pending = null
        current = null
        samples = null
    }

    companion object {
        private const val TAG = "DolbyDigitalSink"
        private const val CHANNELS = 6
        private const val BITRATE = 640_000

        /** True after the encoder failed once: sound is then played as before, until the app restarts. */
        @Volatile
        internal var failed = false

        /** The Dolby Digital 5.1 format [input] is sent as: decoded 16-bit surround sound; null for other sound. */
        internal fun ac3For(input: Format): Format? {
            if (input.sampleMimeType != MimeTypes.AUDIO_RAW || input.pcmEncoding != C.ENCODING_PCM_16BIT) return null
            if (input.sampleRate !in RATES || input.channelCount !in 3..8) return null
            return Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_AC3)
                .setChannelCount(CHANNELS)
                .setSampleRate(input.sampleRate)
                .setAverageBitrate(BITRATE)
                .build()
        }

        /** The sample rates Dolby Digital has. */
        private val RATES = setOf(32_000, 44_100, 48_000)

        /**
         * For each 5.1 channel (front left, front right, centre, LFE, rear left, rear right), the input channels mixed
         * into it, for Android's channel order with [channels] channels. An empty list is silence.
         */
        internal fun mixFor(channels: Int): Array<IntArray> = when (channels) {
            3 -> arrayOf(intArrayOf(0), intArrayOf(1), intArrayOf(2), intArrayOf(), intArrayOf(), intArrayOf())
            4 -> arrayOf(intArrayOf(0), intArrayOf(1), intArrayOf(), intArrayOf(), intArrayOf(2), intArrayOf(3))
            5 -> arrayOf(intArrayOf(0), intArrayOf(1), intArrayOf(2), intArrayOf(), intArrayOf(3), intArrayOf(4))
            6 -> Array(CHANNELS) { intArrayOf(it) }
            // 6.1: the back centre goes to both rears.
            7 -> arrayOf(intArrayOf(0), intArrayOf(1), intArrayOf(2), intArrayOf(3), intArrayOf(4, 6), intArrayOf(5, 6))
            // 7.1: each side goes to the rear on its side.
            8 -> arrayOf(intArrayOf(0), intArrayOf(1), intArrayOf(2), intArrayOf(3), intArrayOf(4, 6), intArrayOf(5, 7))
            else -> throw IllegalArgumentException("$channels channels")
        }

        /** Writes one input frame (from [samples] at byte [at]) into [block] at [offset], mixed by [mix]. */
        internal fun mixInto(block: ShortArray, offset: Int, samples: ByteBuffer, at: Int, mix: Array<IntArray>) {
            for (c in mix.indices) {
                val from = mix[c]
                block[offset + c] = when (from.size) {
                    0 -> 0
                    1 -> samples.getShort(at + 2 * from[0])
                    // Two channels together, each at -3 dB, kept in range.
                    else -> (from.sumOf { samples.getShort(at + 2 * it).toInt() } * HALF_POWER)
                        .toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
            }
        }

        private const val HALF_POWER = 0.70710677f
    }
}
