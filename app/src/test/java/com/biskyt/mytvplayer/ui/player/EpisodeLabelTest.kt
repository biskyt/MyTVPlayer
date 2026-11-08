package com.biskyt.mytvplayer.ui.player

import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.AppText
import com.biskyt.mytvplayer.core.xtream.EpisodeInfo
import com.biskyt.mytvplayer.testutil.PlainApp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A provider's episode title is shown as it is; the app's own words around it follow the app's language. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = PlainApp::class)
class EpisodeLabelTest {
    private val app = ApplicationProvider.getApplicationContext<PlainApp>()

    @After fun followDevice() = AppText.setLanguage(app, "")

    private fun episode(title: String) = EpisodeInfo(1, 2, 3, title, "mp4", null, null, null)

    @Test
    fun `the series name, a language tag and the episode code are left out of the name shown`() {
        val long = episode("EN: Masterchef USA (2010) - S02E03 - Auditions Day 1")
        assertEquals("Auditions Day 1", episodeName(long, SHOW))
        assertEquals("Series 2 · Episode 3 · Auditions Day 1", episodeLabel(long, SHOW))
        // The provider's words are kept as they are, for Continue watching and the sync.
        assertEquals("EN: Masterchef USA (2010) - S02E03 - Auditions Day 1", providerTitle(long))
        // Nothing but the series and the code: the app names it.
        assertEquals("Episode 3", episodeName(episode("Masterchef USA - S02E03"), SHOW))
    }

    @Test
    fun `a provider's title is kept as it is, and only the app's words change language`() {
        AppText.setLanguage(app, "es")
        val titled = episode("The Pilot")
        assertEquals("The Pilot", episodeName(titled, SHOW))
        assertEquals("Temporada 2 · Episodio 3 · The Pilot", episodeLabel(titled, SHOW))
        // Only its number: shown as the provider wrote it, and left out of the label so the number isn't there twice.
        val numbered = episode("Episode 3")
        assertEquals("Episode 3", episodeName(numbered, SHOW))
        assertEquals("Temporada 2 · Episodio 3", episodeLabel(numbered, SHOW))
    }

    @Test
    fun `an episode with no title is named by the app, in its language`() {
        AppText.setLanguage(app, "es")
        val untitled = episode("")
        assertNull(providerTitle(untitled))
        assertEquals("Episodio 3", episodeName(untitled, SHOW))
        assertEquals("Temporada 2 · Episodio 3", episodeLabel(untitled, SHOW))
    }

    private companion object {
        const val SHOW = "Masterchef USA"
    }
}
