package com.biskyt.mytvplayer.data

import androidx.room.withTransaction
import com.biskyt.mytvplayer.core.epg.CategoryLanguageDetector
import com.biskyt.mytvplayer.core.xtream.XtreamCategory
import com.biskyt.mytvplayer.data.db.AppDatabase
import com.biskyt.mytvplayer.data.db.CAT_LIVE
import com.biskyt.mytvplayer.data.db.CategoryEntity
import com.biskyt.mytvplayer.data.db.MAX_SQL_ARGS
import java.util.Locale

/**
 * Provider categories of every type (live, movie, series): which are included, and which items to download because
 * of that. New categories are included when their language/country group is one the user already includes, or, the
 * first time, the TV's own country/language.
 */
class CategoryStore(private val db: AppDatabase, private val locale: () -> Locale = Locale::getDefault) {
    private val categories = db.categories()

    /**
     * A category's language/country group. [groupByName]: M3U playlists also group by genre ("News"); when no
     * language is detected the group's own name is used, so each still appears in the language list.
     */
    fun langOf(c: XtreamCategory, groupByName: Boolean) = detectedGroup(c) ?: genreGroup(c, groupByName)

    // Two-letter names ("TV", "SD") would read as country codes, so those go under "Other".
    private fun genreGroup(c: XtreamCategory, groupByName: Boolean) = c.name.takeIf { groupByName && it.length > 2 }

    /**
     * As its name says, else from its entries' tvg-country/tvg-language (see M3uCategories). (A name the rules up to
     * 1.8.14 knew has no tvg group in its id: only a name known since, such as "GLOBAL: Movies", can have both.)
     */
    private fun detectedGroup(c: XtreamCategory) = CategoryLanguageDetector.detect(c.name) ?: M3uCategories.groupOf(c.id)

    /**
     * Saves the provider's categories of one [type], keeping the user's choices for ones already known.
     *
     * Movie and series categories are often named without a language ("Action", "Netflix Movies"); those are taken to
     * be in the provider's main language and included too. A movie or series list the user unticked entirely is off:
     * new categories in it stay unticked too.
     */
    suspend fun store(playlistId: Long, type: String, remote: List<XtreamCategory>, groupByName: Boolean = false) {
        val vod = type != CAT_LIVE
        val existing = categories.all(playlistId, type).associateBy { it.categoryId }
        val switchedOff = vod && existing.isNotEmpty() && existing.values.none { it.included }
        val detected = remote.map(::detectedGroup)
        val langs = remote.mapIndexed { i, c -> detected[i] ?: genreGroup(c, groupByName) }
        val isNew: (Int) -> Boolean = if (existing.isEmpty()) {
            firstChoice(langs, detected)
        } else {
            val includes = includesNew(existing.values, groupByName)
            fun(i: Int) = includes(remote[i])
        }
        val remoteIds = remote.mapTo(HashSet()) { it.id }
        val items = remote.mapIndexed { i, c ->
            val old = existing[c.id] ?: M3uCategories.inherited(existing, c.id)
            CategoryEntity(
                playlistId = playlistId,
                categoryId = c.id,
                name = c.name,
                lang = langs[i],
                included = included(old, langs[i], !switchedOff && (isNew(i) || (vod && langs[i] == null))),
                // An inherited place only once the old category has gone, so the two don't share it.
                sortOrder = old?.takeIf { it.categoryId == c.id || it.categoryId !in remoteIds }?.sortOrder ?: (existing.size + i),
                channelCount = old?.channelCount ?: 0,
                type = type
            )
        }
        // Those the provider no longer has go.
        val gone = if (remote.isEmpty()) emptyList() else existing.keys.filter { it !in remoteIds }
        db.withTransaction {
            categories.upsert(items)
            gone.chunked(MAX_SQL_ARGS).forEach { categories.delete(playlistId, type, it) }
        }
    }

    /**
     * Groups the user includes: a new category in one of these is included automatically. Worked out from the names
     * rather than the stored groups, so it still matches after an update improves language detection.
     */
    private fun includedLangs(existing: Collection<CategoryEntity>, groupByName: Boolean) =
        existing.filter { it.included }.mapNotNullTo(HashSet()) { langOf(XtreamCategory(it.categoryId, it.name), groupByName) }

    /**
     * Whether a category not seen before is included, given the [known] ones: when it's in a group the user includes,
     * or names no country while every known one that names none is included (as everything is to start with in a
     * playlist of genres, so a genre added later is too).
     */
    fun includesNew(known: Collection<CategoryEntity>, groupByName: Boolean): (XtreamCategory) -> Boolean {
        val wanted = includedLangs(known, groupByName)
        val undetected = known.filter { detectedGroup(XtreamCategory(it.categoryId, it.name)) == null }
        val allUndetected = undetected.isNotEmpty() && undetected.all { it.included }
        return { c ->
            val detected = detectedGroup(c)
            (detected ?: genreGroup(c, groupByName)) in wanted || (allUndetected && detected == null)
        }
    }

    /**
     * Whether a category is included: as chosen before ([old]), or else [byDefault]. Adult sections are never included by
     * default; one that only now counts as adult (from 1.8.18) is left out once, as it may have been included by
     * default before.
     */
    private fun included(old: CategoryEntity?, lang: String?, byDefault: Boolean): Boolean {
        val adult = lang == CategoryLanguageDetector.ADULT
        return old?.included?.takeUnless { adult && old.lang != lang } ?: (byDefault && !adult)
    }

    /**
     * Which of a new playlist's categories (by their [langs] and [detected] groups) are chosen to start with: the
     * default groups, and "Global" sections (in no one language). When nothing matches the TV's country or language
     * (e.g. a playlist of genres, "Live: News", with no countries at all), what names no country, so there's
     * something to watch.
     */
    private fun firstChoice(langs: List<String?>, detected: List<String?>): (Int) -> Boolean {
        val defaults = defaultGroups(langs.filterNotNullTo(HashSet()))
        return { i -> langs[i] in defaults || langs[i] == CategoryLanguageDetector.GLOBAL || (defaults.isEmpty() && detected[i] == null) }
    }

    /**
     * Language groups included by default: the TV's own country (e.g. en-GB → UK) when the provider has it,
     * otherwise every group of the TV's language (e.g. en-US with a UK-only provider → UK). The user can change this.
     */
    fun defaultGroups(available: Set<String>): Set<String> {
        val l = locale()
        val country = CategoryLanguageDetector.groupForCountry(l.country)
        if (country in available) return setOf(country)
        return CategoryLanguageDetector.languageGroups[l.language].orEmpty().intersect(available)
    }

    /**
     * Reads the items of included categories of [type]: with just a few included, one request per category;
     * otherwise the whole list once, dropping the rest as it streams in.
     */
    suspend fun <T> forIncluded(
        playlistId: Long,
        type: String,
        categoryOf: (T) -> String,
        fetch: suspend (categoryId: String?, emit: suspend (T) -> Unit) -> Unit,
        onItem: suspend (T) -> Unit
    ) {
        val all = categories.all(playlistId, type)
        val included = all.filter { it.included }.mapTo(HashSet()) { it.categoryId }
        if (included.isEmpty()) return
        // Items are checked in both cases: some panels ignore the category asked for and send everything.
        val keep: suspend (T) -> Unit = { item -> if (categoryOf(item) in included) onItem(item) }
        if (included.size <= PER_CATEGORY_LIMIT && included.size * 2 < all.size) {
            for (cat in included) fetch(cat, keep)
        } else {
            fetch(null, keep)
        }
    }

    private companion object {
        const val PER_CATEGORY_LIMIT = 20
    }
}

/** Writes a long list in batches as it streams in, so it never sits in memory whole. [count] is the total added. */
class Batch<E>(private val size: Int, private val write: suspend (List<E>) -> Unit, private val onFlush: (Int) -> Unit = {}) {
    private val items = ArrayList<E>(size)
    var count = 0
        private set

    suspend fun add(item: E) {
        items += item
        count++
        if (items.size >= size) flush()
    }

    suspend fun flush() {
        if (items.isEmpty()) return
        write(items.toList())
        items.clear()
        onFlush(count)
    }
}
