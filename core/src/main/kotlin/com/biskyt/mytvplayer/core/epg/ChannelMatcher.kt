package com.biskyt.mytvplayer.core.epg

import java.util.PriorityQueue
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/** A channel from an EPG source that playlist channels can be matched to. */
data class EpgCandidate(val sourceId: Long, val xmlId: String, val displayName: String, val normalized: String)

data class EpgMatch(val candidate: EpgCandidate, val score: Double)

/**
 * Suggests EPG channels for playlist channels. Built once from all candidates, then queried per channel.
 *
 * Matching runs for thousands of channels against tens of thousands of guide channels on a slow CPU, so: candidate
 * tokens are computed once here, shared tokens are counted while walking an inverted index, very common words are
 * ignored when rarer ones exist, and the string-distance check only runs on the best few candidates.
 */
class ChannelMatcher(candidates: List<EpgCandidate>) {
    /** [index]: the candidate's place in the list, which decides between equal ranks. */
    private class Entry(val index: Int, val candidate: EpgCandidate, val tokens: Set<String>) {
        val shifts = tokens.shifts()
        val numbers = tokens.numbers()
        var weight = 0.0
    }

    // Sorted, so that the order (which decides between equal candidates) does not depend on the order of the input.
    private val entries = candidates.sortedWith(compareBy({ it.sourceId }, { it.xmlId }, { it.displayName }))
        .mapIndexed { i, c -> Entry(i, c, ChannelNames.tokens(c.normalized)) }

    /** One [Shared] kept for the next name; a second caller at the same time uses its own. */
    private val spare = AtomicReference<Shared?>()
    private val byXmlId = entries.groupBy { it.candidate.xmlId.lowercase() }
    private val byNormalized = entries.groupBy { it.candidate.normalized }
    private val byToken = HashMap<String, MutableList<Entry>>().also { index ->
        for (e in entries) for (t in e.tokens) index.getOrPut(t) { ArrayList() }.add(e)
    }
    private val commonTokenSize = max(COMMON_TOKEN_MIN, entries.size / COMMON_TOKEN_DIVISOR)

    /** Rare words say more: a call sign found in one guide channel outweighs "tv" found in thousands. */
    private val weights = HashMap<String, Double>(byToken.size * 2).also { w ->
        for ((t, bucket) in byToken) w[t] = ln(1.0 + entries.size.toDouble() / bucket.size)
    }
    private val unseenWeight = ln(1.0 + entries.size)

    init {
        // Set once the index exists (weights depend on it).
        for (e in entries) e.weight = e.tokens.sumOf { weights.getValue(it) }
    }

    /** A US or Canadian station's call sign ("wnbc", "kabc", "cfto"): it names one station, wherever it is in the name. */
    private fun isCallSign(token: String) = callSign.matches(token) &&
        token.drop(1).count { it in "aeiou" } <= 1 &&
        token !in NOT_CALL_SIGNS &&
        (byToken[token]?.size ?: 0) <= CALL_SIGN_MAX_GUIDES

    /** Best automatic match, or null when nothing reaches [threshold]. */
    fun autoMatch(channelName: String, providerEpgId: String?, threshold: Double = AUTO_THRESHOLD): EpgMatch? =
        // A candidate below the threshold cannot be the match, so the search leaves it out early.
        suggestions(channelName, providerEpgId, limit = 1, floor = max(threshold, MIN_SUGGESTION)).firstOrNull()
            ?.takeIf { it.score >= threshold }

    fun suggestions(channelName: String, providerEpgId: String?, limit: Int = 5): List<EpgMatch> =
        suggestions(channelName, providerEpgId, limit, MIN_SUGGESTION)

    /** The best [limit] candidates that score at least [floor]. */
    private fun suggestions(channelName: String, providerEpgId: String?, limit: Int, floor: Double): List<EpgMatch> {
        val results = LinkedHashMap<EpgCandidate, Double>()
        providerEpgId?.lowercase()?.let { id -> byXmlId[id]?.forEach { results[it.candidate] = 1.0 } }

        val norm = ChannelNames.normalize(channelName)
        if (norm.isNotEmpty()) {
            byNormalized[norm]?.forEach { results.putIfAbsent(it.candidate, EXACT_NAME_SCORE) }
            fuzzy(norm, results, floor)
        }
        return results.entries.sortedByDescending { it.value }.take(limit).map { EpgMatch(it.key, it.value) }
    }

    /** A playlist channel's name, worked out once per lookup. */
    private inner class Query(val norm: String) {
        val tokens = ChannelNames.tokens(norm)
        val weight = tokens.sumOf { weights[it] ?: unseenWeight }
        val shifts = tokens.shifts()
        val numbers = tokens.numbers()
        val callSigns = tokens.filterTo(HashSet(), ::isCallSign)
    }

    private fun fuzzy(norm: String, results: MutableMap<EpgCandidate, Double>, floor: Double) {
        val q = Query(norm)
        // Words like "tv" or "sport" appear in thousands of guide channels; only use them if nothing rarer matches.
        val buckets = q.tokens.mapNotNull { t -> byToken[t]?.let { t to it } }
        val useful = buckets.filter { it.second.size <= commonTokenSize }.ifEmpty { buckets }
        val shared = spare.getAndSet(null) ?: Shared(entries.size)
        try {
            for ((t, bucket) in useful) {
                val w = weights.getValue(t)
                for (e in bucket) shared.add(e.index, w)
            }
            // Rank by the weight of the rarer shared words, then score the best few on all their words.
            scoreBest(q, best(shared, q.weight), results, floor)
        } finally {
            shared.clear()
            spare.compareAndSet(null, shared)
        }
    }

    private fun scoreBest(q: Query, best: List<Entry>, results: MutableMap<EpgCandidate, Double>, floor: Double) {
        for (e in best) {
            if (e.candidate in results) continue
            val score = score(q, e, floor)
            if (score >= floor) results[e.candidate] = score
        }
    }

    /**
     * The [FUZZY_CANDIDATES] entries sharing the most (by weight, relative to both names' weight), best first. Kept in
     * a small heap rather than sorting every entry: a common word can be shared by thousands.
     */
    private fun best(shared: Shared, queryWeight: Double): List<Entry> {
        val heap = PriorityQueue(FUZZY_CANDIDATES + 1, byRank)
        for (k in 0 until shared.count) {
            val i = shared.touched[k]
            val e = entries[i]
            val rank = shared.weight[i] / (queryWeight + e.weight)
            if (heap.size < FUZZY_CANDIDATES) {
                heap += Ranked(e, rank)
                continue
            }
            // The same order as byRank, without an object for the many entries that rank lower.
            val top = heap.peek()
            if (rank > top.rank || (rank == top.rank && e.index < top.entry.index)) {
                heap.poll()
                heap += Ranked(e, rank)
            }
        }
        return heap.sortedWith(byRank.reversed()).map { it.entry }
    }

    private class Ranked(val entry: Entry, val rank: Double)

    /**
     * The weight of the words each entry shares with one channel name, by entry index, and which entries have any.
     * Arrays reused for every name: a map for each name was a third of the matching time.
     */
    private class Shared(size: Int) {
        val weight = DoubleArray(size)
        val touched = IntArray(size)
        var count = 0

        fun add(index: Int, w: Double) {
            if (weight[index] == 0.0) touched[count++] = index // word weights are always more than 0
            weight[index] += w
        }

        fun clear() {
            for (k in 0 until count) weight[touched[k]] = 0.0
            count = 0
        }
    }

    /** Unboxed: compared a few times per shared entry, for every channel matched. */
    private val byRank = Comparator<Ranked> { a, b ->
        // Equal ranks: the earlier candidate counts as better, so every run gives the same matches.
        val byValue = a.rank.compareTo(b.rank)
        if (byValue != 0) byValue else b.entry.index.compareTo(a.entry.index)
    }

    /** The score of [e] for [q]. If the score cannot reach [floor], it is [NO_SCORE], and the slow name comparison is skipped. */
    private fun score(q: Query, e: Entry, floor: Double): Double {
        var common = 0.0
        var sharesCallSign = false
        var sharedWords = 0
        for (t in e.tokens) {
            if (t !in q.tokens) continue
            common += weights.getValue(t)
            sharedWords++
            if (t in q.callSigns) sharesCallSign = true
        }
        // "Sports 1" is not "Sports 10"; a number on one side only ("NBC 4" vs "NBC") is a smaller doubt.
        val numbersAgree = q.numbers == e.numbers
        val oneSided = q.numbers.isEmpty() || e.numbers.isEmpty()
        // A shared call sign, with something else in common (the network, the city), is the same station whatever the
        // virtual channel number; "+1" is still an hour later.
        val sameStation = sharesCallSign && sharedWords >= 2 && q.shifts == e.shifts && (numbersAgree || oneSided)
        val words = 0.6 * (2 * common / (q.weight + e.weight)) // a val: the local function then does not box it
        fun final(names: Double): Double {
            var score = words + 0.4 * names
            if (q.shifts != e.shifts) score *= 0.5
            if (!numbersAgree && !sameStation) score *= if (oneSided) 0.85 else 0.6
            return if (sameStation) max(score, CALL_SIGN_SCORE) else score
        }
        // The name similarity is at most 1.0. If the score cannot reach the floor with 1.0, do not compare the names.
        return if (final(1.0) < floor) NO_SCORE else final(jaroWinkler(q.norm, e.candidate.normalized))
    }

    companion object {
        /** Matching after a guide or playlist is first loaded. */
        const val AUTO_THRESHOLD = 0.85

        /** "Match channels without a guide": only near-certain matches. */
        const val MISSING_THRESHOLD = 0.90
        private const val EXACT_NAME_SCORE = 0.97
        private const val CALL_SIGN_SCORE = 0.96
        private const val CALL_SIGN_MAX_GUIDES = 3
        private const val MIN_SUGGESTION = 0.4
        private const val NO_SCORE = -1.0
        private const val FUZZY_CANDIDATES = 40
        private const val COMMON_TOKEN_MIN = 200
        private const val COMMON_TOKEN_DIVISOR = 20

        private val callSign = Regex("[kw][a-z]{2,3}|c[bfhijk][a-z]{2}")

        /** Words shaped like call signs (at most one vowel after the K/W/C, e.g. "wnbc", "kabc", "wttg"). */
        private val NOT_CALL_SIGNS = setOf(
            "kids", "kino", "kiss", "king", "kick", "keep", "know", "west", "wild", "word", "work", "wine", "wave", "wiki",
            "wwe", "web", "war", "way", "wow", "win", "wok", "key", "kix", "wind", "wing", "wise", "wolf", "wood", "wars",
            "chef", "chic", "city", "chat", "cine", "cbbc"
        )

        private fun Set<String>.shifts() = filterTo(HashSet()) { it.startsWith("plus") }
        private fun Set<String>.numbers() = filterTo(HashSet()) { t -> !t.startsWith("plus") && t.any(Char::isDigit) }

        fun jaroWinkler(s1: String, s2: String): Double {
            if (s1 == s2) return 1.0
            if (s1.isEmpty() || s2.isEmpty()) return 0.0
            val range = max(0, max(s1.length, s2.length) / 2 - 1)
            val m1 = BooleanArray(s1.length)
            val m2 = BooleanArray(s2.length)
            var matches = 0
            for (i in s1.indices) {
                for (j in max(0, i - range) until min(s2.length, i + range + 1)) {
                    if (!m2[j] && s1[i] == s2[j]) {
                        m1[i] = true
                        m2[j] = true
                        matches++
                        break
                    }
                }
            }
            if (matches == 0) return 0.0
            var t = 0
            var k = 0
            for (i in s1.indices) {
                if (!m1[i]) continue
                while (!m2[k]) k++
                if (s1[i] != s2[k]) t++
                k++
            }
            val m = matches.toDouble()
            val jaro = (m / s1.length + m / s2.length + (m - t / 2.0) / m) / 3
            var prefix = 0
            while (prefix < min(4, min(s1.length, s2.length)) && s1[prefix] == s2[prefix]) prefix++
            return jaro + prefix * 0.1 * (1 - jaro)
        }
    }
}
