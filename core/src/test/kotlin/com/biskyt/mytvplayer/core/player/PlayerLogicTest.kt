package com.biskyt.mytvplayer.core.player

import com.biskyt.mytvplayer.core.player.OverlayState.CONTROLS
import com.biskyt.mytvplayer.core.player.OverlayState.GUIDE
import com.biskyt.mytvplayer.core.player.OverlayState.NONE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerLogicTest {
    private val mapper = KeyActionMapper(skipBackMs = 10_000, skipForwardMs = 30_000)

    @Test
    fun `left and right skip by configured amounts`() {
        assertEquals(PlayerAction.SeekBy(-10_000), mapper.map(RemoteKey.LEFT, NONE, isLive = true))
        assertEquals(PlayerAction.SeekBy(30_000), mapper.map(RemoteKey.RIGHT, CONTROLS, isLive = false))
        assertEquals(PlayerAction.SeekBy(-5_000), KeyActionMapper(5_000, 60_000).map(RemoteKey.REWIND, NONE, isLive = false))
    }

    @Test
    fun `up and down show the controls, or zap on live tv when that's turned on`() {
        assertEquals(PlayerAction.ToggleOverlay, mapper.map(RemoteKey.UP, NONE, isLive = true))
        assertEquals(PlayerAction.ToggleOverlay, mapper.map(RemoteKey.DOWN, NONE, isLive = false))
        assertEquals(PlayerAction.Zap(-1), mapper.map(RemoteKey.CHANNEL_UP, NONE, isLive = true))
        val zapper = KeyActionMapper(10_000, 30_000, upDownZaps = true)
        assertEquals(PlayerAction.Zap(-1), zapper.map(RemoteKey.UP, NONE, isLive = true))
        assertEquals(PlayerAction.Zap(1), zapper.map(RemoteKey.DOWN, NONE, isLive = true))
        assertEquals(PlayerAction.Zap(1), zapper.map(RemoteKey.DOWN, CONTROLS, isLive = true)) // with the controls up too
        assertEquals(PlayerAction.None, mapper.map(RemoteKey.DOWN, CONTROLS, isLive = true))
        assertEquals(PlayerAction.ToggleOverlay, zapper.map(RemoteKey.UP, NONE, isLive = false))
    }

    @Test
    fun `scrubbing steps, speeds up while held and stays within the programme`() {
        assertEquals(40_000L, scrubTarget(10_000, 30_000, repeat = 0, 0L..100_000))
        assertEquals(70_000L, scrubTarget(10_000, 30_000, repeat = 5, 0L..1_000_000)) // twice as fast
        assertEquals(370_000L, scrubTarget(10_000, 30_000, repeat = 500, 0L..1_000_000)) // capped at 12×
        assertEquals(0L, scrubTarget(5_000, -10_000, repeat = 0, 0L..100_000))
        assertEquals(100_000L, scrubTarget(90_000, 30_000, repeat = 0, 0L..100_000))
    }

    @Test
    fun `back closes overlays first then stops playback`() {
        assertEquals(PlayerAction.CloseGuide, mapper.map(RemoteKey.BACK, GUIDE, isLive = true))
        assertEquals(PlayerAction.HideOverlay, mapper.map(RemoteKey.BACK, CONTROLS, isLive = true))
        assertEquals(PlayerAction.StopAndExit, mapper.map(RemoteKey.BACK, NONE, isLive = true))
        assertEquals(PlayerAction.StopAndExit, mapper.map(RemoteKey.STOP, GUIDE, isLive = true))
    }

    @Test
    fun `the media buttons work with the guide panel open`() {
        assertEquals(PlayerAction.SeekBy(30_000), mapper.map(RemoteKey.FAST_FORWARD, GUIDE, isLive = true))
        assertEquals(PlayerAction.SeekBy(-10_000), mapper.map(RemoteKey.REWIND, GUIDE, isLive = true))
        assertEquals(PlayerAction.TogglePlayPause, mapper.map(RemoteKey.PLAY_PAUSE, GUIDE, isLive = true))
        assertEquals(PlayerAction.None, mapper.map(RemoteKey.CHANNEL_UP, GUIDE, isLive = true))
    }

    @Test
    fun `guide, ok and digits`() {
        assertEquals(PlayerAction.OpenGuide, mapper.map(RemoteKey.GUIDE, NONE, isLive = true))
        assertEquals(PlayerAction.None, mapper.map(RemoteKey.GUIDE, NONE, isLive = false))
        assertEquals(PlayerAction.None, mapper.map(RemoteKey.LEFT, GUIDE, isLive = true))
        assertEquals(PlayerAction.Digit(7), mapper.map(RemoteKey.DIGIT, NONE, isLive = true, digit = 7))
    }

    @Test
    fun `menu opens the more menu, and closes it when open`() {
        assertEquals(PlayerAction.OpenMenu, mapper.map(RemoteKey.MENU, NONE, isLive = true))
        assertEquals(PlayerAction.OpenMenu, mapper.map(RemoteKey.MENU, GUIDE, isLive = true)) // in the guide's place
        assertEquals(PlayerAction.OpenMenu, mapper.map(RemoteKey.MENU, CONTROLS, isLive = true))
        assertEquals(PlayerAction.CloseMenu, mapper.map(RemoteKey.MENU, CONTROLS, isLive = false, menuOpen = true))
    }

    @Test
    fun `ok pauses and shows the controls, unless up and down zap live tv`() {
        // Up and down show the controls without pausing.
        assertEquals(PlayerAction.PauseAndShowControls, mapper.map(RemoteKey.OK, NONE, isLive = true))
        assertEquals(PlayerAction.PauseAndShowControls, mapper.map(RemoteKey.OK, NONE, isLive = false))
        // Up and down zap, so OK is the way to the controls: it only shows them.
        val zapper = KeyActionMapper(10_000, 30_000, upDownZaps = true)
        assertEquals(PlayerAction.ToggleOverlay, zapper.map(RemoteKey.OK, NONE, isLive = true))
        assertEquals(PlayerAction.PauseAndShowControls, zapper.map(RemoteKey.OK, NONE, isLive = false))
        // With the controls up (and focus on the video), OK hides them.
        assertEquals(PlayerAction.ToggleOverlay, mapper.map(RemoteKey.OK, CONTROLS, isLive = true))
        assertEquals(PlayerAction.ToggleOverlay, zapper.map(RemoteKey.OK, CONTROLS, isLive = true))
        // Info only shows or hides them.
        assertEquals(PlayerAction.ToggleOverlay, mapper.map(RemoteKey.INFO, NONE, isLive = false))
    }

    private val tracks = listOf(
        TextTrack("full-en", "en", forced = false),
        TextTrack("forced-en", "eng", forced = true),
        TextTrack("full-fr", "fr-FR", forced = false),
        TextTrack("forced-fr", "fra", forced = true)
    )

    @Test
    fun `subtitle policy`() {
        assertNull(SubtitlePolicy.choose(tracks, SubtitleMode.OFF, "en"))
        assertEquals("forced-en", SubtitlePolicy.choose(tracks, SubtitleMode.FORCED_ONLY, "en-GB")?.id)
        assertEquals("forced-fr", SubtitlePolicy.choose(tracks, SubtitleMode.FORCED_ONLY, "fr")?.id)
        assertNull(SubtitlePolicy.choose(tracks, SubtitleMode.FORCED_ONLY, "de"))
        assertEquals("full-en", SubtitlePolicy.choose(tracks, SubtitleMode.ALWAYS, "en")?.id)
        assertEquals("forced-x", SubtitlePolicy.choose(listOf(TextTrack("forced-x", null, true)), SubtitleMode.FORCED_ONLY, "en")?.id)
        assertEquals("forced-en", SubtitlePolicy.choose(tracks.drop(2) + tracks[1], SubtitleMode.ALWAYS, "en")?.id)
    }
}
