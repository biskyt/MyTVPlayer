package com.biskyt.mytvplayer.player

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.data.Settings
import com.biskyt.mytvplayer.playerClient
import com.biskyt.mytvplayer.testutil.PlainApp
import com.biskyt.mytvplayer.ui.player.GIVE_UP_MS
import com.biskyt.mytvplayer.ui.player.RetryBudget
import com.biskyt.mytvplayer.ui.player.keepsTrying
import com.biskyt.mytvplayer.ui.player.retryDelayMs
import java.time.Duration
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = PlainApp::class)
class PlaybackEngineTest {
    private val engine = PlaybackEngine(ApplicationProvider.getApplicationContext<Context>(), Settings())
    private var unsupported = 0
    private var unsupportedUrl: String? = null

    init {
        engine.onUnsupported = {
            unsupported++
            unsupportedUrl = it
        }
    }

    @After
    fun tearDown() = engine.release()

    @Test
    fun `DASH streams play, and a stream type with no support reports it instead of crashing`() {
        // BBC Four in the free UK list is DASH: this used to throw and close the app.
        engine.play("https://example.invalid/bbc/stream/BBC_FOUR/hevc_iptv_mse_v0.mpd", hls = null, proxy = null)
        assertEquals(0, unsupported)
        // SmoothStreaming isn't included.
        engine.play("https://example.invalid/channel.ism/Manifest", hls = null, proxy = null)
        assertEquals(1, unsupported)
        // The error shows its address.
        assertEquals("https://example.invalid/channel.ism/Manifest", unsupportedUrl)
    }

    @Test
    fun `the stall check runs only while the player wants to play, and starts again when it plays`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)) // waits for data
            val looper = shadowOf(Looper.getMainLooper())
            engine.play(server.url("/live.ts").toString(), hls = false, proxy = null)
            looper.idle()
            assertTrue(engine.checking)
            engine.player.pause()
            looper.idleFor(Duration.ofSeconds(3)) // the next check notes the pause, and posts no more
            assertFalse(engine.checking)
            engine.player.play()
            looper.idle()
            assertTrue(engine.checking)
        }
    }

    @Test
    fun `a failing stream gets a few retries in a row, then fresh ones once it has played`() {
        val budget = RetryBudget(3)
        repeat(3) { assertTrue(budget.take()) }
        assertFalse(budget.take()) // starts and fails again and again: the user is told
        budget.reset() // played steadily for a while
        assertTrue(budget.take())
        assertEquals(1, budget.used)
    }

    @Test
    fun `tries in a row wait longer each time, then every 15 seconds`() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 15_000L), (0..5).map(::retryDelayMs))
    }

    @Test
    fun `a stream's read waits as long as the longest stall wait, so that a slow server is not cut off first`() {
        val client = playerClient(okhttp3.OkHttpClient())
        assertTrue(client.readTimeoutMillis >= PlaybackEngine.ON_DEMAND_REBUFFER_MS)
    }

    @Test
    fun `live TV that played goes on trying a failure that passes, for 5 minutes with no picture`() {
        assertTrue(keepsTrying(live = true, played = true, passing = true, failingForMs = 0))
        assertTrue(keepsTrying(live = true, played = true, passing = true, failingForMs = GIVE_UP_MS - 1))
        // Then the error shows, with Try again.
        assertFalse(keepsTrying(live = true, played = true, passing = true, failingForMs = GIVE_UP_MS))
        // A refusal, a channel that never played, or a film: the usual few tries.
        assertFalse(keepsTrying(live = true, played = true, passing = false, failingForMs = 0))
        assertFalse(keepsTrying(live = true, played = false, passing = true, failingForMs = 0))
        assertFalse(keepsTrying(live = false, played = true, passing = true, failingForMs = 0))
    }

    @Test
    fun `the sound label comes from the selected audio track, before the audio output has started`() {
        val format = androidx.media3.common.Format.Builder()
            .setSampleMimeType(androidx.media3.common.MimeTypes.AUDIO_AAC).setChannelCount(2).build()
        val group = androidx.media3.common.Tracks.Group(
            androidx.media3.common.TrackGroup(format),
            false,
            intArrayOf(androidx.media3.common.C.FORMAT_HANDLED),
            booleanArrayOf(true)
        )
        // Nothing has played, so the player itself has no audio format yet: this used to say "No audio".
        assertEquals("AAC decoded · Stereo", engine.audioOutputLabel(androidx.media3.common.Tracks(listOf(group))))
        assertEquals("No audio", engine.audioOutputLabel(androidx.media3.common.Tracks.EMPTY))
    }

    /** What goes to the audio output: [encoding] and [channelConfig] as Android's AudioTrack has them. */
    private fun output(encoding: Int, channelConfig: Int) =
        androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig(encoding, 48_000, channelConfig, false, false, 0)

    private fun track(mime: String, channels: Int) =
        androidx.media3.common.Format.Builder().setSampleMimeType(mime).setChannelCount(channels).build()

    @Test
    fun `once the sound plays, the label says what goes to the audio output (the owner, 2026-11-15)`() {
        val aac51 = track(androidx.media3.common.MimeTypes.AUDIO_AAC, 6)
        // A TV's decoder can mix a 5.1 track down to stereo: the label says so, not "6ch".
        assertEquals(
            "AAC decoded · Stereo",
            engine.audioOutputLabel(
                playing = aac51,
                out = output(android.media.AudioFormat.ENCODING_PCM_16BIT, android.media.AudioFormat.CHANNEL_OUT_STEREO)
            )
        )
        assertEquals(
            "AAC decoded · 6ch",
            engine.audioOutputLabel(
                playing = aac51,
                out = output(android.media.AudioFormat.ENCODING_PCM_FLOAT, android.media.AudioFormat.CHANNEL_OUT_5POINT1)
            )
        )
        val ac3 = track(androidx.media3.common.MimeTypes.AUDIO_AC3, 6)
        assertEquals(
            "Passthrough · Dolby Digital",
            engine.audioOutputLabel(
                playing = ac3,
                out = output(android.media.AudioFormat.ENCODING_AC3, android.media.AudioFormat.CHANNEL_OUT_5POINT1)
            )
        )
    }

    @Test
    fun `an AAC output that is not PCM is not called passthrough`() {
        val aac51 = track(androidx.media3.common.MimeTypes.AUDIO_AAC, 6)
        val sent = output(android.media.AudioFormat.ENCODING_AAC_LC, android.media.AudioFormat.CHANNEL_OUT_5POINT1)
        assertEquals("AAC decoded · 6ch", engine.audioOutputLabel(playing = aac51, out = sent))
    }

    @Test
    fun `AAC encoded to Dolby Digital in the app says so`() {
        val aac51 = track(androidx.media3.common.MimeTypes.AUDIO_AAC, 6)
        val sent = output(androidx.media3.common.C.ENCODING_AC3, android.media.AudioFormat.CHANNEL_OUT_5POINT1)
        assertEquals("AAC sent as Dolby Digital 5.1", engine.audioOutputLabel(playing = aac51, out = sent))
        val ac3 = track(androidx.media3.common.MimeTypes.AUDIO_AC3, 6)
        assertEquals("Passthrough · Dolby Digital", engine.audioOutputLabel(playing = ac3, out = sent))
    }

    @Test
    fun `Dolby Atmos sent as plain Dolby Digital Plus is named as it is sent`() {
        val atmos = track(androidx.media3.common.MimeTypes.AUDIO_E_AC3_JOC, 6)
        val sent = output(androidx.media3.common.C.ENCODING_E_AC3, android.media.AudioFormat.CHANNEL_OUT_5POINT1)
        assertEquals("Passthrough · Dolby Digital Plus", engine.audioOutputLabel(playing = atmos, out = sent))
    }

    @Test
    fun `after a seek the new output is kept, another track's format forgets it, and so does stopping`() {
        val first = output(android.media.AudioFormat.ENCODING_PCM_16BIT, android.media.AudioFormat.CHANNEL_OUT_STEREO)
        val aac51 = track(androidx.media3.common.MimeTypes.AUDIO_AAC, 6)
        engine.audioTrackStarted(first, aac51)
        // A seek: a new output starts (the old one's release, told late, is not used).
        engine.audioTrackStarted(first, aac51)
        assertEquals("AAC decoded · Stereo", engine.audioOutputLabel(playing = aac51))
        // The same format again (a new bitrate): still known.
        engine.audioInputChanged(aac51)
        assertEquals("AAC decoded · Stereo", engine.audioOutputLabel(playing = aac51))
        // Another track of the same codec, in stereo: its own output is not known yet.
        engine.audioInputChanged(track(androidx.media3.common.MimeTypes.AUDIO_AAC, 2))
        assertEquals("AAC decoded · 6ch", engine.audioOutputLabel(playing = aac51))
        engine.audioTrackStarted(first, aac51)
        // Another track: its own output is not known yet.
        val ac3 = track(androidx.media3.common.MimeTypes.AUDIO_AC3, 6)
        engine.audioInputChanged(ac3)
        assertEquals("Dolby Digital decoded · 6ch", engine.audioOutputLabel(playing = ac3))
        // Its output started, then the sound stopped.
        engine.audioTrackStarted(first, ac3)
        engine.audioStopped()
        assertEquals("Dolby Digital decoded · 6ch", engine.audioOutputLabel(playing = ac3))
    }
}
