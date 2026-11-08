package com.biskyt.mytvplayer

import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.biskyt.mytvplayer.data.db.ChannelEntity
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app on a real Android: what the JVM-based tests can't show, such as Android's own regex engine (ICU), which
 * crashed M3U set-up in 0.9.5 while every JVM test passed. One test, as each needs a fresh install.
 */
@RunWith(AndroidJUnit4::class)
class SmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    /** Serves the playlist from the device itself, so the test needs no outside network. */
    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody(PLAYLIST)
        }
        start()
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun a_fresh_install_sets_up_an_M3U_playlist() {
        waitFor(hasText("Add my TV provider"))
        click(hasText("Add my TV provider"))
        click(hasText("M3U playlist URL"))
        click(hasText("https://example.com/playlist.m3u"))
        waitFor(hasSetTextAction())
        compose.onNode(hasSetTextAction()).performTextInput(server.url("/playlist.m3u").toString())
        click(hasText("Continue"))
        // Downloaded and its groups read: its categories are offered.
        waitFor(hasText("Smoke Test News", substring = true))
        click(hasText("Continue"))
        // Then its channels are read, catch-up and all (the regex that crashed 0.9.5 runs here).
        val one = waitForChannel("Channel One {HD}")
        assertEquals(2, one.archiveDays)
        backInSettingsKeepsFocus()
    }

    /**
     * Back with the remote from a screen that a Settings row opened: that row has focus again (the owner, 2026-11-08,
     * on a real TV; the JVM tests passed).
     */
    private fun backInSettingsKeepsFocus() {
        val playback = hasText("Playback") and hasClickAction()
        click(hasContentDescription("Settings"))
        waitFor(playback)
        compose.onNode(playback).performSemanticsAction(SemanticsActions.RequestFocus)
        waitFor(isFocused() and hasText("Playback", substring = true))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("Skip back", substring = true))
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        // The Playback screen gone, Settings shown again.
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasText("Skip back", substring = true)).fetchSemanticsNodes().isEmpty() }
        waitFor(playback)
        waitFor(isFocused() and hasText("Playback", substring = true) and hasClickAction(), FOCUS_MS) {
            "Back in Settings, focus is on: ${compose.onAllNodes(isFocused()).fetchSemanticsNodes().map { it.config }}"
        }
    }

    /** The channel once the playlist's channels are stored (they download in the background, after set-up). */
    private fun waitForChannel(name: String): ChannelEntity {
        val c = InstrumentationRegistry.getInstrumentation().targetContext.container
        var found: ChannelEntity? = null
        compose.waitUntil(WAIT_MS) {
            found = runBlocking { c.playlists.allIds().firstNotNullOfOrNull { c.db.channels().search(it, name, 1).firstOrNull() } }
            found != null
        }
        return found!!
    }

    private fun waitFor(matcher: SemanticsMatcher, timeoutMs: Long = WAIT_MS, failure: () -> String = { "Not shown: $matcher" }) = try {
        compose.waitUntil(timeoutMs) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
    } catch (e: ComposeTimeoutException) {
        throw AssertionError(failure(), e)
    }

    private fun click(matcher: SemanticsMatcher) {
        waitFor(matcher and hasClickAction())
        compose.onNode(matcher and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
    }

    private companion object {
        /** Generous: an emulator on a shared CI runner can be slow. */
        const val WAIT_MS = 30_000L

        /** Focus is given back within a few frames: three seconds is plenty, even on a slow emulator. */
        const val FOCUS_MS = 3_000L

        /** The attributes the app reads, catch-up included, so each of its regexes runs on Android's engine. */
        val PLAYLIST = """
            #EXTM3U
            #EXTINF:-1 tvg-id="One.uk" tvg-name="One" tvg-logo="http://logo.example/one.png" tvg-country="GB" tvg-language="English" group-title="Smoke Test News" catchup="append" catchup-days="2" catchup-source="?utc={utc}&lutc={lutc}&d={duration:60}" http-user-agent="SmokeTest/1.0",Channel One {HD}
            http://stream.example/one.m3u8
            #EXTINF:-1 tvg-id="Two.uk" group-title="Smoke Test Films",Channel Two
            #EXTVLCOPT:http-referrer=http://ref.example/
            http://stream.example/two.ts
        """.trimIndent()
    }
}
