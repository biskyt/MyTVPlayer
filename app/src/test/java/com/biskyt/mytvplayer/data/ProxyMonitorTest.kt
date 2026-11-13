package com.biskyt.mytvplayer.data

import com.biskyt.mytvplayer.core.net.ProxyCheck
import com.biskyt.mytvplayer.core.net.ProxySettings
import com.biskyt.mytvplayer.core.net.ProxyType
import com.biskyt.mytvplayer.core.net.PublicIp
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** "Warn if the proxy is not working" (the owner, 2026-11-13): when the active playlist's proxy is checked, and what it shows. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProxyMonitorTest {
    private val home = PublicIp("1.1.1.1", "GB")
    private val away = PublicIp("185.12.34.56", "NL")
    private val socks = ProxySettings(ProxyType.SOCKS, "proxy.example", 1080)
    private val network = MutableStateFlow(NetworkState(1, vpn = false))
    private val proxy = MutableStateFlow<ProxySettings?>(null)
    private var checks = 0
    private var answer: () -> ProxyCheck = { ProxyCheck.Working(away, home) }

    private fun TestScope.newMonitor() = ProxyMonitor(network, proxy, {
        checks++
        answer()
    }, backgroundScope, clock = { testScheduler.currentTime })

    /** Shows [monitor]'s status (collects it) until the job is cancelled. */
    private fun TestScope.show(monitor: ProxyMonitor) = backgroundScope.launch { monitor.status.collect {} }

    /** A monitor shown for the whole test. */
    private fun TestScope.monitor() = newMonitor().also { show(it) }

    /** A minute on: past the settle wait and the retries. (advanceUntilIdle does not wait for backgroundScope's work.) */
    private fun TestScope.settle() {
        advanceTimeBy(60_000)
        runCurrent()
    }

    @Test
    fun `without a proxy nothing is checked or shown, and with one it is checked, and again when the network changes`() = runTest {
        val monitor = monitor()
        settle()
        assertEquals(0, checks)
        assertNull(monitor.status.value)

        proxy.value = socks
        settle()
        assertEquals(1, checks)
        assertEquals(ProxyCheck.Working(away, home), monitor.status.value)

        network.value = NetworkState(2, vpn = false)
        settle()
        assertEquals(2, checks)

        proxy.value = null
        settle()
        assertNull("no proxy: nothing to show", monitor.status.value)
        assertEquals(2, checks)
    }

    @Test
    fun `the same address as without the proxy is the warning's answer, and it stands for a while when shown again`() = runTest {
        answer = { ProxyCheck.SameAddress(home) }
        proxy.value = socks
        val monitor = ProxyMonitor(network, proxy, {
            checks++
            answer()
        }, backgroundScope, clock = { testScheduler.currentTime })
        var shown = show(monitor)
        settle()
        assertEquals(ProxyCheck.SameAddress(home), monitor.status.value)
        assertEquals(1, checks)

        // Hidden (playback) and shown again soon after: the answer stands.
        shown.cancel()
        settle()
        shown = show(monitor)
        settle()
        assertEquals(ProxyCheck.SameAddress(home), monitor.status.value)
        assertEquals(1, checks)

        // Shown again much later: checked again.
        shown.cancel()
        advanceTimeBy(15 * 60_000)
        show(monitor)
        settle()
        assertEquals(2, checks)
    }

    @Test
    fun `while it shows, it is checked again once the answer is old, and a proxy that stops working then warns`() = runTest {
        proxy.value = socks
        val monitor = monitor()
        settle()
        assertEquals(ProxyCheck.Working(away, home), monitor.status.value)

        answer = { ProxyCheck.SameAddress(home) }
        advanceTimeBy(9 * 60_000)
        runCurrent()
        assertEquals("not old yet", 1, checks)
        settle()
        settle()
        assertEquals(2, checks)
        assertEquals(ProxyCheck.SameAddress(home), monitor.status.value)
    }

    @Test
    fun `a failure is tried again a few times, and then shown`() = runTest {
        answer = { throw IOException("Connection refused") }
        proxy.value = socks
        val monitor = monitor()
        advanceTimeBy(2_000)
        assertNull("still trying", monitor.status.value)
        settle()
        assertEquals(3, checks)
        assertEquals(ProxyCheck.Failed("Connection refused"), monitor.status.value)
    }

    @Test
    fun `with no network, nothing is checked`() = runTest {
        network.value = NetworkState(null, vpn = false)
        proxy.value = socks
        val monitor = monitor()
        settle()
        assertEquals(0, checks)
        assertNull(monitor.status.value)
    }
}
