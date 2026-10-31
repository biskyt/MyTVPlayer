package com.biskyt.mytvplayer.core.epg

import java.io.InputStream
import org.xmlpull.v1.XmlPullParser

data class XmltvChannel(val id: String, val names: List<String>, val iconUrl: String?)

data class XmltvProgramme(
    val channelId: String,
    val startMs: Long,
    val stopMs: Long,
    val title: String,
    val subtitle: String?,
    val description: String?,
    /** A [ProgrammeKind] constant. */
    val kind: Int = ProgrammeKind.UNKNOWN,
    val season: Int? = null,
    val episode: Int? = null
)

/**
 * Streaming XMLTV reader. Every `<channel>` can be reported (they are small and needed for matching), but
 * `<programme>` elements are only built when [wantProgrammes] accepts the channel id and the programme
 * overlaps [windowStartMs]..[windowEndMs]. A byte scan ([ProgrammeFilter]) removes the other programmes before the
 * parser reads them.
 *
 * The caller supplies the [XmlPullParser] so Android can use the framework parser and tests can use kxml2.
 */
class XmltvParser(
    private val newParser: () -> XmlPullParser,
    private val windowStartMs: Long = Long.MIN_VALUE,
    private val windowEndMs: Long = Long.MAX_VALUE
) {
    /**
     * Blocking read; call from an IO dispatcher. Callbacks may suspend (e.g. to write a batch to the database).
     * Without [onChannel], channels are skipped unread.
     */
    suspend fun parse(
        input: InputStream,
        wantProgrammes: (channelId: String) -> Boolean,
        onChannel: (suspend (XmltvChannel) -> Unit)?,
        onProgramme: suspend (XmltvProgramme) -> Unit
    ) = read(input, wantProgrammes, onChannel, onProgramme, channelsOnly = false)

    /**
     * Reads just the channels. XMLTV lists every `<channel>` before the programmes, so this stops at the first
     * programme after a channel: most of a big guide (its programmes) isn't even decompressed.
     */
    suspend fun parseChannels(input: InputStream, onChannel: suspend (XmltvChannel) -> Unit) =
        read(input, { false }, onChannel, {}, channelsOnly = true)

    private suspend fun read(
        input: InputStream,
        wantProgrammes: (channelId: String) -> Boolean,
        onChannel: (suspend (XmltvChannel) -> Unit)?,
        onProgramme: suspend (XmltvProgramme) -> Unit,
        channelsOnly: Boolean
    ) {
        val p = newParser()
        // Programmes not wanted, or outside the window, are removed before the parser reads them: most of a big guide.
        p.setInput(if (channelsOnly) input else ProgrammeFilter(input, wantProgrammes, ::inWindow), null)
        var seenChannel = false
        while (true) {
            when (p.next()) {
                XmlPullParser.END_DOCUMENT -> return
                XmlPullParser.START_TAG -> when (p.name) {
                    "channel" -> {
                        seenChannel = true
                        if (onChannel == null) p.skipElement() else readChannel(p)?.let { onChannel(it) }
                    }
                    "programme" -> when {
                        !channelsOnly -> programme(p, wantProgrammes, onProgramme)
                        seenChannel -> return
                        else -> p.skipElement()
                    }
                }
            }
        }
    }

    private suspend fun programme(p: XmlPullParser, wantProgrammes: (String) -> Boolean, onProgramme: suspend (XmltvProgramme) -> Unit) {
        // Check the channel before parsing times: most programmes in a big guide are skipped.
        val id = p.getAttributeValue(null, "channel")
        val start = if (id != null && wantProgrammes(id)) parseTime(p.getAttributeValue(null, "start")) else null
        if (id == null || start == null) return p.skipElement()
        val end = endOf(start, parseTime(p.getAttributeValue(null, "stop")))
        if (inWindow(start, end)) onProgramme(readProgramme(p, id, start, end)) else p.skipElement()
    }

    /** Whether a programme from [startMs] to [endMs] (see [endOf]) is in the window. */
    private fun inWindow(startMs: Long, endMs: Long) = endMs > windowStartMs && startMs < windowEndMs

    private fun readChannel(p: XmlPullParser): XmltvChannel? {
        val id = p.getAttributeValue(null, "id")
        val names = ArrayList<String>(2)
        var icon: String? = null
        forEachChild(p) { tag ->
            when (tag) {
                "display-name" -> p.nextText().trim().takeIf { it.isNotEmpty() }?.let(names::add)
                "icon" -> {
                    icon = p.getAttributeValue(null, "src")
                    p.skipElement()
                }
                else -> p.skipElement()
            }
        }
        return id?.let { XmltvChannel(it, names, icon) }
    }

    private fun readProgramme(p: XmlPullParser, channelId: String, start: Long, stop: Long): XmltvProgramme {
        var title: String? = null
        var sub: String? = null
        var desc: String? = null
        var kind = ProgrammeKind.UNKNOWN
        var packedEpisode: Int? = null
        forEachChild(p) { tag ->
            when (tag) {
                "title" -> p.nextText().trim().let { if (title == null) title = it }
                "sub-title" -> p.nextText().trim().let { if (sub == null) sub = it }
                "desc" -> p.nextText().trim().let { if (desc == null) desc = it }
                "category" -> kind = ProgrammeKind.fold(kind, p.nextText())
                "episode-num" -> {
                    val system = p.getAttributeValue(null, "system")
                    val value = p.nextText()
                    if (packedEpisode == null) packedEpisode = ProgrammeKind.parseEpisode(system, value)
                }
                else -> p.skipElement()
            }
        }
        val ep = packedEpisode
        return XmltvProgramme(
            channelId, start, stop, title.orEmpty(), sub?.ifEmpty { null }, desc?.ifEmpty { null },
            ProgrammeKind.finish(kind, ep != null), ep?.let(ProgrammeKind::season), ep?.let(ProgrammeKind::episode)
        )
    }

    /** Calls [onChild] for each direct child start tag; [onChild] must consume the child element. */
    private inline fun forEachChild(p: XmlPullParser, onChild: (String) -> Unit) {
        val depth = p.depth
        while (true) {
            val e = p.next()
            if (e == XmlPullParser.END_DOCUMENT || (e == XmlPullParser.END_TAG && p.depth == depth)) return
            if (e == XmlPullParser.START_TAG) onChild(p.name)
        }
    }

    private fun XmlPullParser.skipElement() {
        var depth = 1
        while (depth > 0) {
            when (next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    companion object {
        private const val DEFAULT_LENGTH_MS = 30 * 60_000L

        /** A programme's end. A stop at or before the start is a mistake in the guide: taken as no stop. */
        internal fun endOf(startMs: Long, stopMs: Long?) = stopMs?.takeIf { it > startMs } ?: (startMs + DEFAULT_LENGTH_MS)

        /**
         * Parses XMLTV times such as `20261018180000 +0100`, also without seconds or with the offset written `+01:00`;
         * a missing offset means UTC. Hand-rolled digit parsing:
         * this runs for every programme kept from guides with hundreds of thousands of entries.
         */
        @Suppress("CyclomaticComplexMethod", "ComplexCondition") // flat, allocation-free on purpose
        fun parseTime(value: String?): Long? {
            val v = value ?: return null
            var i = 0
            while (i < v.length && v[i] == ' ') i++
            if (v.length - i < 12) return null
            fun num(from: Int, len: Int): Int {
                var n = 0
                for (k in from until from + len) {
                    val d = v[k] - '0'
                    if (d !in 0..9) return -1
                    n = n * 10 + d
                }
                return n
            }
            val year = num(i, 4)
            val month = num(i + 4, 2)
            val day = num(i + 6, 2)
            val hour = num(i + 8, 2)
            val minute = num(i + 10, 2)
            // Seconds can be left out.
            val hasSeconds = v.length - i >= 14 && v[i + 12] in '0'..'9'
            val second = if (hasSeconds) num(i + 12, 2) else 0
            if (year < 0 || month !in 1..12 || day !in 1..31 || hour !in 0..23 || minute !in 0..59 || second !in 0..60) return null
            var offsetSec = 0
            var j = i + if (hasSeconds) 14 else 12
            while (j < v.length && v[j] == ' ') j++
            if (v.length - j >= 5 && (v[j] == '+' || v[j] == '-')) {
                val hh = num(j + 1, 2)
                val mm = if (v[j + 3] == ':' && v.length - j >= 6) num(j + 4, 2) else num(j + 3, 2)
                if (hh >= 0 && mm >= 0) offsetSec = (hh * 3600 + mm * 60) * if (v[j] == '-') -1 else 1
            }
            return (daysFromCivil(year, month, day) * 86_400L + hour * 3600 + minute * 60 + second - offsetSec) * 1000
        }

        /** Days since 1970-01-01 for a proleptic Gregorian date (Howard Hinnant's algorithm). */
        private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
            val y = if (month <= 2) year - 1 else year
            val era = (if (y >= 0) y else y - 399) / 400
            val yoe = y - era * 400
            val doy = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
            val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
            return era * 146_097L + doe - 719_468
        }
    }
}
