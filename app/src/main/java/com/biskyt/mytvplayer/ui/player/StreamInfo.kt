@file:OptIn(UnstableApi::class)

package com.biskyt.mytvplayer.ui.player

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.appString
import com.biskyt.mytvplayer.player.PlaybackEngine
import com.biskyt.mytvplayer.player.displayAddress
import com.biskyt.mytvplayer.ui.common.displayLanguage
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * The lines of the Stream info panel: the stream's address, its video and audio, the audio output and the buffer. It
 * helps to find why a stream plays badly or not at all. The codec names and units are technical, so they are not
 * translated.
 */
fun streamInfo(engine: PlaybackEngine, audioOutput: String): List<String> {
    val player = engine.player
    val unknown = appString(R.string.player_info_unknown)
    return listOf(
        appString(R.string.player_info_address, engine.currentUrl?.let(::displayAddress) ?: unknown),
        appString(R.string.player_info_video, player.videoFormat?.let(::videoDetails) ?: unknown),
        appString(R.string.player_info_audio, player.audioFormat?.let(::audioDetails) ?: unknown),
        appString(R.string.player_audio_output, audioOutput.ifEmpty { unknown }),
        appString(R.string.player_info_buffer, player.totalBufferedDuration / MS_PER_SECOND)
    )
}

/** For example "1920×1080 · avc1.4D4029 · 25 fps · 4.3 Mb/s". */
private fun videoDetails(f: Format) = listOfNotNull(
    if (f.width > 0 && f.height > 0) "${f.width}×${f.height}" else null,
    codec(f),
    f.frameRate.takeIf { it > 0 }?.let { "${number(it.toDouble())} fps" },
    bitrate(f)
).joinToString(" · ")

/** For example "mp4a.40.2 · 6ch · 48 kHz · Dutch", or "Dolby Atmos · 6ch · 48 kHz". */
private fun audioDetails(f: Format) = listOfNotNull(
    PlaybackEngine.encodedName(f.sampleMimeType) ?: codec(f),
    f.channelCount.takeIf { it > 0 }?.let { appString(R.string.player_track_channels, it) },
    f.sampleRate.takeIf { it > 0 }?.let { "${number(it / HZ_PER_KHZ)} kHz" },
    f.language?.let(::displayLanguage),
    bitrate(f)
).joinToString(" · ")

/** The format's codec: its full codec string (avc1.4D4029) when known, or else its MIME type's subtype (avc). */
internal fun codec(f: Format) = f.codecs ?: f.sampleMimeType?.substringAfter('/')

private fun bitrate(f: Format): String? {
    val bps = listOf(f.bitrate, f.averageBitrate, f.peakBitrate).firstOrNull { it > 0 } ?: return null
    return if (bps >= BPS_PER_MBPS) "${number(bps / BPS_PER_MBPS)} Mb/s" else "${bps / BPS_PER_KBPS} kb/s"
}

/** 25 as "25", 29.97 as "29.97", 3.0004 as "3". */
private fun number(value: Double) = DecimalFormat("0.##", DecimalFormatSymbols(Locale.ROOT)).format(value)

private const val MS_PER_SECOND = 1_000L
private const val HZ_PER_KHZ = 1_000.0
private const val BPS_PER_MBPS = 1_000_000.0
private const val BPS_PER_KBPS = 1_000
