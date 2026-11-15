package com.biskyt.mytvplayer.player

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.testutil.PlainApp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.shadows.ShadowAudioTrack

/** The check in Settings of whether the TV can send AAC unchanged to the amplifier (the owner, 2026-11-15). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = PlainApp::class)
class AacPassthroughTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun tearDown() = ShadowAudioTrack.clearDirectPlaybackSupportedFormats()

    private fun allowAac() {
        val aac51 = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_AAC_LC)
            .setSampleRate(48_000)
            .setChannelMask(AudioFormat.CHANNEL_OUT_5POINT1)
            .build()
        ShadowAudioTrack.addDirectPlaybackSupport(aac51, PlaybackEngine.MOVIE.audioAttributesV21.audioAttributes)
    }

    private fun connectAmplifier() {
        val hdmi = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_HDMI_ARC).build()
        shadowOf(context.getSystemService(AudioManager::class.java)).addOutputDevice(hdmi, false)
    }

    @Test
    fun `a TV that accepts AAC as it is says yes, and one that does not says no`() {
        connectAmplifier()
        assertEquals(AacPassthrough.NO, AacPassthrough.check(context))
        allowAac()
        assertEquals(AacPassthrough.YES, AacPassthrough.check(context))
    }

    @Test
    fun `with no amplifier connected, AAC to the TV's own speakers is not a yes`() {
        allowAac()
        assertEquals(AacPassthrough.NO, AacPassthrough.check(context))
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
