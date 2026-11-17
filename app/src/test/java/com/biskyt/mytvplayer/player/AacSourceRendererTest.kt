package com.biskyt.mytvplayer.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.testutil.PlainApp
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** With Dolby Digital on, Media3's own audio renderer is replaced by one that tells the sink the source format. */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = PlainApp::class)
class AacSourceRendererTest {
    @Test
    fun `Media3's audio renderer is replaced, in its place`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val renderers = DefaultRenderersFactory(context)
            .createRenderers(
                Handler(Looper.getMainLooper()),
                object : VideoRendererEventListener {},
                object : AudioRendererEventListener {},
                {},
                {}
            )
            .toMutableList()
        val index = renderers.indexOfFirst { it is MediaCodecAudioRenderer }
        assertTrue(index >= 0)
        val sink = DolbyDigitalSink(DefaultAudioSink.Builder(context).build())
        val ours = AacSourceRenderer(
            context,
            MediaCodecAdapter.Factory.getDefault(context),
            MediaCodecSelector.DEFAULT,
            false,
            null,
            null,
            sink
        )
        AacSourceRenderer.replaceIn(renderers, ours)
        assertSame(ours, renderers[index])
    }
}
