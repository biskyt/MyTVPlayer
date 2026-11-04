package com.biskyt.mytvplayer.data

import com.biskyt.mytvplayer.AppText
import com.biskyt.mytvplayer.core.countryName
import com.biskyt.mytvplayer.data.db.EpgSourceEntity
import java.text.Collator
import java.util.Locale

/** A free XMLTV guide for one country. */
data class FreeGuide(val code: String, val country: String, val url: String) {
    val sourceName: String get() = "$country (iptv-epg.org)"
}

/**
 * Free country guides from iptv-epg.org, all named `epg-<country code>.xml.gz`. The list is fixed here rather than
 * read from their site, so it works without scraping a page that may change; new countries need an app update.
 */
object FreeGuides {
    private const val BASE = "https://iptv-epg.org/files/epg-"

    private val codes = (
        "al am ar at au ba be bg bo br bs by ca ch cl co cr cw cz de dk do ec es fi fr gb ge gh gr gt hk hn hr id il in is " +
            "it jm jp kr lt lu lv me mt mx my ng ni nl no nz pa pe pl pt py rs ru se sg sv th tr tt tw ua ug us uy ve za zw"
        ).split(' ')

    /** What a list was made for: the TV's locale (whose country goes first) and the language of the country names. */
    private data class Key(val locale: Locale, val names: Locale)

    private var cached: Pair<Key, List<FreeGuide>>? = null

    /**
     * Every country, the TV's own ([locale]'s) first, then by name in [names]' language (kept: the country names take a
     * while to look up).
     */
    fun all(locale: Locale = Locale.getDefault(), names: Locale = AppText.locale): List<FreeGuide> {
        val key = Key(locale, names)
        return cached?.takeIf { it.first == key }?.second ?: build(key).also { cached = key to it }
    }

    private fun build(key: Key): List<FreeGuide> {
        val own = key.locale.country.lowercase()
        return codes.map {
            FreeGuide(it, countryName(it, key.names), "$BASE$it.xml.gz")
        }
            .sortedWith(compareBy<FreeGuide> { it.code != own }.thenBy(Collator.getInstance(key.names)) { it.country })
    }

    /** Every free guide not among [sources] yet, the TV's own country first. */
    fun notAdded(sources: List<EpgSourceEntity>): List<FreeGuide> {
        val added = sources.mapTo(HashSet()) { it.url }
        return all().filter { it.url !in added }
    }
}
