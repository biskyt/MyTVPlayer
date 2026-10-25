package com.biskyt.mytvplayer.core.player

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class Hdr10PlusOffTest {
    private val aud = nal(35, 0x50)
    private val slice = nal(1, 0x11, 0x22, 0x33)
    private val rpu = nal(62, 0x08, 0x09)
    private val hdr10PlusMessage = intArrayOf(4, 9, 0xB5, 0x00, 0x3C, 0x00, 0x01, 4, 1, 0x55, 0x66)
    private val otherMessage = intArrayOf(4, 9, 0xB5, 0x00, 0x31, 0x00, 0x01, 4, 1, 0x55, 0x66) // another provider
    private val hdr10Plus = nal(39, *hdr10PlusMessage, 0x80)
    private val otherSei = nal(39, *otherMessage, 0x80)

    @Test
    fun `switches off only the HDR10+ NAL, in place`() {
        val data = buffer(aud + hdr10Plus + otherSei + slice + rpu)
        off(data)
        assertArrayEquals(aud + retyped(hdr10Plus) + otherSei + slice + rpu, data.bytes())
    }

    @Test
    fun `switches off a NAL of several HDR10+ messages`() {
        val two = nal(39, *hdr10PlusMessage, *hdr10PlusMessage, 0x80)
        val data = buffer(two + slice)
        off(data)
        assertArrayEquals(retyped(two) + slice, data.bytes())
    }

    @Test
    fun `leaves a NAL that holds another message too`() {
        val both = nal(39, *otherMessage, *hdr10PlusMessage, 0x80)
        val data = buffer(both + slice)
        off(data)
        assertArrayEquals(both + slice, data.bytes())
    }

    @Test
    fun `reads a message past its emulation prevention bytes`() {
        // An HDR10+ message whose payload holds 00 00 03 00: sent with the 03, read without it.
        val escaped = nal(39, 4, 11, 0xB5, 0x00, 0x3C, 0x00, 0x01, 4, 1, 0x00, 0x00, 0x03, 0x00, 0x07, 0x80)
        val data = buffer(escaped + slice)
        off(data)
        assertArrayEquals(retyped(escaped) + slice, data.bytes())
    }

    @Test
    fun `leaves a NAL whose HDR10+ message is followed by another`() {
        val both = nal(39, *hdr10PlusMessage, *otherMessage, 0x80)
        val data = buffer(both + slice)
        off(data)
        assertArrayEquals(both + slice, data.bytes())
    }

    @Test
    fun `switches off HDR10+ in one sample after another`() {
        repeat(2) {
            val data = buffer(otherSei + hdr10Plus + slice)
            off(data)
            assertArrayEquals(otherSei + retyped(hdr10Plus) + slice, data.bytes())
        }
    }

    @Test
    fun `leaves a NAL whose HDR10+ message is followed by one of type 128`() {
        val both = nal(39, *hdr10PlusMessage, 0x80, 1, 0x00, 0x80)
        val data = buffer(both + slice)
        off(data)
        assertArrayEquals(both + slice, data.bytes())
    }

    @Test
    fun `leaves a message cut short just after an emulation prevention byte`() {
        // Says 12 bytes, but the NAL ends after the 03 that stands before its last.
        val cut = nal(39, 4, 12, 0xB5, 0x00, 0x3C, 0x00, 0x01, 4, 1, 0x55, 0x00, 0x00, 0x03)
        val data = buffer(cut + slice)
        off(data)
        assertArrayEquals(cut + slice, data.bytes())
    }

    @Test
    fun `stops at the first slice`() {
        val sample = slice + hdr10Plus // not a real stream: HDR10+ never follows the picture
        val data = buffer(sample)
        off(data)
        assertArrayEquals(sample, data.bytes())
    }

    @Test
    fun `leaves a cut-short message alone`() {
        val cut = hdr10Plus.copyOf(hdr10Plus.size - 6)
        val data = buffer(cut)
        off(data)
        assertArrayEquals(cut, data.bytes())
    }

    @Test
    fun `works from a buffer's position, with three-byte start codes and leading bytes`() {
        val short = byteArrayOf(0, 0, 1) + hdr10Plus.copyOfRange(4, hdr10Plus.size)
        val data = ByteBuffer.allocateDirect(3 + 2 + short.size + slice.size)
        data.put(byteArrayOf(7, 7, 7, 9, 9)).put(short).put(slice).flip()
        data.position(3)
        off(data)
        assertEquals(3, data.position())
        assertArrayEquals(byteArrayOf(9, 9) + retyped(short, at = 3) + slice, data.bytes())
    }

    private val hdr10PlusOff = Hdr10PlusOff()

    private fun off(data: ByteBuffer) = hdr10PlusOff.apply(data, data.position(), data.limit())

    private fun nal(type: Int, vararg body: Int) = byteArrayOf(0, 0, 0, 1, (type shl 1).toByte(), 1) + body.map { it.toByte() }

    /** [nal] switched off: NAL type 48. */
    private fun retyped(nal: ByteArray, at: Int = 4) = nal.copyOf().also { it[at] = (48 shl 1).toByte() }

    private fun buffer(bytes: ByteArray): ByteBuffer = ByteBuffer.allocateDirect(bytes.size).apply { put(bytes).flip() }

    private fun ByteBuffer.bytes() = ByteArray(remaining()).also { duplicate().get(it) }
}
