package com.biskyt.mytvplayer.core.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Section names from a real provider, filed under their language or country group. */
class CategoryLanguageDetectorTest {
    @Test
    fun `sections in no one language are Global, Scandinavian ones Nordic`() {
        assertEquals("GLOBAL", CategoryLanguageDetector.detect("GLOBAL: [24/7 O-D] DISNEY+   [1080p]"))
        assertEquals("GLOBAL", CategoryLanguageDetector.detect("MULTI: NETFLIX SERIES [4K-Q]"))
        assertEquals("NORDIC", CategoryLanguageDetector.detect("SCAN: NORDIC FILMS [4K-Q]"))
    }

    @Test
    fun `more country codes and languages are known`() {
        val cases = mapOf(
            "CAN: LOCALS [1080p]" to "CA", "QC: FILMS [1080p]" to "CA", "NZ: SKY SPORT  [1080p]" to "NZ",
            "CZ: NOVA [1080p]" to "CZ", "AT: JOYN [1080p]" to "AT", "CH: DAZN [PPV EVENTS] [1080p]" to "CH",
            "HU: SOROZAT [1080p]" to "HU", "PH: DRAMA [1080p]" to "PH", "ID: VIDIO [1080p]" to "ID",
            "CY: ΑΘΛΗΤΙΣΜΟΣ/SPORTS [1080p]" to "CY", "HK: HONGKONG SPORTS [1080p]" to "HK", "ISR: HEBREW [1080p]" to "IL",
            "IS: HEBREW SDAROT [24/7 O-D] [1080p]" to "IL", "BH: BOSNIA [1080p]" to "EXYU", "MK: MACEDONIA [1080p]" to "EXYU",
            "AFRICAN: GENERAL [1080p]" to "AF", "LA: LATINO SERIES [1080p]" to "LAT",
            "IR: PERSIAN MOVIES [1080p]" to "IR", "AS: [24/7 O-D] TAMIL [1080p]" to "IN", "AS: [24/7 O-D] BOLLYWOOD [1080p]" to "IN",
        )
        cases.forEach { (name, group) -> assertEquals(name, group, CategoryLanguageDetector.detect(name)) }
    }

    @Test
    fun `codes with two meanings, and genres, stay unknown`() {
        // IR is Ireland and Iran, IS Israel and Iceland, LV Latin and Latvia, KO Kosovo and Korean; HR, MU and SPT are
        // horse racing, music and sport.
        listOf(
            "IR: LOI [PPV EVENTS] [1080p]",
            "IS: YES BOXES [1080p]",
            "LV: VIX [1080p]",
            "HR: HORSE RACING [1080p]",
            "MU: MUSIC CONCERTS [1080p]",
            "SPT: SPORT UFC [1080p]",
            "18: FOR ADULTS [1080p]",
            "KO: KBS [1080p]",
        ).forEach { assertNull(it, CategoryLanguageDetector.detect(it)) }
    }

    @Test
    fun `M3U category ids keep being made the old way`() {
        // A name only the new rules know stays unknown to the old ones, so its M3U id (and its choices) don't change.
        assertNull(CategoryLanguageDetector.detectAsBefore("GLOBAL: MOVIES"))
        assertNull(CategoryLanguageDetector.detectAsBefore("Tamil Movies"))
        assertEquals("UK", CategoryLanguageDetector.detectAsBefore("UK | NEWS"))
    }
}
