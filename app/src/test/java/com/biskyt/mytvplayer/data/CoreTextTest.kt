package com.biskyt.mytvplayer.data

import com.biskyt.mytvplayer.appString
import com.biskyt.mytvplayer.core.epg.CategoryLanguageDetector
import com.biskyt.mytvplayer.core.ui.AccentPalette
import com.biskyt.mytvplayer.core.xtream.XtreamException
import com.biskyt.mytvplayer.core.xtream.XtreamException.Reason
import com.biskyt.mytvplayer.testutil.PlainApp
import com.biskyt.mytvplayer.ui.common.distinctLabels
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = PlainApp::class)
class CoreTextTest {
    @Test
    fun `every language group has its heading`() {
        assertEquals(CategoryLanguageDetector.namedGroups, groupLabels.keys)
        assertEquals("English (UK)", groupLabel("UK"))
        assertEquals("English (Ireland)", groupLabel("IE"))
        assertEquals("Balkan", groupLabel("EXYU"))
        assertEquals("Other", groupLabel(null))
        // Other countries by their name, and anything else (a language's name) as it is.
        assertEquals("Austria", groupLabel("AT"))
        assertEquals("Swahili", groupLabel("Swahili"))
        // Two characters that are not a country: in capitals, as before 1.10.2.
        assertEquals("4K", groupLabel("4k"))
    }

    @Test
    fun `adult categories are all named with XXX first, in place of the provider's section code`() {
        val adult = CategoryLanguageDetector.ADULT
        assertEquals("XXX: FOR ADULTS", categoryLabel("18: FOR ADULTS", adult))
        assertEquals("XXX MOVIES", categoryLabel("GLOBAL: XXX MOVIES", adult))
        assertEquals("XXX: ADULT MOVIES", categoryLabel("GLOBAL | ADULT MOVIES", adult))
        assertEquals("XXX", categoryLabel("UK | XXX", adult))
        assertEquals("XXX: 18+ Movies", categoryLabel("18+ Movies", adult))
        // Others keep the playlist's name.
        assertEquals("18: FOR ADULTS", categoryLabel("18: FOR ADULTS", null))
        assertEquals("UK | NEWS", categoryLabel("UK | NEWS", "UK"))
    }

    @Test
    fun `category columns list categories from A to Z by the names shown, adult ones near the end`() {
        val names = listOf(
            "UK | NEWS" to "UK",
            "18: FOR ADULTS" to CategoryLanguageDetector.ADULT,
            "UK | ENTERTAINMENT" to "UK",
            "Zoo" to null
        )
        val shown = distinctLabels(names, { categoryLabel(it.first, it.second) }, { it.second }) { _, label -> label }
        assertEquals(listOf("UK | ENTERTAINMENT", "UK | NEWS", "XXX: FOR ADULTS", "Zoo"), shown)
    }

    @Test
    fun `adult categories with the same name are told apart by their provider's code`() {
        val adult = CategoryLanguageDetector.ADULT
        val names = listOf("UK | XXX", "DE | XXX")
        val shown = distinctLabels(names, { categoryLabel(it, adult) }, { labelGroup(it, adult) }) { _, label -> label }
        assertEquals(listOf("XXX · English (UK)", "XXX · German"), shown)
    }

    @Test
    fun `every accent colour has its name`() {
        assertEquals(
            listOf("Mint", "Amber", "Violet", "Sky", "Coral", "Lime", "Pink", "Silver"),
            AccentPalette.presets.map { appString(accentName(it.id)) }
        )
    }

    @Test
    fun `xtream failures are told in the app's words, keeping the English for logs`() {
        val unreachable = XtreamException("Couldn't reach the server: timeout", IOException("timeout"), Reason.UNREACHABLE)
        assertEquals("Couldn't reach the server: timeout", userMessage(unreachable))
        val error = XtreamException("Server returned error 503", reason = Reason.SERVER_ERROR, code = 503)
        assertEquals("Server returned error 503", userMessage(error))
        val login = XtreamException("Login failed (log)", reason = Reason.LOGIN_FAILED)
        assertEquals("Login failed: check the server address, username and password", userMessage(login))
        assertEquals("Login failed (log)", login.message)
        // Shown once wrapped, as before.
        val wrapped = runCatching { withUserXtreamErrors { throw login } }.exceptionOrNull()
        assertEquals("Login failed: check the server address, username and password", wrapped?.message)
        assertEquals("Not an Xtream error", userMessage(IOException("Not an Xtream error")))
    }
}
