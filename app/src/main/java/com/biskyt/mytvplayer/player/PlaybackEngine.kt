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
import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.USER_AGENT
import com.biskyt.mytvplayer.appString
import com.biskyt.mytvplayer.core.net.ProxyClients
import com.biskyt.mytvplayer.core.net.ProxySettings
import com.biskyt.mytvplayer.core.player.StallWatch
import com.biskyt.mytvplayer.core.player.SubtitlePolicy
import com.biskyt.mytvplayer.core.player.TextTrack
import com.biskyt.mytvplayer.data.BufferSize
import com.biskyt.mytvplayer.data.Settings
import java.io.IOException
import okhttp3.OkHttpClient

/**
 * Builds and drives one ExoPlayer for a playback session.
 *
 * Audio passthrough (default on): the stock audio sink sends Dolby/DTS bitstreams to the TV or soundbar when the
 * HDMI device says it can decode them. When the user turns it off, the sink is limited to PCM so everything is
 * decoded on the box (hardware decoders first, then the FFmpeg extension).
 */
@OptIn(UnstableApi::class)
class PlaybackEngine(
    private val context: Context,
    private val settings: Settings,
    /** The streams' HTTP client for each playlist proxy (none: the plain one). */
    private val clients: ProxyClients = ProxyClients(OkHttpClient()),
) {

    /**
     * The address of the last load, read from the start, whose reply had no data. A server can answer "OK" with an empty
     * file, for example when it no longer has the film. The player then reports a format it cannot read, which is
     * misleading. The player's load events run on the main thread, as do the questions about them.
     */
    private var emptyReply: String? = null

    /**
     * Notes each load from the start of an address that ended with no data, from the player's own load events, and what
     * goes to the audio output ([output]).
     */
    private val events = object : AnalyticsListener {
        override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, config: AudioSink.AudioTrackConfig) =
            audioTrackStarted(config)

        override fun onAudioDisabled(eventTime: AnalyticsListener.EventTime, counters: DecoderCounters) = audioStopped()

        override fun onAudioInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, reuse: DecoderReuseEvaluation?) =
            audioInputChanged(format)

        override fun onLoadCompleted(eventTime: AnalyticsListener.EventTime, info: LoadEventInfo, data: MediaLoadData) =
            note(info, empty = info.bytesLoaded == 0L)

        override fun onBandwidthEstimate(eventTime: AnalyticsListener.EventTime, loadMs: Int, bytes: Long, bitrateEstimate: Long) {
            downloadBps = bitrateEstimate
        }

        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            name: String,
            initializedMs: Long,
            durationMs: Long
        ) {
            videoDecoder = name
        }

        override fun onLoadError(
            eventTime: AnalyticsListener.EventTime,
            info: LoadEventInfo,
            data: MediaLoadData,
            error: IOException,
            wasCanceled: Boolean
        ) = note(info, empty = info.bytesLoaded == 0L && error is ParserException) // not a timeout: a reply that was read

        private fun note(info: LoadEventInfo, empty: Boolean) {
            if (info.dataSpec.position != 0L) return
            val url = info.dataSpec.uri.toString()
            if (empty) {
                emptyReply = url
            } else if (emptyReply == url) {
                emptyReply = null
            }
        }
    }

    /** The player's estimate of the download speed, in bits a second (0 until its first estimate): for Stream info. */
    @Volatile var downloadBps = 0L
        private set

    /** The name of the video decoder in use ("c2.amlogic.avc.decoder"), once one has started: for Stream info. */
    @Volatile var videoDecoder: String? = null
        private set

    /** Whether the last reply for [url], read from the start, had no data at all. */
    fun sentNothing(url: String?) = url != null && url == emptyReply

    /** The client of the stream playing now: through its playlist's proxy, if it has one (see [play]). */
    @Volatile private var client: OkHttpClient = clients.forProxy(null)

    // OkHttp follows redirects between http and https by itself.
    private val http = OkHttpDataSource.Factory { request -> client.newCall(request) }
        .setUserAgent(USER_AGENT)

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
        .setAudioAttributes(MOVIE, true)
        .setHandleAudioBecomingNoisy(true)
        .build()

    /**
     * What the TV's audio output takes unchanged, for the label before an output starts: read once for this player, not at
     * each change of channel (it asks Android's audio service many times).
     */
    private val capabilities by lazy { AudioCapabilities.getCapabilities(context, MOVIE, null) }

    /** What goes to the audio output now: the format sent unchanged, or the decoded sound and its channels. Null: nothing yet. */
    private var output: AudioSink.AudioTrackConfig? = null

    /** The sound format (type and channels) that the last output was started for. */
    private var outputFor: Pair<String?, Int>? = null

    /**
     * A new output replaces the last one. Its release is not used: a seek releases the old output on another thread,
     * and that can be told after the new one has started.
     */
    internal fun audioTrackStarted(config: AudioSink.AudioTrackConfig, forFormat: Format? = player.audioFormat) {
        output = config
        outputFor = forFormat?.let(::kind)
    }

    /** No sound plays any more (the stream stopped, or the audio is turned off). */
    internal fun audioStopped() {
        output = null
    }

    /** Another sound format (another track, or the same codec with other channels): the last output says nothing about it. */
    internal fun audioInputChanged(format: Format) {
        if (kind(format) != outputFor) output = null
    }

    private fun kind(format: Format) = format.sampleMimeType to format.channelCount

    /**
     * Called with an address that can't be played at all (a stream type the player has no support for), instead of
     * the player throwing and closing the app.
     */
    var onUnsupported: (url: String) -> Unit = {}

    /**
     * Called (with the player stopped) when a stream is stuck (see [StallWatch]), so the caller handles it like any other
     * failure (it tries again).
     */
    var onStalled: (PlaybackException) -> Unit = {}

    /** True once the default subtitle choice has been applied to the current stream (the user may change it after). */
    private var subtitlesApplied = false

    // URLs whose format was worked out from the address and turned out to be HLS (some M3U links don't say).
    private val hlsUrls = HashSet<String>()
    private var current: String? = null

    // A server that sends nothing at all fails on the read timeout. One that trickles (a byte now and then, never
    // enough to play), or sends only some of a stream's tracks, raises no error, nor does a picture that freezes while
    // the sound or the clock goes on. Media3 has nothing built in for these (its own check only covers a full buffer
    // that can't start). This watches for them from the start of a stream to its end (the owner, 2026-11-23: a stream
    // could stay on "Loading" for ever), and hands them on as failures, which the player retries.
    private val handler = Handler(player.applicationLooper)
    private var started = false
    private val stalls = StallWatch(STALL_CHECK_MS)

    /**
     * Whether the streams are live TV (catch-up too): once one has played, waiting [REBUFFER_MS] with no data is a stall.
     * A film or an episode waits [ON_DEMAND_REBUFFER_MS], also after a seek: a slow server (a file made on the fly, a
     * disk that sleeps) can take a while to send more.
     */
    var live = true
    private val checkStall = object : Runnable {
        override fun run() {
            if (player.playbackState == Player.STATE_READY) started = true
            when (stalls.check(sample())) {
                null -> handler.postDelayed(this, STALL_CHECK_MS)
                StallWatch.Stall.BUFFERING -> stalled(PlaybackException("Buffering got nowhere", null, ERROR_CODE_STALLED))
                StallWatch.Stall.FROZEN -> stalled(PlaybackException("The picture froze", null, ERROR_CODE_FROZEN))
            }
        }
    }

    private fun stalled(e: PlaybackException) {
        player.stop()
        handler.postDelayed(checkStall, STALL_CHECK_MS)
        onStalled(e)
    }

    /** How the player is now, for [stalls]: the video decoder's output counts as pictures, shown or not. */
    private fun sample(): StallWatch.Sample {
        val frames = player.videoDecoderCounters?.takeIf { player.videoFormat != null }?.let {
            it.ensureUpdated()
            (it.renderedOutputBufferCount + it.skippedOutputBufferCount + it.droppedBufferCount).toLong()
        }
        return StallWatch.Sample(
            buffering = player.playbackState == Player.STATE_BUFFERING && player.playWhenReady,
            playing = player.isPlaying,
            started = started,
            buffered = player.bufferedPosition,
            position = player.currentPosition,
            frames = frames,
        )
    }

    /** Watches a new stream from its start. */
    private fun watchForStall() {
        handler.removeCallbacks(checkStall)
        stalls.reset()
        handler.postDelayed(checkStall, STALL_CHECK_MS)
    }

    init {
        player.addAnalyticsListener(events)
        // Started as soon as it is ready, not at the next check: a stream that is ready and waits again between two
        // checks then has the shorter wait of one that played.
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) started = true
            }
        })
    }

    /**
     * A seek the viewer asked for. The watch starts again: the picture can hold while the sound catches up, as at the
     * start, and it is not a freeze. A reconnect's own seek to the live point does not come here: its waits stay live
     * TV's (MyPlexPlayer's review, 2026-11-23).
     */
    fun seekTo(ms: Long) {
        player.seekTo(ms)
        stalls.reset()
    }

    /**
     * Opens the stream again where it was (a reconnect, Try again, back from Home). The watch starts again too: the new
     * decoder holds its first picture while the sound catches up, which is not a freeze. It keeps [started], so the
     * waits stay those of a stream that played.
     */
    fun reopen() {
        stalls.reset()
        player.prepare()
    }

    /** The current stream's address. */
    val currentUrl: String? get() = current
    private var currentInferred = false

    /**
     * [hls]: null works the format out from the stream (retrying once as HLS when that fails, see [retryAsHls]).
     * [userAgent] / [referrer]: some M3U streams only play when these headers are sent. [firstPictureMs]: how long the
     * stream is given to start, see [StallWatch].
     */
    @Suppress("LongParameterList") // what one stream needs, as MyPlexPlayer's engine has it
    fun play(
        url: String,
        hls: Boolean?,
        proxy: ProxySettings?,
        userAgent: String? = null,
        referrer: String? = null,
        firstPictureMs: Long = FIRST_PICTURE_MS,
    ) {
        stalls.startMs = firstPictureMs
        stalls.rebufferMs = if (live) REBUFFER_MS else ON_DEMAND_REBUFFER_MS
        // A film can have long still scenes with no new pictures (a variable frame rate): only its time counts.
        stalls.watchPicture = live
        client = clients.forProxy(proxy)
        subtitlesApplied = false
        // A track chosen on the last stream mustn't carry over (or quietly match a different one on this).
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()
        current = url
        output = null
        outputFor = null
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
     * How the sound is played, for the options menu, e.g. "Passthrough · Dolby Atmos" or "AAC decoded · Stereo". Once the
     * audio output has started ([out]), it says what goes to it: the channels after decoding, which can be fewer than
     * the track's (a TV's decoder can mix 5.1 down to stereo). Before that, it is read from the selected audio track in
     * [tracks] (when tracks change, the player's own audio format isn't known yet).
     */
    fun audioOutputLabel(
        tracks: Tracks = player.currentTracks,
        playing: Format? = player.audioFormat,
        out: AudioSink.AudioTrackConfig? = output
    ): String {
        // The format being played when known; else the chosen track's.
        val f = playing ?: selectedAudioFormat(tracks) ?: return appString(R.string.player_audio_none)
        val encoded = encodedName(f.sampleMimeType)
        val name = encoded ?: decodedName(f.sampleMimeType)
        // Decoded AAC surround sound encoded in the app (DolbyDigitalSink).
        if (out?.encoding == C.ENCODING_AC3 && f.sampleMimeType == MimeTypes.AUDIO_AAC) {
            return appString(R.string.player_audio_as_dolby, "AAC")
        }
        // An output sent unchanged belongs to a format that can be sent so: Dolby or DTS. Also see audioInputChanged.
        val sent = out?.takeIf { Util.isEncodingLinearPcm(it.encoding) || encoded != null }
        unchangedName(f, sent)?.let { return appString(R.string.player_audio_passthrough, it) }
        val channels = sent?.let { Integer.bitCount(it.channelConfig) } ?: f.channelCount.takeIf { it != Format.NO_VALUE } ?: 2
        val layout = if (channels > 2) appString(R.string.player_track_channels, channels) else appString(R.string.player_audio_stereo)
        return if (name != null) appString(R.string.player_audio_decoded, name, layout) else appString(R.string.player_audio_layout, layout)
    }

    /** The name of the format sent unchanged for [f] ([sent] when its output has started), or null when it is decoded. */
    private fun unchangedName(f: Format, sent: AudioSink.AudioTrackConfig?): String? {
        val encoded = encodedName(f.sampleMimeType)
        return when {
            // Named from what is sent: Dolby Atmos can be sent as plain Dolby Digital Plus, DTS-HD as DTS.
            sent != null -> if (Util.isEncodingLinearPcm(sent.encoding)) null else encodingName(sent.encoding) ?: encoded
            !settings.audioPassthrough -> null
            encoded != null -> encoded.takeIf { capabilities.isPassthroughPlaybackSupported(f, MOVIE) }
            else -> null
        }
    }

    private fun selectedAudioFormat(tracks: Tracks): Format? = tracks.groups
        .firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
        ?.let { g -> (0 until g.length).firstOrNull(g::isTrackSelected)?.let(g::getTrackFormat) }

    fun release() {
        handler.removeCallbacks(checkStall)
        player.release()
    }

    private fun renderersFactory() = object : DefaultRenderersFactory(context) {
        override fun getCodecAdapterFactory(): MediaCodecAdapter.Factory =
            allAudioChannels(dolbyVisionWithoutHdr10Plus(super.getCodecAdapterFactory()) { dolbyVisionScreen(context) })

        override fun buildAudioRenderers(
            context: Context,
            extensionRendererMode: Int,
            mediaCodecSelector: MediaCodecSelector,
            enableDecoderFallback: Boolean,
            audioSink: AudioSink,
            eventHandler: Handler,
            eventListener: AudioRendererEventListener,
            out: ArrayList<Renderer>
        ) {
            // Built first: each audio renderer takes the shared sink's listener when it is built, and the last one keeps
            // it, as with Media3's own renderers.
            val aac = (audioSink as? DolbyDigitalSink)?.let {
                AacSourceRenderer(context, codecAdapterFactory, mediaCodecSelector, enableDecoderFallback, eventHandler, eventListener, it)
            }
            super.buildAudioRenderers(
                context,
                extensionRendererMode,
                mediaCodecSelector,
                enableDecoderFallback,
                audioSink,
                eventHandler,
                eventListener,
                out
            )
            aac?.let { AacSourceRenderer.replaceIn(out, it) }
        }

        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink? =
            if (settings.audioPassthrough) {
                super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)
                    ?.let { if (settings.surroundAsDolby && Ac3Encoder.available) DolbyDigitalSink(it) else it }
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

    companion object {
        private const val TAG = "PlaybackEngine"
        private const val MB = 1024 * 1024

        /** How often the stream is checked for a stall (see StallWatch). */
        private const val STALL_CHECK_MS = 2_000L

        /** How long a stream is given to start, unless [play] is told otherwise. */
        const val FIRST_PICTURE_MS = 20_000L

        /** How long live TV that has played may wait with no new data before it is opened again. */
        const val REBUFFER_MS = 12_000L

        /** How long a film or an episode that has played may wait with no new data (a slow server's read time). */
        const val ON_DEMAND_REBUFFER_MS = 60_000L

        /** How the player's sound is played. */
        private val MOVIE: AudioAttributes = AudioAttributes.Builder().setUsage(
            C.USAGE_MEDIA
        ).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build()

        /** The name of a format that is always decoded before it is played, such as "AAC"; null for others (and PCM). */
        fun decodedName(mime: String?): String? = when (mime) {
            MimeTypes.AUDIO_AAC -> "AAC"
            MimeTypes.AUDIO_MPEG -> "MP3"
            MimeTypes.AUDIO_MPEG_L2 -> "MP2"
            MimeTypes.AUDIO_OPUS -> "Opus"
            MimeTypes.AUDIO_VORBIS -> "Vorbis"
            MimeTypes.AUDIO_FLAC -> "FLAC"
            MimeTypes.AUDIO_ALAC -> "ALAC"
            else -> null
        }

        /** The name of an encoded (passthrough) audio format, such as "Dolby Atmos"; null for other formats. */
        fun encodedName(mime: String?): String? = mime?.let { encodingName(MimeTypes.getEncoding(it, null)) }

        /** The name of a sound encoding sent unchanged, such as "Dolby Atmos"; null for others (PCM, AAC). */
        fun encodingName(encoding: Int): String? = when (encoding) {
            C.ENCODING_AC3 -> "Dolby Digital"
            C.ENCODING_E_AC3 -> "Dolby Digital Plus"
            C.ENCODING_E_AC3_JOC -> "Dolby Atmos"
            C.ENCODING_AC4 -> "Dolby AC-4"
            C.ENCODING_DOLBY_TRUEHD -> "Dolby TrueHD"
            C.ENCODING_DTS -> "DTS"
            C.ENCODING_DTS_HD -> "DTS-HD"
            C.ENCODING_DTS_UHD_P2 -> "DTS:X"
            else -> null
        }
    }
}
