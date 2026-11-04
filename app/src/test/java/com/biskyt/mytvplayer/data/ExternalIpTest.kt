package com.biskyt.mytvplayer.data

import com.biskyt.mytvplayer.core.net.PublicIp
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class) // the test scheduler's virtual clock
class ExternalIpTest {
    private val network = MutableStateFlow(NetworkState(1, vpn = false))
    private val calls = AtomicInteger()
    private var answers = mutableListOf<() -> PublicIp>()

    private fun lookup(): PublicIp {
        calls.incrementAndGet()
        return answers.removeAt(0)()
    }

    @Test
    fun `the address is asked for once, then again only when the network or the VPN changes`() = runTest {
        answers += { PublicIp("1.1.1.1", "GB") }
        answers += { PublicIp("185.12.34.56", "NL") }
        val ip = ExternalIp(network, ::lookup, backgroundScope)
        backgroundScope.launch { ip.current.collect {} }
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(PublicIp("1.1.1.1", "GB"), ip.current.value)

        // Nothing changed: no new lookup.
        advanceTimeBy(60_000)
        assertEquals(1, calls.get())

        // The VPN starts: unknown at once, then the new address.
        network.value = NetworkState(2, vpn = true)
        runCurrent()
        assertNull(ip.current.value)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(PublicIp("185.12.34.56", "NL"), ip.current.value)
        assertEquals(2, calls.get())
    }

    @Test
    fun `when the top bar comes back on the same network, the last answer stands`() = runTest {
        answers += { PublicIp("1.1.1.1", "GB") }
        val ip = ExternalIp(network, ::lookup, backgroundScope)
        val shown = backgroundScope.launch { ip.current.collect {} }
        advanceTimeBy(2_000)
        runCurrent()
        shown.cancel()
        // Playback, with no top bar, for longer than the flow is kept.
        advanceTimeBy(60_000)
        backgroundScope.launch { ip.current.collect {} }
        runCurrent()
        assertEquals(PublicIp("1.1.1.1", "GB"), ip.current.value)
        assertEquals(1, calls.get())
    }

    @Test
    fun `an unexpected failure is no crash`() = runTest {
        repeat(3) { answers += { throw IllegalStateException("closed") } }
        val ip = ExternalIp(network, ::lookup, backgroundScope)
        backgroundScope.launch { ip.current.collect {} }
        advanceTimeBy(30_000)
        runCurrent()
        assertNull(ip.current.value)
    }

    @Test
    fun `a failed lookup is tried again, then given up`() = runTest {
        repeat(3) { answers += { throw IOException("offline") } }
        val ip = ExternalIp(network, ::lookup, backgroundScope)
        backgroundScope.launch { ip.current.collect {} }
        advanceTimeBy(30_000)
        runCurrent()
        assertNull(ip.current.value)
        assertEquals(3, calls.get())
    }

    @Test
    fun `with no network, nothing is asked`() = runTest {
        network.value = NetworkState(null, vpn = false)
        val ip = ExternalIp(network, ::lookup, backgroundScope)
        backgroundScope.launch { ip.current.collect {} }
        advanceTimeBy(10_000)
        runCurrent()
        assertNull(ip.current.value)
        assertEquals(0, calls.get())
    }
}
