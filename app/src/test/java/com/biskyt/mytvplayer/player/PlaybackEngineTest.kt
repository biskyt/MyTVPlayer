package com.biskyt.mytvplayer.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.data.Settings
import com.biskyt.mytvplayer.testutil.PlainApp
import com.biskyt.mytvplayer.ui.player.RetryBudget
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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
        engine.play("https://example.invalid/bbc/stream/BBC_FOUR/hevc_iptv_mse_v0.mpd", hls = null)
        assertEquals(0, unsupported)
        // SmoothStreaming isn't included.
        engine.play("https://example.invalid/channel.ism/Manifest", hls = null)
        assertEquals(1, unsupported)
        // The error shows its address.
        assertEquals("https://example.invalid/channel.ism/Manifest", unsupportedUrl)
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
        assertEquals("Audio · Stereo", engine.audioOutputLabel(androidx.media3.common.Tracks(listOf(group))))
        assertEquals("No audio", engine.audioOutputLabel(androidx.media3.common.Tracks.EMPTY))
    }
}
