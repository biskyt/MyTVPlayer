package com.biskyt.mytvplayer.data

import com.biskyt.mytvplayer.core.epg.CategoryLanguageDetector
import com.biskyt.mytvplayer.core.m3u.M3uEntry
import com.biskyt.mytvplayer.core.xtream.XtreamCategory
import java.util.concurrent.ConcurrentHashMap

/**
 * M3U categories: an entry's group-title, filed under the group its tvg-country or tvg-language gives when the name
 * itself names none, so "News" splits by country while "UK | News" stays as it is. The group follows a separator no
 * playlist uses, so the ids differ by group while the names stay the playlist's own. Entries with no group-title go in
 * one with no name (the app names it, in its language: see categoryLabel), whose id no playlist can use.
 */
object M3uCategories {
    private const val TAG = '\u001F'
    private const val UNGROUPED = "$TAG"

    /** What versions before 1.1.0 called (and keyed) the category of entries with no group-title. */
    private const val OLD_UNGROUPED = "Other"

    private data class Key(val name: String, val country: String?, val language: String?)

    // A playlist has a few hundred combinations at most: each is worked out once, and its id reused.
    private val ids = ConcurrentHashMap<Key, String>()

    /** The id of [e]'s category. */
    fun idOf(e: M3uEntry): String {
        val name = e.group.orEmpty()
        if (e.country == null && e.language == null) return name.ifEmpty { UNGROUPED }
        return ids.getOrPut(Key(name, e.country, e.language)) {
            val group = CategoryLanguageDetector.groupFor(e.country, e.language)
            (if (group == null || CategoryLanguageDetector.detectAsBefore(name) != null) name else "$name$TAG$group").ifEmpty { UNGROUPED }
        }
    }

    /** The group an id was filed under, or null. */
    fun groupOf(id: String): String? = id.substringAfter(TAG, "").ifEmpty { null }

    /**
     * The id category [id] had before 1.1.0, when entries with no group-title were in "Other" (together with any group
     * the playlist itself called "Other"); null for a category whose id didn't change. Its choices (shown or hidden,
     * order) carry over, and series ids are still made from it, keeping their favourites and progress.
     */
    fun oldId(id: String): String? = when {
        !id.startsWith(TAG) -> null
        id == UNGROUPED -> OLD_UNGROUPED
        else -> OLD_UNGROUPED + id
    }

    /** What [saved] (by category id) holds under [id]'s [oldId], for a category with nothing saved under its own. */
    fun <V> inherited(saved: Map<String, V>, id: String): V? = oldId(id)?.let(saved::get)

    /** A category from its id, named as the playlist names it. */
    fun category(id: String) = XtreamCategory(id, id.substringBefore(TAG))
}
