package com.biskyt.mytvplayer.core.player

import java.nio.ByteBuffer

/**
 * Switches off the HDR10+ metadata in an HEVC sample (Annex B, as Media3's Matroska and MP4 readers hand it to the
 * decoder) bound for a Dolby Vision decoder. Some TVs (the owner's Philips, a MediaTek chip) show a Dolby Vision
 * profile 8 file that also carries HDR10+ as HDR10: proven with the diagnostics app in MyPlexPlayer's
 * `spikes/dv-diagnostics`.
 *
 * Done the light way, in place and without copying: an SEI NAL holding only HDR10+ gets an unspecified NAL type (48),
 * which the HEVC spec says decoders ignore, so nothing moves. HDR10+ travels in prefix SEI, ahead of the picture data,
 * so reading stops at the first slice: a sample costs a few hundred bytes of reading, however large it is. An SEI NAL
 * that also holds other messages is left as it is.
 */
class Hdr10PlusOff {
    // Where the SEI payload being read is (past its emulation prevention bytes, 00 00 03): kept here, not in an object
    // made for each, as this runs for every frame. So one per decoder: it isn't for use from two threads at once.
    private var pos = 0
    private var end = 0
    private var zeros = 0

    /** Switches off each SEI NAL in [data] from [from] to [end] that holds only HDR10+. */
    fun apply(data: ByteBuffer, from: Int, end: Int) {
        var header = nextNal(data, from, end)
        while (header >= 0 && header + 1 < end) {
            val type = (data.get(header).toInt() and 0x7E) shr 1
            if (type < FIRST_NON_SLICE) return // the picture data: no HDR10+ after it
            val next = nextNal(data, header + 2, end)
            if (type == PREFIX_SEI && onlyHdr10Plus(data, header + 2, if (next < 0) end else next - 3)) {
                // Keep the forbidden bit and the layer id's top bit; only the type changes.
                data.put(header, ((UNSPECIFIED shl 1) or (data.get(header).toInt() and 0x81)).toByte())
            }
            header = next
        }
    }

    /** Whether the SEI payload in [data] from [from] to [end] holds HDR10+ messages and nothing else. */
    private fun onlyHdr10Plus(data: ByteBuffer, from: Int, end: Int): Boolean {
        // Another kind of message first: not read any further.
        if (from >= end || data.get(from).toInt() != USER_DATA_REGISTERED) return false
        pos = from
        this.end = end
        zeros = 0
        var found = false
        while (!atTrailingBits(data)) {
            if (!hdr10PlusMessage(data)) return false
            found = true
        }
        return found
    }

    /** Whether the next message is a whole HDR10+ one (read past it). */
    private fun hdr10PlusMessage(data: ByteBuffer): Boolean {
        val type = extended(data)
        val size = extended(data)
        var whole = type == USER_DATA_REGISTERED && size > HDR10_PLUS_ID.size
        var i = 0
        while (whole && i < size) {
            val b = read(data)
            whole = when {
                b < 0 -> false // cut short
                i < HDR10_PLUS_ID.size -> b == HDR10_PLUS_ID[i]
                i == HDR10_PLUS_ID.size -> b <= 1 // the version: 0 or 1
                else -> true
            }
            i++
        }
        return whole
    }

    /** Whether all that's left is the NAL's trailing bits: 0x80, then only zeros (a 0x80 can also start a message). */
    private fun atTrailingBits(data: ByteBuffer): Boolean {
        if (pos >= end) return true
        if (data.get(pos).toInt() and 0xFF != TRAILING_BITS) return false
        for (i in pos + 1 until end) if (data.get(i).toInt() != 0) return false
        return true
    }

    /** The next byte, or -1 past the NAL's end. */
    private fun read(data: ByteBuffer): Int {
        if (zeros >= 2 && pos < end && data.get(pos).toInt() == 3) {
            pos++
            zeros = 0
        }
        if (pos >= end) return -1
        val b = data.get(pos++).toInt() and 0xFF
        zeros = if (b == 0) zeros + 1 else 0
        return b
    }

    /** A value sent as 0xFF bytes plus a last byte, as SEI types and sizes are; -1 if cut short. */
    private fun extended(data: ByteBuffer): Int {
        var value = 0
        do {
            val b = read(data)
            if (b < 0) return -1
            value += b
        } while (b == 0xFF)
        return value
    }

    private companion object {
        private const val FIRST_NON_SLICE = 32
        private const val PREFIX_SEI = 39
        private const val UNSPECIFIED = 48
        private const val USER_DATA_REGISTERED = 4
        private const val TRAILING_BITS = 0x80

        /** HDR10+'s registered user data: the US (0xB5), provider 0x003C, oriented code 0x0001, application 4 (then a version). */
        private val HDR10_PLUS_ID = intArrayOf(0xB5, 0x00, 0x3C, 0x00, 0x01, 4)

        /**
         * The offset of the next NAL header (just after a 00 00 01 start code; a four-byte one ends the same way) at or
         * after [from], or -1. Skips three bytes at a time where no start code can end, as decoders do.
         */
        private fun nextNal(data: ByteBuffer, from: Int, end: Int): Int {
            var i = from
            while (i + 2 < end) {
                val third = data.get(i + 2).toInt() and 0xFF
                when {
                    third > 1 -> i += 3
                    third == 1 && data.get(i + 1).toInt() == 0 && data.get(i).toInt() == 0 -> return i + 3
                    else -> i++
                }
            }
            return -1
        }
    }
}
