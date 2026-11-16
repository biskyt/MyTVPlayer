package com.biskyt.mytvplayer.player

import android.content.Context
import android.os.Handler
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/**
 * Media3's audio renderer that also tells [sink] the format of the sound it decodes: the sink sees only the decoded
 * sound, and encodes only AAC.
 */
@OptIn(UnstableApi::class)
class AacSourceRenderer(
    context: Context,
    codecAdapterFactory: MediaCodecAdapter.Factory,
    selector: MediaCodecSelector,
    decoderFallback: Boolean,
    handler: Handler?,
    listener: AudioRendererEventListener?,
    private val sink: DolbyDigitalSink
) : MediaCodecAudioRenderer(context, codecAdapterFactory, selector, decoderFallback, handler, listener, sink) {
    override fun onInputFormatChanged(formatHolder: FormatHolder): DecoderReuseEvaluation? {
        sink.sourceMime = formatHolder.format?.sampleMimeType
        return super.onInputFormatChanged(formatHolder)
    }

    /** Another renderer (the FFmpeg one) can play the next track: its format is not known here. */
    override fun onDisabled() {
        sink.sourceMime = null
        super.onDisabled()
    }

    companion object {
        /** Puts [renderer] in place of Media3's own audio renderer in [renderers]. */
        fun replaceIn(renderers: MutableList<Renderer>, renderer: AacSourceRenderer) {
            val i = renderers.indexOfFirst { it.javaClass == MediaCodecAudioRenderer::class.java }
            if (i >= 0) renderers[i] = renderer else Log.w("AacSourceRenderer", "No MediaCodecAudioRenderer: AAC is not encoded")
        }
    }
}
