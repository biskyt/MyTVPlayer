package com.biskyt.mytvplayer.core.epg

import com.biskyt.mytvplayer.core.Languages
import java.util.Locale

/**
 * Guesses which language/country group a provider category belongs to from its name, e.g.
 * `UK | SPORTS`, `FR: CINEMA`, `|EN| NEWS`, `DE - KIDS`, `[AR] MBC`. Xtream has no language field,
 * so this is a best guess the user can override per category. Returns a group code such as `UK`, `FR`, or null.
 */
object CategoryLanguageDetector {
    /**
     * The group codes with a label of their own (the app names them, e.g. UK → "English (UK)"). Codes are what
     * providers put in front of category names.
     */
    val groups: Set<String> = linkedSetOf(
        "UK", "US", "CA", "AU", "IE", "FR", "DE", "ES", "IT", "PT", "BR", "NL", "BE", "PL", "TR", "AR", "IN", "PK", "GR",
        "RO", "AL", "EXYU", "SE", "NO", "DK", "FI", "RU", "LAT", "MX", "AF", "CN", "KR", "JP"
    )

    private val aliases = mapOf(
        "GB" to "UK", "EN" to "UK", "ENG" to "UK", "USA" to "US", "FRA" to "FR", "GER" to "DE", "ESP" to "ES",
        "SP" to "ES", "ITA" to "IT", "POR" to "PT", "NLD" to "NL", "HOL" to "NL", "TUR" to "TR", "ARA" to "AR",
        "ARAB" to "AR", "IND" to "IN", "HINDI" to "IN", "YU" to "EXYU", "SWE" to "SE", "NOR" to "NO", "DEN" to "DK",
        "LATINO" to "LAT", "LATAM" to "LAT", "AFR" to "AF", "AFRICA" to "AF"
    )

    // A short code at the start, wrapped or followed by a separator: "UK |", "|UK|", "[UK]", "UK:", "UK -", "UK/".
    private val leading = Regex("""^\W*([A-Za-z]{2,6})\s*[|:\-\]/)]""")

    private val nonLetters = Regex("[^\\p{L}]+")

    /**
     * " WORD WORD ": upper-case letters only, without accents, "&" as "and", single spaces, padded so whole words can
     * be found with contains(). So "Curaçao" and "Bosnia & Herzegovina" match iptv-org's "Curacao" and "... and ...".
     */
    private fun padded(text: String) =
        " " + ChannelNames.stripAccents(text).replace("&", " and ").uppercase().replace(nonLetters, " ").trim() + " "

    /** The group for an ISO country code: "UK" for Great Britain, as providers write it. */
    fun groupForCountry(iso: String): String = iso.uppercase().let { if (it == "GB") "UK" else it }

    // Whole words anywhere in the name: every country's English name (iptv-org groups channels by country), a few
    // alternative names, and language adjectives. Longest first, so the most specific name wins.
    private val words: List<Pair<String, String>> = (
        Locale.getISOCountries().flatMap { iso ->
            // Both the full name and the everyday one: "Myanmar (Burma)" → "Myanmar", "Hong Kong SAR China" → "Hong Kong".
            val name = Locale("", iso).getDisplayCountry(Locale.ENGLISH)
            val short = name.substringBefore(" (").substringBefore(" - ").removeSuffix(" SAR China")
            val code = groupForCountry(iso)
            listOf(name to code, short to code)
        } +
            listOf(
                "Turkey" to "TR", "Turkiye" to "TR", "Czech Republic" to "CZ", "Russia" to "RU",
                // iptv-org's names, where they differ from Java's.
                "Republic of the Congo" to "CG", "Democratic Republic of the Congo" to "CD", "English" to "UK", "French" to "FR",
                "German" to "DE", "Spanish" to "ES", "Italian" to "IT", "Dutch" to "NL", "Polish" to "PL",
                "Turkish" to "TR", "Arabic" to "AR", "Greek" to "GR",
            )
        ).map { (name, code) -> padded(name) to code }.filter { it.first.isNotBlank() }.sortedByDescending { it.first.length }

    /** Whether [code] is a language/country code providers put in front of names ("EN", "UK", "FRA"…). */
    fun isLanguageCode(code: String): Boolean = code.uppercase().let { it in aliases || it in groups }

    /** The category's group: [ADULT] for adult sections, whatever their language, else its language or country. */
    fun detect(categoryName: String): String? = ADULT.takeIf { adult.containsMatchIn(categoryName) } ?: detectIn(categoryName, more = true)

    /**
     * [detect] as it was up to 1.8.14. M3U category ids are made with it (see M3uCategories), so a name it newly
     * detects keeps its id, and with it its choices, favourites and progress.
     */
    fun detectAsBefore(categoryName: String): String? = detectIn(categoryName, more = false)

    /** The rules up to 1.8.14 (codes, then words), then with [more] those since (codes, then words). */
    private fun detectIn(categoryName: String, more: Boolean): String? {
        val code = leading.find(categoryName)?.groupValues?.get(1)?.uppercase()
        val name by lazy(LazyThreadSafetyMode.NONE) { padded(categoryName) }
        return code?.let { aliases[it] ?: it.takeIf { c -> c in groups } }
            ?: words.firstOrNull { name.contains(it.first) }?.second
            ?: if (!more) {
                null
            } else {
                code?.let { moreAliases[it] ?: it.takeIf { c -> c in moreCountries } }
                    ?: moreWords.firstOrNull { name.contains(it.first) }?.second
            }
    }

    /** Groups for "Global", "Nordic" and adult sections, named by the app as the others in [groups] are. */
    const val GLOBAL = "GLOBAL"
    const val NORDIC = "NORDIC"

    /** Adult sections ("18: FOR ADULTS", "XXX MOVIES"): never chosen by default (see CategoryStore). */
    const val ADULT = "ADULT"

    /** Every group the app names itself (others are country codes, named by their country). */
    val namedGroups: Set<String> = groups + GLOBAL + NORDIC + ADULT

    // Whole words in the app's languages (not "Adult Swim", "Adult Animation" or "Young Adult": cartoons and books),
    // "18+" or "+18" (not in "2018+"), or an "18" section code ("18: ...").
    private val adult = Regex(
        """(?i)(?<!young )\b(adults?|adultos|adultes|adulti|adulto|erwachsene|xxx|porn)\b(?! ?(swim|animation))""" +
            """|\b18\s*\+|\+\s*18\b|^\W*18\s*[|:\-\]/)]"""
    )

    /**
     * Since 1.8.15: more of the codes providers put in front of names. Only codes with one meaning in IPTV sections:
     * not "IR" (Ireland and Iran), "IS" (Israel and Iceland), "LV" (Latin and Latvia), "HR" (horse racing), "MU"
     * (music) or "SK" (Slovakia and South Korea) or "KO" (Kosovo and Korean). A country code is named by its country (see groupLabel).
     */
    private val moreAliases = mapOf(
        "GLOBAL" to GLOBAL, "MULTI" to GLOBAL, "INT" to GLOBAL, "SCAN" to NORDIC, "CAN" to "CA", "QC" to "CA", "ISR" to "IL",
        "BH" to "EXYU", "BIH" to "EXYU", "MK" to "EXYU", "SRB" to "EXYU", "LA" to "LAT",
    )
    private val moreCountries = setOf("NZ", "AT", "CH", "CZ", "CY", "HU", "ID", "PH", "HK", "IL")

    // Since 1.8.15: languages named anywhere in the name ("AS: TAMIL", "IR: PERSIAN MOVIES").
    private val moreWords: List<Pair<String, String>> = listOf(
        "Hebrew" to "IL", "Persian" to "IR", "Farsi" to "IR", "Tamil" to "IN", "Telugu" to "IN", "Telegu" to "IN",
        "Malayalam" to "IN", "Punjabi" to "IN", "Kannada" to "IN", "Bollywood" to "IN", "Bengali" to "IN",
        "Nordic" to NORDIC, "Scandinavian" to NORDIC, "African" to "AF",
    ).map { (name, code) -> padded(name) to code }

    /**
     * Category groups that share a language, the first being where it's mostly watched (for defaults, and below). Only
     * add to the end of a list, and no new languages: [groupFor] gives M3U category ids, which must stay the same.
     */
    val languageGroups: Map<String, List<String>> = mapOf(
        "en" to listOf("UK", "US", "CA", "AU", "IE"), "fr" to listOf("FR", "BE"), "de" to listOf("DE", "AT", "CH"),
        "es" to listOf("ES", "MX", "LAT"), "it" to listOf("IT"), "pt" to listOf("PT", "BR"), "nl" to listOf("NL", "BE"),
        "pl" to listOf("PL"), "tr" to listOf("TR"), "ar" to listOf("AR"), "el" to listOf("GR"), "ro" to listOf("RO"),
        "sv" to listOf("SE", NORDIC), "nb" to listOf("NO", NORDIC), "no" to listOf("NO", NORDIC), "da" to listOf("DK", NORDIC),
        "fi" to listOf("FI", NORDIC),
        "ru" to listOf("RU"), "hi" to listOf("IN"), "sq" to listOf("AL"), "zh" to listOf("CN"), "ko" to listOf("KR"), "ja" to listOf("JP"),
    )

    private val isoCountries = Locale.getISOCountries().toHashSet()

    /**
     * The group for a playlist entry's `tvg-country` (an ISO code, or one providers use: "UK", "USA", "LAT"…), or else
     * its `tvg-language` (an English name or an ISO 639 code): the country's own group, or the language's main one
     * ("English" → UK); for other languages, the language's English name. Null when neither says anything usable.
     */
    fun groupFor(country: String?, language: String?): String? {
        val c = country?.uppercase()?.let { aliases[it] ?: it }
        if (c != null && (c in groups || c in isoCountries)) return groupForCountry(c)
        val code = language?.let(Languages::code) ?: return null
        return languageGroups[code]?.first() ?: Locale(code).getDisplayLanguage(Locale.ENGLISH).ifEmpty { null }
    }
}
