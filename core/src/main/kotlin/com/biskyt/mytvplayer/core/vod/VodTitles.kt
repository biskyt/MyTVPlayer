package com.biskyt.mytvplayer.core.vod

import com.biskyt.mytvplayer.core.epg.CategoryLanguageDetector

/** A movie or series name as people would write it, and its year when the provider's name includes one. */
data class CleanTitle(val title: String, val year: Int?)

/**
 * Providers decorate names: "EN - The Matrix (1999)", "|UK| Line of Duty", "[4K] Dune [2021]". This strips a leading
 * language/quality tag and pulls out a trailing year, so lists read cleanly and titles can be matched later.
 */
object VodTitles {
    // A short tag at the start: "EN - ", "EN: ", "|EN| ", "[EN] ", "(EN) ".
    private val tag = Regex("""^\s*(?:[|\[(]\s*([A-Za-z0-9+]{2,6})\s*[|\])]\s*[-:|]?|([A-Za-z0-9+]{2,6})\s*(?:-|:|\|)\s)\s*""")
    private val qualityTags = setOf("4K", "UHD", "FHD", "HD", "SD", "HEVC", "MULTI", "VOD", "NF", "AMZ", "DSNP", "3D")

    // A year at the end: "(1999)", "[1999]" or "- 1999". A bare number is left alone ("Blade Runner 2049").
    private val year = Regex("""\s*(?:[(\[]((?:19|20)\d{2})[)\]]|-\s*((?:19|20)\d{2}))\s*$""")

    /** [t] without its leading language or quality tag, or null if it has none. */
    private fun withoutTag(t: String): String? {
        val m = tag.find(t) ?: return null
        val bracketed = m.groupValues[1].isNotEmpty()
        val code = (m.groupValues[1].ifEmpty { m.groupValues[2] }).uppercase()
        // Unbracketed codes that are also words ("IT: Chapter Two") are left alone.
        val known = code in qualityTags || (CategoryLanguageDetector.isLanguageCode(code) && (bracketed || code !in wordCodes))
        return if (known) t.substring(m.range.last + 1) else null
    }

    /** A few providers stack tags ("|EN| [4K] Title"): up to two are removed. */
    private fun withoutTags(t: String) = withoutTag(t)?.let { withoutTag(it) ?: it } ?: t

    private val wordCodes = setOf("IT", "IN", "NO", "AL", "AR", "AF", "US")
    private val nonWord = Regex("""[^\p{L}\p{N}]+""")

    /**
     * A title reduced for comparing a TV programme with a provider's movie or series: no tags or year, lower case,
     * no punctuation and no leading "the" ("The Simpsons (1989)", "Simpsons" → "simpsons").
     */
    fun key(title: String): String {
        // Apostrophes are dropped rather than split on, so "Grey's" and "Greys" agree.
        val words = clean(title).title.lowercase().replace("'", "").replace("\u2019", "").split(nonWord).filter { it.isNotEmpty() }
        return (if (words.size > 1 && words[0] == "the") words.drop(1) else words).joinToString(" ")
    }

    private val word = Regex("""[\p{L}\p{N}]+""")
    private val yearWord = Regex("""(?:19|20)\d{2}""")
    private val spaces = Regex("""\s+""")
    private val bracketed = Regex("""[(\[][^)\]]*[)\]]""")

    // Where an episode's name is split into parts: " - ", " | ", a dash, "_" or ",". Not ":" or "." on their own,
    // which are often in the name itself ("Mystery Box Challenge: Dim Sim Lim").
    private val partSeparator = Regex("""\s+[-\u2013\u2014]+\s+|\s*\|\s*|[_,]""")
    private const val TRIMMED = " -|_,:\u2013\u2014"

    /**
     * An episode's name without what the page shows already, whatever their order: "EN: Masterchef USA (2010) - S16E01
     * - Auditions Day 1" or "Auditions Day 1 - S16E01 - Masterchef USA" of the series "Masterchef USA" is "Auditions Day
     * 1". It drops a language or quality tag at the start, the episode code (see [EpisodeNames]), and the parts that are
     * the series' name, a year or a release tag. With a name on both sides of the code, the one after it is kept, as most
     * providers write it. Null when nothing is left. For display only, of every provider's episodes.
     */
    fun episodeName(title: String, series: String?): String? {
        val t = withoutTags(title.trim())
        // Commas and underscores count as separators around the code too (the same length, so the places stay).
        val code = EpisodeNames.codeIn(t.replace(',', ' ').replace('_', ' '))
        val sides = if (code == null) listOf(t) else listOf(t.substring(code.last + 1), t.substring(0, code.first))
        val seriesWords = series?.let(::nameWords).orEmpty()
        // A part that is only the start of the series' name goes too, unless that leaves nothing ("Doctor Who" of
        // "Doctor Who Confidential").
        return sides.firstNotNullOfOrNull { nameIn(it, seriesWords, short = true) }
            ?: sides.firstNotNullOfOrNull { nameIn(it, seriesWords, short = false) }
    }

    /**
     * What is left of [side] without its parts that are the series ([seriesWords]; with [short], the start of its name
     * too), a year, a release tag or a language code.
     */
    private fun nameIn(side: String, seriesWords: List<String>, short: Boolean): String? {
        val s = side.trimStart(')', ']').trimEnd('(', '[')
        val parts = buildList {
            var start = 0
            partSeparator.findAll(s).forEach {
                add(start until it.range.first)
                start = it.range.last + 1
            }
            add(start until s.length)
        }.map { r -> withoutSeriesPrefix(s, r, seriesWords, short) }
        val kept = parts.indices.filter { keep(s.substring(parts[it]), seriesWords, short) }
        if (kept.isEmpty()) return null
        // Next to each other: as written, commas and all. Else joined.
        val name = if (kept.zipWithNext().all { (a, b) -> b == a + 1 }) {
            s.substring(parts[kept.first()].first, parts[kept.last()].last + 1)
        } else {
            kept.joinToString(" - ") { s.substring(parts[it]).trim() }
        }
        return name.replace(spaces, " ").trim { it in TRIMMED }.ifEmpty { null }
    }

    /** Part [r] of [s] without the series' name and a colon at its start ("Masterchef USA: Auditions"). */
    private fun withoutSeriesPrefix(s: String, r: IntRange, seriesWords: List<String>, short: Boolean): IntRange {
        val colon = s.indexOf(':', r.first).takeIf { it in r } ?: return r
        return if (seriesWords.isNotEmpty() &&
            isSeries(nameWords(s.substring(r.first, colon)), seriesWords, short)
        ) {
            colon + 1..r.last
        } else {
            r
        }
    }

    private fun keep(part: String, seriesWords: List<String>, short: Boolean): Boolean {
        val p = part.trim { it in TRIMMED }
        if (p.isEmpty() || EpisodeNames.isReleaseTag(p) || isTagCode(p)) return false
        val words = nameWords(p)
        // Only years and brackets ("(2010)", "2015-2022", "(GB)") say nothing.
        return words.isNotEmpty() && !isSeries(words, seriesWords, short)
    }

    /** A language or quality code on its own ("EN", "4K"), as at the start of a name; not one that is also a word. */
    private fun isTagCode(p: String): Boolean {
        val code = p.uppercase()
        return code in qualityTags || (p.length <= 3 && code !in wordCodes && CategoryLanguageDetector.isLanguageCode(code))
    }

    /**
     * Whether [words] name the series: all its words, or with [short] the first two or more of them, as providers
     * shorten names ("MasterChef USA" of "MasterChef USA Gordon Ramsay").
     */
    private fun isSeries(words: List<String>, seriesWords: List<String>, short: Boolean) = words == seriesWords ||
        (short && words.size >= 2 && words.size < seriesWords.size && seriesWords.subList(0, words.size) == words)

    /** The words of a name, lower case, without years or what is in brackets ("(GB)", "[1080p]"). */
    private fun nameWords(name: String): List<String> =
        word.findAll(name.replace(bracketed, " ")).map { it.value.lowercase() }.filterNot { yearWord.matches(it) }.toList()

    fun clean(name: String): CleanTitle {
        var t = withoutTags(name.trim())
        val y = year.find(t)
        val yearValue = y?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.toInt()
        if (y != null && y.range.first > 0) t = t.substring(0, y.range.first)
        return CleanTitle(t.trim().trimEnd('-', ':', '|').trim().ifEmpty { name.trim() }, yearValue)
    }
}
