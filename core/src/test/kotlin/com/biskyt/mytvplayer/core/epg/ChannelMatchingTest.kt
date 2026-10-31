package com.biskyt.mytvplayer.core.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelMatchingTest {

    @Test
    fun `normalize strips provider noise`() {
        val cases = mapOf(
            "UK: ARENA SPRTS 1 FHD *RAW*" to "arena sprts 1",
            "UK| MOTOR TV ᴴᴰ" to "motor tv",
            "UK - COOK&CO" to "cook and co",
            "|EN| Doc Planet +1" to "doc planet plus1",
            "[UK] Kite Kids (Backup)" to "kite kids",
            "Channel One HD" to "channel one",
            "Télé Évasion 4K" to "tele evasion",
            "Arena Sports 1 HEVC 50fps" to "arena sports 1",
            "US: NBC 4 (WNBC) NEW YORK (A)" to "nbc 4 wnbc ny",
            "HBO (W)" to "hbo west",
            "US| FOX 5 WTTG Washington DC [Geo-Blocked]" to "fox 5 wttg wa dc",
            "West Virginia News" to "wv news",
            "Sky Sports Main Event (Backup 2)" to "sky sports main event",
            "Geo News (Geo-blocked)" to "geo news",
            "E! (E)" to "e east",
            // A dash after a name and before its number isn't a country code's.
            "HBO-2" to "hbo 2",
            "ITV-1 HD" to "itv 1",
            "UK-BBC One" to "bbc one",
            "UK - 5 Star" to "5 star",
            "US -FOX 5" to "fox 5"
        )
        cases.forEach { (input, expected) -> assertEquals(input, expected, ChannelNames.normalize(input)) }
    }

    private fun candidate(xmlId: String, name: String, source: Long = 1) = EpgCandidate(source, xmlId, name, ChannelNames.normalize(name))

    private val candidates = listOf(
        candidate("arena1.uk", "Arena Sports 1 HD"),
        candidate("arena10.uk", "Arena Sports 10"),
        candidate("arena2.uk", "Arena Sports 2"),
        candidate("docplanet.uk", "Doc Planet"),
        candidate("docplanet1.uk", "Doc Planet +1"),
        candidate("kite.uk", "Kite Kids"),
        candidate("one.uk", "Channel One")
    )
    private val matcher = ChannelMatcher(candidates)

    @Test
    fun `provider epg id wins`() {
        val m = matcher.autoMatch("something unrelated", "KITE.uk")!!
        assertEquals("kite.uk", m.candidate.xmlId)
        assertEquals(1.0, m.score, 0.0)
    }

    @Test
    fun `exact normalized name auto-matches`() {
        assertEquals("arena1.uk", matcher.autoMatch("UK: ARENA SPORTS 1 FHD", null)!!.candidate.xmlId)
        assertEquals("docplanet1.uk", matcher.autoMatch("UK| DOC PLANET+1", null)!!.candidate.xmlId)
        assertEquals(
            "one.uk",
            matcher.autoMatch("UK| CH ONE HD", null)?.candidate?.xmlId ?: matcher.suggestions("UK| CH ONE HD", null).first().candidate.xmlId
        )
    }

    @Test
    fun `numbers must agree`() {
        val best = matcher.suggestions("Arena Sports 1", null)
        assertEquals("arena1.uk", best.first().candidate.xmlId)
        val ten = best.firstOrNull { it.candidate.xmlId == "arena10.uk" }
        assertTrue(ten == null || ten.score < ChannelMatcher.AUTO_THRESHOLD)
    }

    @Test
    fun `typos give suggestions but not automatic matches when unsure`() {
        val suggestions = matcher.suggestions("UK: ARENA SPRTS 1 FHD *RAW*", null)
        assertEquals("arena1.uk", suggestions.first().candidate.xmlId)
        assertNull(matcher.autoMatch("Totally Different", null))
    }

    @Test
    fun `US local stations match on their call sign wherever it is in the name`() {
        val us = ChannelMatcher(
            candidates + listOf(
                candidate("wnbc.us", "US - NBC WNBC NY"),
                candidate("knbc.us", "US - NBC KNBC LA"),
                candidate("cnbc.us", "US - CNBC"),
                candidate("wildlife.us", "Wildlife Explorer"),
                candidate("nbcny.us", "NBC New York News")
            )
        )
        val m = us.autoMatch("US: NBC 4 (WNBC) NEW YORK (A)", null, ChannelMatcher.MISSING_THRESHOLD)
        assertEquals("wnbc.us", m?.candidate?.xmlId)
        assertEquals("wnbc.us", us.autoMatch("US| WNBC NBC 4 New York HD", null, ChannelMatcher.MISSING_THRESHOLD)?.candidate?.xmlId)
        // A state written out matches its abbreviation.
        assertEquals("nbcny.us", us.autoMatch("NBC NY News", null)?.candidate?.xmlId)
        // A rare word that isn't a call sign doesn't get the same benefit of the doubt, nor does a lone shared one.
        assertNull(us.autoMatch("Wildlife TV", null, ChannelMatcher.MISSING_THRESHOLD))
        val kite = ChannelMatcher(listOf(candidate("kitekids.uk", "Kite Kids"), candidate("kbsworld.kr", "KBS World")))
        assertNull(kite.autoMatch("Kite Movies", null))
        assertNull(kite.autoMatch("KBS Drama", null))
    }

    @Test
    fun `timeshifted channels never match the live one automatically`() {
        val noShift = ChannelMatcher(candidates.filter { it.xmlId != "docplanet1.uk" })
        assertNull(noShift.autoMatch("UK: DOC PLANET +1", null))
    }

    @Test
    fun `jaro winkler basics`() {
        assertEquals(1.0, ChannelMatcher.jaroWinkler("abc", "abc"), 0.0)
        assertEquals(0.0, ChannelMatcher.jaroWinkler("", "abc"), 0.0)
        assertTrue(ChannelMatcher.jaroWinkler("martha", "marhta") > 0.95)
    }

    @Test
    fun `language detector reads common prefixes`() {
        val cases = mapOf(
            "UK | SPORTS" to "UK", "UK| NEWS" to "UK", "UK - KIDS" to "UK", "|EN| ENTERTAINMENT" to "UK",
            "FR: CINEMA" to "FR", "[DE] Kinder" to "DE", "AR | MBC" to "AR", "USA: NBA" to "US", "EX-YU | Sport" to null,
            "EXYU | Sport" to "EXYU", "FRENCH MOVIES" to "FR", "Movies 24/7" to null, "United Kingdom" to "UK", "United States" to "US",
            "NEWS" to null, "North Korea" to "KP", "South Korea" to "KR", "Austria" to "AT", "Bosnia & Herzegovina" to "BA",
            "Indian Movies" to null, "India" to "IN", "Turkiye" to "TR", "Bosnia and Herzegovina" to "BA", "Curacao" to "CW",
            "Trinidad and Tobago" to "TT", "Republic of the Congo" to "CG", "Democratic Republic of the Congo" to "CD"
        )
        cases.forEach { (input, expected) -> assertEquals(input, expected, CategoryLanguageDetector.detect(input)) }
        // Codes with a label of their own, which the app names (its group label test checks every one); other
        // countries are named by their ISO code.
        assertTrue(listOf("UK", "EXYU", "LAT").all { it in CategoryLanguageDetector.groups })
        assertTrue("KP" !in CategoryLanguageDetector.groups)
    }

    @Test
    fun `equal candidates are chosen in a fixed order, whatever the order given`() {
        // 60 guide channels that share "kite kids" equally: only 40 are scored, and they must be the first 40 by id.
        val many = (0 until 60).map { candidate("kite%02d".format(it), "Kite Kids Z${'a' + it % 26}${'a' + it / 26}") }
        fun ids(list: List<EpgCandidate>) = ChannelMatcher(list).suggestions("Kite Kids", null, limit = 60).map { it.candidate.xmlId }
        val chosen = ids(many)
        assertEquals((0 until 40).map { "kite%02d".format(it) }, chosen.sorted())
        assertEquals(chosen, ids(many.reversed()))
    }

    @Test
    fun `an automatic match is the best suggestion that reaches the threshold`() {
        val names = listOf("UK: ARENA SPRTS 1 FHD", "Kite Kids", "Doc Planet +1", "NBC 4 (WNBC) New York", "Nothing Like It", "Arena")
        for (name in names) {
            for (threshold in listOf(0.3, ChannelMatcher.AUTO_THRESHOLD, ChannelMatcher.MISSING_THRESHOLD)) {
                val best = matcher.suggestions(name, null, limit = 1).firstOrNull()?.takeIf { it.score >= threshold }
                assertEquals("$name at $threshold", best, matcher.autoMatch(name, null, threshold))
            }
        }
    }
}
