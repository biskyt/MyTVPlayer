package com.biskyt.mytvplayer.core

import java.util.IllformedLocaleException
import java.util.Locale

/** Language names and codes as streams and playlists write them, to ISO 639-1 codes. */
object Languages {
    /** ISO 639-2 codes streams tag tracks with, both forms ("fre" and "fra"). */
    private val iso3to2 = mapOf(
        "eng" to "en", "fra" to "fr", "fre" to "fr", "deu" to "de", "ger" to "de", "spa" to "es", "ita" to "it",
        "por" to "pt", "nld" to "nl", "dut" to "nl", "pol" to "pl", "tur" to "tr", "ara" to "ar", "hin" to "hi",
        "ell" to "el", "gre" to "el", "ron" to "ro", "rum" to "ro", "swe" to "sv", "nor" to "no", "dan" to "da",
        "fin" to "fi", "rus" to "ru", "zho" to "zh", "chi" to "zh", "kor" to "ko", "jpn" to "ja"
    )

    /** A track's language tag ("eng", "en-GB", "pt_BR") as its two-letter code, as written if not known. */
    fun primary(tag: String): String {
        val p = tag.lowercase().substringBefore('-').substringBefore('_')
        return iso3to2[p] ?: p
    }

    // Every language Java knows, by its English name, its own code and its 3-letter code.
    private val codes: Map<String, String> by lazy {
        Locale.getISOLanguages().flatMap { code ->
            val l = Locale(code)
            val iso3 = runCatching { l.isO3Language }.getOrNull()
            listOfNotNull(code to code, l.getDisplayLanguage(Locale.ENGLISH).lowercase() to code, iso3?.let { it to code })
        }.toMap() + iso3to2
    }

    /** The two-letter code for a language named in English ("Swedish") or by an ISO 639 code ("sv", "swe", "ger"). */
    fun code(language: String): String? = language.trim().lowercase().let { codes[it] }
}

/** The name of the country with the two-letter [code] (e.g. "GB") in [language]; else [code] in capitals (e.g. "4K"). */
fun countryName(code: String, language: Locale): String = try {
    Locale.Builder().setRegion(code).build().getDisplayCountry(language)
} catch (_: IllformedLocaleException) {
    ""
}.ifEmpty { code.uppercase() }
