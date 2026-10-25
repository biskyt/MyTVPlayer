package com.biskyt.mytvplayer.player

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import com.biskyt.mytvplayer.core.player.Hdr10PlusOff
import java.nio.ByteBuffer

/**
 * Decoders as Media3 makes them, except that a Dolby Vision decoder of an HEVC-based stream gets no HDR10+
 * ([Hdr10PlusOff]) while the screen shows Dolby Vision ([dolbyVisionScreen]): some TVs show HDR10 instead of Dolby
 * Vision when it also gets HDR10+. Otherwise (an HEVC decoder, or a screen without Dolby Vision) HDR10+ is left as it is.
 */
@OptIn(UnstableApi::class)
fun dolbyVisionWithoutHdr10Plus(plain: MediaCodecAdapter.Factory, dolbyVisionScreen: () -> Boolean) =
    MediaCodecAdapter.Factory { configuration ->
        val codec = plain.createAdapter(configuration)
        val dolbyVision = configuration.codecInfo.codecMimeType == MimeTypes.VIDEO_DOLBY_VISION
        // HEVC-based Dolby Vision only (dvhe, dvh1): AVC and AV1-based streams are laid out differently.
        val hevc = configuration.format.codecs?.let { it.startsWith("dvhe") || it.startsWith("dvh1") } == true
        if (dolbyVision && hevc && dolbyVisionScreen()) NoHdr10Plus(codec) else codec
    }

/** Whether [context]'s screen shows Dolby Vision. */
fun dolbyVisionScreen(context: Context): Boolean {
    val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY) ?: return false
    @Suppress("DEPRECATION") // its replacement (per display mode) is API 34
    return display.hdrCapabilities?.supportedHdrTypes?.contains(Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION) == true
}

@OptIn(UnstableApi::class)
private class NoHdr10Plus(private val codec: MediaCodecAdapter) : MediaCodecAdapter by codec {
    private val hdr10PlusOff = Hdr10PlusOff()

    // The buffer the renderer last asked for, which it then queues: asking the codec again would cost a call into it
    // and a new buffer object, for every frame.
    private var inputIndex = -1
    private var input: ByteBuffer? = null

    override fun getInputBuffer(index: Int): ByteBuffer? = codec.getInputBuffer(index).also {
        inputIndex = index
        input = it
    }

    override fun queueInputBuffer(index: Int, offset: Int, size: Int, presentationTimeUs: Long, flags: Int) {
        val sample = if (index == inputIndex) input else codec.getInputBuffer(index)
        if (size > 0 && sample != null) hdr10PlusOff.apply(sample, offset, offset + size)
        inputIndex = -1
        input = null
        codec.queueInputBuffer(index, offset, size, presentationTimeUs, flags)
    }

    override fun flush() {
        inputIndex = -1
        input = null
        codec.flush()
    }

    // Kotlin's delegation leaves out the interface's default methods: Media3's asynchronous adapter has its own.
    override fun registerOnBufferAvailableListener(listener: MediaCodecAdapter.OnBufferAvailableListener): Boolean =
        codec.registerOnBufferAvailableListener(listener)
}
