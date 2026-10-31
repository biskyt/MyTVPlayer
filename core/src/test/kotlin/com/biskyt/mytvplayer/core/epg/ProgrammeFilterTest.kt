package com.biskyt.mytvplayer.core.epg

import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.kxml2.io.KXmlParser

class ProgrammeFilterTest {
    private fun programme(channel: String, title: String, start: String = "20261018180000 +0000", quote: Char = '"') =
        "<programme start=$quote$start$quote channel=$quote$channel$quote><title>$title</title></programme>\n"

    /** Gives the bytes a few at a time, so tags and closing tags are cut across reads. */
    private class Trickle(private val bytes: ByteArray) : InputStream() {
        private var at = 0
        override fun read(): Int = if (at < bytes.size) bytes[at++].toInt() and 0xff else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (at >= bytes.size) return -1
            val n = minOf(len, 1 + at % 7, bytes.size - at)
            System.arraycopy(bytes, at, b, off, n)
            at += n
            return n
        }
    }

    private fun filtered(xml: String, keep: (String) -> Boolean, inWindow: (Long, Long) -> Boolean = { _, _ -> true }) =
        ProgrammeFilter(Trickle(xml.toByteArray()), keep, inWindow).readBytes().decodeToString()

    @Test
    fun `removes the programmes of channels not wanted and keeps the rest as it is`() {
        val kept = programme("a", "One") + programme("a", "Two", quote = '\'')
        val xml = "<tv>\n<channel id=\"a\"><display-name>A</display-name></channel>\n" +
            programme("b", "Gone") + kept + "<programme channel=\"b\" start=\"20261018180000\"/>\n" + programme("b", "Gone too") + "</tv>"
        assertEquals(
            "<tv>\n<channel id=\"a\"><display-name>A</display-name></channel>\n\n$kept\n\n</tv>",
            filtered(xml, keep = { it == "a" })
        )
    }

    @Test
    fun `keeps a programme whose channel it can't read, for the parser to decide`() {
        val xml = programme("b&amp;c", "Kept") + programme("é", "Also kept") + "<programmes/>" + "<programme\nchannel = 'b'>x</programme>"
        assertEquals(programme("b&amp;c", "Kept") + programme("é", "Also kept") + "<programmes/>", filtered(xml, keep = { false }))
    }

    @Test
    fun `removes programmes outside the window`() {
        val sixPm = XmltvParser.parseTime("20261018180000 +0000")!!
        val inHour = { start: Long, end: Long -> end > sixPm && start < sixPm + 3_600_000 }
        val xml = programme("a", "Before", start = "20261018170000 +0000") + programme("a", "In", start = "20261018180000 +0000") +
            programme("a", "After", start = "20261018190000 +0000")
        assertEquals("\n" + programme("a", "In") + "\n", filtered(xml, keep = { true }, inWindow = inHour))
        // A stop or a start it can't read as the parser would (an entity, a line break): kept for the parser.
        val unsure = "<programme channel=\"a\" start=\"20261018100000 +0000\" stop=\"20261018140000&#32;+0000\">x</programme>" +
            "<programme channel=\"a\" start=\"20261018170000\n+0000\">y</programme>"
        assertEquals(unsure, filtered(unsure, keep = { true }, inWindow = inHour))
    }

    @Test
    fun `passes comments and CDATA as they are`() {
        val xml = "<!-- <programme channel=\"b\" start=\"x\"> -->" +
            "<programme channel=\"a\"><desc><![CDATA[<programme channel=\"b\">]]></desc></programme>"
        assertEquals(xml, filtered(xml, keep = { it == "a" }))
    }

    @Test
    fun `reads the whole start tag and the right closing tag`() {
        val xml = "<programme hint=\"a>b\" channel=\"b\" note='/>'><x>1</x></programme>" +
            "<programme channel=\"b\"><programme-info>i</programme-info></programme>" + programme("a", "Kept")
        assertEquals(programme("a", "Kept"), filtered(xml, keep = { it == "a" }))
    }

    @Test
    fun `reads programmes longer than its buffer, a byte at a time too`() {
        val long = "x".repeat(300_000)
        val xml = "<tv>" + programme("b", long) + programme("a", long) + programme("b", long) + programme("a", "Short") + "</tv>"
        val expected = "<tv>\n" + programme("a", long) + "\n" + programme("a", "Short") + "</tv>" // removed: all but the line break
        assertEquals(
            expected,
            ProgrammeFilter(ByteArrayInputStream(xml.toByteArray()), {
                it == "a"
            }, { _, _ -> true }).readBytes().decodeToString()
        )
        val oneByOne = ProgrammeFilter(ByteArrayInputStream(xml.toByteArray()), { it == "a" }, { _, _ -> true })
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val b = oneByOne.read()
            if (b < 0) break
            out.write(b)
        }
        assertEquals(expected, out.toString(Charsets.UTF_8.name()))
    }

    @Test
    fun `the parser never reads a programme it doesn't want`() {
        // A bare & is not XML: the parser fails on it, so it must not see that programme.
        val xml = "<tv>" + programme("b", "Tom & Jerry") + programme("a", "Kept") + "</tv>"
        val titles = ArrayList<String>()
        runBlocking {
            XmltvParser({ KXmlParser() }).parse(ByteArrayInputStream(xml.toByteArray()), { it == "a" }, null) { titles += it.title }
        }
        assertEquals(listOf("Kept"), titles)
    }
}
