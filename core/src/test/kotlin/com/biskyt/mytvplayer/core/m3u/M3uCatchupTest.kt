package com.biskyt.mytvplayer.core.m3u

import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class M3uCatchupTest {
    // 2024-05-01 18:00 UTC, watched until 20:30.
    private val start = ZonedDateTime.of(2024, 5, 1, 18, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
    private val now = start + 150 * 60_000L
    private val s = start / 1000
    private val n = now / 1000

    @Test
    fun `templates fill the usual placeholders`() {
        val end = n + 60
        assertEquals(
            "http://cdn/archive?start=$s&end=$end&now=$n&dur=${end - s}&min=${(end - s) / 60}&off=${n - s}",
            M3uCatchup.url(
                "http://cdn/live.m3u8",
                "default",
                "http://cdn/archive?start={utc}&end={utcend}&now={lutc}&dur={duration}&min={duration:60}&off={offset}",
                start,
                now
            )
        )
        assertEquals(
            "http://cdn/live.m3u8?begin=2024-05-01%2018:00:00&ts=$s",
            M3uCatchup.url("http://cdn/live.m3u8", "append", "?begin={utc:Y-m-d H:M:S}&ts=\${start}", start, now)
        )
        assertEquals("http://cdn/2024/05/01/18/00.ts", M3uCatchup.url("x", "default", "http://cdn/{Y}/{m}/{d}/{H}/{M}.ts", start, now))
    }

    @Test
    fun `shift, Flussonic and Xtream panel addresses`() {
        assertEquals(
            "http://cdn/live.m3u8?token=1&utc=$s&lutc=$n",
            M3uCatchup.url("http://cdn/live.m3u8?token=1", "shift", null, start, now)
        )
        val dur = n + 60 - s
        assertEquals(
            "http://fl/ch1/index-$s-$dur.m3u8?token=a",
            M3uCatchup.url("http://fl/ch1/index.m3u8?token=a", "flussonic", null, start, now)
        )
        assertEquals("http://fl/ch1/timeshift_abs-$s.ts", M3uCatchup.url("http://fl/ch1/mpegts", "flussonic", null, start, now))
        assertEquals(
            "http://panel:8080/timeshift/me/pw/151/2024-05-01:18-00/123.ts",
            M3uCatchup.url("http://panel:8080/live/me/pw/123.ts", "xc", null, start, now)
        )
        assertNull(M3uCatchup.url("http://cdn/live.m3u8", "xc", null, start, now))
    }

    @Test
    fun `which channels have catch-up that can play`() {
        assertEquals("shift", M3uCatchup.typeFor("SHIFT", null, "http://cdn/a.m3u8"))
        assertEquals("shift", M3uCatchup.typeFor("timeshift", null, "http://cdn/a.m3u8"))
        assertEquals("flussonic", M3uCatchup.typeFor("fs", null, "http://fl/ch1/index.m3u8"))
        assertNull(M3uCatchup.typeFor("flussonic", null, "http://fl/ch1/video.ts")) // not a Flussonic address
        assertNull(M3uCatchup.typeFor("default", "", "http://cdn/a.m3u8")) // no template
        assertNull(M3uCatchup.typeFor("vod", "x", "http://cdn/a.m3u8")) // not a type this knows
        assertNull(M3uCatchup.typeFor("xc", null, "http://cdn/a.m3u8")) // not a panel's address
        assertNull(M3uCatchup.typeFor(null, null, "http://panel/me/pw/123")) // days alone aren't enough
        assertEquals(7, M3uCatchup.daysFor(null))
        assertEquals(30, M3uCatchup.daysFor(9999))
    }

    @Test
    fun `append joins a query the address already has, and formatted times have no raw spaces`() {
        assertEquals(
            "http://cdn/live.m3u8?token=1&begin=2024-05-01%2018:00:00",
            M3uCatchup.url("http://cdn/live.m3u8?token=1", "append", "?begin={utc:Y-m-d H:M:S}", start, now)
        )
    }
}
