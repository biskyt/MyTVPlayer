package com.biskyt.mytvplayer.core.epg

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.kxml2.io.KXmlParser

class XmltvParserTest {
    private val xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE tv SYSTEM "xmltv.dtd">
        <tv generator-info-name="test">
          <channel id="arena1.uk">
            <display-name>Arena Sports 1</display-name>
            <display-name lang="en">Arena Sports 1 HD</display-name>
            <icon src="http://logo/arena1.png"/>
          </channel>
          <channel id="kite.uk"><display-name>Kite Kids</display-name></channel>
          <programme start="20261018173000 +0100" stop="20261018194500 +0100" channel="arena1.uk">
            <title lang="en">Premier Football Live</title>
            <sub-title>Matchday 9</sub-title>
            <desc>Build-up &amp; the full match.</desc>
            <category>Sport</category>
            <rating system="UK"><value>U</value></rating>
          </programme>
          <programme start="20261018194500 +0100" stop="20261018210000 +0100" channel="arena1.uk">
            <title>Match of the Week</title>
          </programme>
          <programme start="20261018180000 +0100" stop="20261018183000 +0100" channel="kite.uk">
            <title>Paper Planes Club</title>
          </programme>
          <programme start="bad" channel="arena1.uk"><title>Broken</title></programme>
          <programme start="20261018210000" channel="arena1.uk"><title></title></programme>
        </tv>
    """.trimIndent()

    private fun parse(
        bytes: ByteArray,
        want: (String) -> Boolean = { true },
        windowStart: Long = Long.MIN_VALUE,
        windowEnd: Long = Long.MAX_VALUE
    ): Pair<List<XmltvChannel>, List<XmltvProgramme>> {
        val channels = mutableListOf<XmltvChannel>()
        val programmes = mutableListOf<XmltvProgramme>()
        runBlocking {
            XmltvParser({ KXmlParser() }, windowStart, windowEnd).parse(ByteArrayInputStream(bytes), want, { channels += it }, {
                programmes +=
                    it
            })
        }
        return channels to programmes
    }

    @Test
    fun `reads channels and programmes`() {
        val (channels, programmes) = parse(xml.toByteArray())
        assertEquals(listOf("arena1.uk", "kite.uk"), channels.map { it.id })
        assertEquals(listOf("Arena Sports 1", "Arena Sports 1 HD"), channels[0].names)
        assertEquals("http://logo/arena1.png", channels[0].iconUrl)
        assertNull(channels[1].iconUrl)

        assertEquals(4, programmes.size)
        val first = programmes[0]
        assertEquals("Premier Football Live", first.title)
        assertEquals("Matchday 9", first.subtitle)
        assertEquals("Build-up & the full match.", first.description)
        assertEquals(XmltvParser.parseTime("20261018163000 +0000"), first.startMs)
        // Missing title and stop time fall back to defaults.
        assertEquals("", programmes[3].title) // none: the app names it when shown, in its language
        assertEquals(30 * 60_000L, programmes[3].stopMs - programmes[3].startMs)
    }

    @Test
    fun `skips programmes for unwanted channels and outside the window`() {
        val (_, onlyKite) = parse(xml.toByteArray(), want = { it == "kite.uk" })
        assertEquals(listOf("Paper Planes Club"), onlyKite.map { it.title })

        val cutoff = XmltvParser.parseTime("20261018194500 +0100")!!
        val (_, early) = parse(xml.toByteArray(), windowEnd = cutoff)
        assertEquals(listOf("Premier Football Live", "Paper Planes Club"), early.map { it.title })
    }

    @Test
    fun `reading just the channels stops at the programmes`() {
        val channels = mutableListOf<String>()
        // Truncated after the first programme starts: a channel-only read never gets that far.
        val cut = xml.substring(0, xml.indexOf("<title lang"))
        runBlocking { XmltvParser({ KXmlParser() }).parseChannels(ByteArrayInputStream(cut.toByteArray())) { channels += it.id } }
        assertEquals(listOf("arena1.uk", "kite.uk"), channels)
    }

    @Test
    fun `reads gzip input`() {
        val gz = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(xml.toByteArray()) } }.toByteArray()
        val channels = mutableListOf<XmltvChannel>()
        runBlocking { XmltvParser({ KXmlParser() }).parse(GZIPInputStream(ByteArrayInputStream(gz)), { false }, { channels += it }, { }) }
        assertEquals(2, channels.size)
    }

    @Test
    fun `parses times with and without offsets`() {
        assertEquals(1_792_342_800_000L, XmltvParser.parseTime("20261018180000 +0100"))
        assertEquals(1_792_346_400_000L, XmltvParser.parseTime("20261018180000"))
        assertEquals(1_792_364_400_000L, XmltvParser.parseTime("20261018180000 -0500"))
        assertEquals(951_782_400_000L, XmltvParser.parseTime("20000229000000 +0000")) // leap day
        assertEquals(1_792_347_300_000L, XmltvParser.parseTime("20261018180000 -0015"))
        assertNull(XmltvParser.parseTime("2026"))
        assertNull(XmltvParser.parseTime("20261318180000"))
        assertNull(XmltvParser.parseTime(null))
    }

    @Test
    fun `reads offsets written with a colon and times without seconds`() {
        val sixPmUk = 1_792_342_800_000L
        assertEquals(sixPmUk, XmltvParser.parseTime("20261018180000 +01:00"))
        assertEquals(sixPmUk, XmltvParser.parseTime("202610181800 +0100"))
        assertEquals(sixPmUk + 3_600_000, XmltvParser.parseTime("202610181800"))
        assertEquals(sixPmUk, XmltvParser.parseTime("20261018180000+0100"))
        assertNull(XmltvParser.parseTime("2026101818"))
    }

    @Test
    fun `a programme ending before it starts gets the usual length`() {
        val guide = """<tv><programme start="20261018180000 +0000" stop="20261018170000 +0000" channel="a">
            <title>Backwards</title></programme></tv>"""
        val p = parse(guide.toByteArray()).second.single()
        assertEquals(30 * 60_000L, p.stopMs - p.startMs)
    }

    @Test
    fun `the latest end is of the wanted channels' programmes, outside the window too`() {
        // The window is one hour on the 18th. arena1's last programme starts at 21:00 UTC, after it, and has no stop
        // time, so it ends a default length later.
        val start = XmltvParser.parseTime("20261018180000 +0100")!!
        val parser = XmltvParser({ KXmlParser() }, start, start + 3_600_000L)
        runBlocking { parser.parse(ByteArrayInputStream(xml.toByteArray()), { it == "arena1.uk" }, null) {} }
        assertEquals(XmltvParser.endOf(XmltvParser.parseTime("20261018210000")!!, null), parser.latestEndMs)

        // Kite Kids alone: its one programme ends at 18:30; arena1's later ones are not counted.
        val kite = XmltvParser({ KXmlParser() })
        runBlocking { kite.parse(ByteArrayInputStream(xml.toByteArray()), { it == "kite.uk" }, null) {} }
        assertEquals(XmltvParser.parseTime("20261018183000 +0100"), kite.latestEndMs)

        // No wanted channel: nothing known.
        val none = XmltvParser({ KXmlParser() })
        runBlocking { none.parse(ByteArrayInputStream(xml.toByteArray()), { false }, null) {} }
        assertNull(none.latestEndMs)
    }
}
