package com.biskyt.mytvplayer.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.testutil.PlainApp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioTrack

/** The check in Settings of whether the TV can send AAC unchanged to the amplifier (the owner, 2026-11-15). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = PlainApp::class)
class AacPassthroughTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun tearDown() = ShadowAudioTrack.clearDirectPlaybackSupportedFormats()

    @Test
    fun `a TV that accepts AAC as it is says yes, and one that does not says no`() {
        assertEquals(AacPassthrough.NO, AacPassthrough.check(context))
        val aac51 = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_AAC_LC)
            .setSampleRate(48_000)
            .setChannelMask(AudioFormat.CHANNEL_OUT_5POINT1)
            .build()
        val movie = AudioAttributes.Builder().setUsage(
            AudioAttributes.USAGE_MEDIA
        ).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build()
        ShadowAudioTrack.addDirectPlaybackSupport(aac51, movie)
        assertEquals(AacPassthrough.YES, AacPassthrough.check(context))
    }

    @Test
    fun `an amplifier that accepts AAC behind a TV that does not send it is told apart`() {
        assertEquals(AacPassthrough.NOT_BY_TV, AacPassthrough.of(direct = false, amplifier = true))
        assertEquals(AacPassthrough.YES, AacPassthrough.of(direct = true, amplifier = false))
        assertEquals(AacPassthrough.NO, AacPassthrough.of(direct = false, amplifier = false))
    }

    @Test
    @Config(sdk = [28])
    fun `Android 9 cannot tell`() {
        assertEquals(AacPassthrough.UNKNOWN, AacPassthrough.check(context))
    }
}
