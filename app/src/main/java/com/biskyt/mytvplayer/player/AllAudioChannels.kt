package com.biskyt.mytvplayer.player

import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter

/**
 * Decoders as [plain] makes them, except that before Android 12L (API 32) an audio decoder is asked for all of a
 * track's channels, as Media3 asks from API 32. Without this, a TV's decoder can mix 5.1 sound down to stereo (the owner's
 * Android 11 TV, 2026-11-15).
 */
@OptIn(UnstableApi::class)
fun allAudioChannels(plain: MediaCodecAdapter.Factory) = MediaCodecAdapter.Factory { configuration ->
    if (Build.VERSION.SDK_INT < MEDIA3_ASKS && MimeTypes.isAudio(configuration.codecInfo.mimeType)) {
        configuration.mediaFormat.setInteger(MAX_OUTPUT_CHANNELS, ANY_CHANNELS)
        // The older AAC-only form of the same request (Android 9 and later).
        configuration.mediaFormat.setInteger(AAC_MAX_OUTPUT_CHANNELS, AAC_MAX_CHANNELS)
    }
    plain.createAdapter(configuration)
}

/** From this API level (Android 12L), Media3 asks itself. */
private const val MEDIA3_ASKS = 32

/** MediaFormat.KEY_MAX_OUTPUT_CHANNEL_COUNT (API 32). A decoder from before ignores a key that it does not know. */
private const val MAX_OUTPUT_CHANNELS = "max-output-channel-count"

/**
 * MediaFormat.KEY_AAC_MAX_OUTPUT_CHANNEL_COUNT, by its value: Android deprecates the name in favour of the key above,
 * which these older versions do not have.
 */
internal const val AAC_MAX_OUTPUT_CHANNELS = "aac-max-output-channel_count"

/** As Media3 sets it: as many as the track has. */
private const val ANY_CHANNELS = 99

/** 7.1, the most that AAC carries. */
private const val AAC_MAX_CHANNELS = 8
