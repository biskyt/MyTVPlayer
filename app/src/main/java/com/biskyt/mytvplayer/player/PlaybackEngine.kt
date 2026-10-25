package com.biskyt.mytvplayer.player

import android.content.Context
import android.os.Handler
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.USER_AGENT
import com.biskyt.mytvplayer.appString
import com.biskyt.mytvplayer.core.player.SubtitlePolicy
import com.biskyt.mytvplayer.core.player.TextTrack
import com.biskyt.mytvplayer.data.BufferSize
import com.biskyt.mytvplayer.data.Settings

/**
 * Builds and drives one ExoPlayer for a playback session.
 *
 * Audio passthrough (default on): the stock audio sink sends Dolby/DTS bitstreams to the TV or soundbar when the
 * HDMI device says it can decode them. When the user turns it off, the sink is limited to PCM so everything is
 * decoded on the box (hardware decoders first, then the FFmpeg extension).
 */
@OptIn(UnstableApi::class)
class PlaybackEngine(private val context: Context, private val settings: Settings) {

    private val http = DefaultHttpDataSource.Factory()
        .setUserAgent(USER_AGENT)
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(10_000)
        .setReadTimeoutMs(15_000)

    val player: ExoPlayer = ExoPlayer.Builder(context, renderersFactory())
        .setLoadControl(loadControl(settings.bufferSize))
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(
                http,
                DefaultExtractorsFactory()
                    // Start live MPEG-TS faster and cope with streams that don't flag keyframes.
                    .setTsExtractorFlags(
                        DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
                    )
            )
        )
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        .setHandleAudioBecomingNoisy(true)
        .build()

    /**
     * Called with an address that can't be played at all (a stream type the player has no support for), instead of
     * the player throwing and closing the app.
     */
    var onUnsupported: (url: String) -> Unit = {}

    /**
     * Called (with the player stopped) when a stream has buffered for [STALL_MS] without getting any further, so the
     * caller handles it like any other failure (retries, then an error).
     */
    var onStalled: (PlaybackException) -> Unit = {}

    /** True once the default subtitle choice has been applied to the current stream (the user may change it after). */
    private var subtitlesApplied = false

    // URLs whose format was worked out from the address and turned out to be HLS (some M3U links don't say).
    private val hlsUrls = HashSet<String>()
    private var current: String? = null

    // A server that sends nothing at all fails on the read timeout. One that trickles (a byte now and then, never
    // enough to play), or sends only some of a stream's tracks, raises no error: this notices a stream that can't get
    // started. Media3 1.8 has nothing built in for it (its own check only covers a full buffer that can't start).
    // Once a stream has played, rebuffering is left to the player's own retries.
    private val handler = Handler(player.applicationLooper)
    private var started = false
    private var lastBuffered = 0L
    private var stalledMs = 0L
    private val checkStall = object : Runnable {
        override fun run() {
            val buffered = player.bufferedPosition
            stalledMs = if (buffered != lastBuffered) 0 else stalledMs + STALL_CHECK_MS
            lastBuffered = buffered
            if (stalledMs < STALL_MS) {
                handler.postDelayed(this, STALL_CHECK_MS)
                return
            }
            player.stop()
            onStalled(PlaybackException("Buffering got nowhere for ${STALL_MS / 1000} s", null, ERROR_CODE_STALLED))
        }
    }

    /** Watches while the viewer is waiting on buffering, from the start (again) when a new stream starts. */
    private fun watchForStall() {
        handler.removeCallbacks(checkStall)
        if (player.playbackState == Player.STATE_READY) started = true
        if (started || player.playbackState != Player.STATE_BUFFERING || !player.playWhenReady) return
        lastBuffered = player.bufferedPosition
        stalledMs = 0
        handler.postDelayed(checkStall, STALL_CHECK_MS)
    }

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) = watchForStall()
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = watchForStall()
        })
    }

    /** The current stream's address. */
    val currentUrl: String? get() = current
    private var currentInferred = false

    /**
     * [hls]: null works the format out from the stream (retrying once as HLS when that fails, see [retryAsHls]).
     * [userAgent] / [referrer]: some M3U streams only play when these headers are sent.
     */
    fun play(url: String, hls: Boolean?, userAgent: String? = null, referrer: String? = null) {
        subtitlesApplied = false
        // A track chosen on the last stream mustn't carry over (or quietly match a different one on this).
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()
        current = url
        started = false
        currentInferred = hls == null && url !in hlsUrls
        http.setUserAgent(userAgent ?: USER_AGENT)
        http.setDefaultRequestProperties(if (referrer != null) mapOf("Referer" to referrer) else emptyMap())
        val asHls = hls ?: (url in hlsUrls)
        val item = MediaItem.Builder().setUri(url).apply { if (asHls) setMimeType(MimeTypes.APPLICATION_M3U8) }.build()
        if (!start(item)) return
        player.playWhenReady = true
        watchForStall()
    }

    /** Sets and prepares [item]; false (and [onUnsupported]) when the player can't handle its type. */
    private fun start(item: MediaItem): Boolean = try {
        player.setMediaItem(item)
        player.prepare()
        true
    } catch (e: IllegalStateException) {
        // E.g. a SmoothStreaming (.ism) address: the media source factory has no module for it.
        Log.w(TAG, "Can't play ${item.localConfiguration?.uri}", e)
        // Nothing of the previous stream may be left to start again on a later prepare().
        player.stop()
        player.clearMediaItems()
        current = null
        onUnsupported(item.localConfiguration?.uri?.toString().orEmpty())
        false
    }

    /** A stream whose format was guessed and couldn't be read is tried once more as HLS. True if it was. */
    fun retryAsHls(e: PlaybackException): Boolean {
        val url = current ?: return false
        if (!currentInferred || e.errorCode != PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED) return false
        currentInferred = false
        hlsUrls += url
        return start(MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_M3U8).build())
    }

    /** Applies the user's default subtitle setting the first time tracks are known for a stream. */
    fun applyDefaultSubtitles(tracks: Tracks) {
        if (subtitlesApplied) return
        if (tracks.groups.isEmpty()) return
        subtitlesApplied = true
        val options = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.flatMap { g -> (0 until g.length).map { g to it } }
        val candidates = options.mapIndexed { i, (g, ti) ->
            val f = g.getTrackFormat(ti)
            TextTrack(i.toString(), f.language, (f.selectionFlags and C.SELECTION_FLAG_FORCED) != 0)
        }
        val choice = SubtitlePolicy.choose(candidates, settings.subtitleMode, settings.effectiveSubtitleLanguage)
        val (group, index) = choice?.let { options[it.id.toInt()] } ?: (null to 0)
        selectTrack(C.TRACK_TYPE_TEXT, group, index)
    }

    /** Whether track [index] of [group] is the one playing, in the player's current tracks. */
    fun isPlaying(group: Tracks.Group, index: Int): Boolean =
        player.currentTracks.groups.any { it.mediaTrackGroup == group.mediaTrackGroup && it.isTrackSelected(index) }

    /** Selects a track of [type] (audio or text); null turns that type off. */
    fun selectTrack(type: Int, group: Tracks.Group?, index: Int) {
        val params = player.trackSelectionParameters.buildUpon()
        if (group == null) {
            params.setTrackTypeDisabled(type, true)
        } else {
            params.setTrackTypeDisabled(type, false).setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
        }
        player.trackSelectionParameters = params.build()
    }

    /**
     * How the sound is played, for the options menu, e.g. "Passthrough · Dolby Atmos". Read from the selected audio track in [tracks]: when tracks change,
     * the player's own audio format isn't known yet (the audio output starts a moment later).
     */
    fun audioOutputLabel(tracks: Tracks = player.currentTracks, playing: Format? = player.audioFormat): String {
        // The format being played when known (it has the real channel count); else the chosen track's.
        val f = playing ?: selectedAudioFormat(tracks) ?: return appString(R.string.player_audio_none)
        val name = encodedName(f.sampleMimeType)
        if (name != null && settings.audioPassthrough) {
            val caps = AudioCapabilities.getCapabilities(context, AudioAttributes.DEFAULT, null)
            if (caps.isPassthroughPlaybackSupported(f, AudioAttributes.DEFAULT)) return appString(R.string.player_audio_passthrough, name)
        }
        val channels = f.channelCount.takeIf { it != Format.NO_VALUE } ?: 2
        val layout = if (channels > 2) appString(R.string.player_track_channels, channels) else appString(R.string.player_audio_stereo)
        return if (name != null) appString(R.string.player_audio_decoded, name, layout) else appString(R.string.player_audio_layout, layout)
    }

    private fun selectedAudioFormat(tracks: Tracks): Format? = tracks.groups
        .firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
        ?.let { g -> (0 until g.length).firstOrNull(g::isTrackSelected)?.let(g::getTrackFormat) }

    fun release() {
        handler.removeCallbacks(checkStall)
        player.release()
    }

    private fun renderersFactory() = object : DefaultRenderersFactory(context) {
        override fun getCodecAdapterFactory(): MediaCodecAdapter.Factory = dolbyVisionWithoutHdr10Plus(super.getCodecAdapterFactory()) {
            dolbyVisionScreen(context)
        }

        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink? =
            if (settings.audioPassthrough) {
                super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)
            } else {
                @Suppress("DEPRECATION")
                DefaultAudioSink.Builder(context).setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES).build()
            }
    }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON).setEnableDecoderFallback(true)

    private fun loadControl(size: BufferSize): DefaultLoadControl {
        // Byte caps matter on low-RAM boxes: high-bitrate streams would otherwise fill memory before the time limit.
        val (minMs, maxMs, bytes) = when (size) {
            BufferSize.SMALL -> Triple(8_000, 20_000, 8 * MB)
            BufferSize.NORMAL -> Triple(15_000, 30_000, 16 * MB)
            BufferSize.LARGE -> Triple(30_000, 60_000, 32 * MB)
        }
        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(minMs, maxMs, 1_500, 3_000)
            .setTargetBufferBytes(bytes)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
    }

    private companion object {
        const val TAG = "PlaybackEngine"
        const val MB = 1024 * 1024

        /** Buffering this long without getting any further counts as a failure. */
        const val STALL_MS = 20_000L
        const val STALL_CHECK_MS = 5_000L

        fun encodedName(mime: String?): String? = when (mime) {
            MimeTypes.AUDIO_AC3 -> "Dolby Digital"
            MimeTypes.AUDIO_E_AC3 -> "Dolby Digital Plus"
            MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Atmos"
            MimeTypes.AUDIO_AC4 -> "Dolby AC-4"
            MimeTypes.AUDIO_TRUEHD -> "Dolby TrueHD"
            MimeTypes.AUDIO_DTS -> "DTS"
            MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
            MimeTypes.AUDIO_DTS_X -> "DTS:X"
            else -> null
        }
    }
}
