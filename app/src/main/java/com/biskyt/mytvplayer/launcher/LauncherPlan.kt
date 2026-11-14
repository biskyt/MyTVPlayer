package com.biskyt.mytvplayer.launcher

/** A card the launcher has from the app now: its row's id, the app's key, and whether the user took it off. */
internal data class StoredCard(val id: Long, val key: String, val removed: Boolean, val watchedAt: Long)

/** What to change in the launcher so that it shows [Plan.insert] and [Plan.update], and no longer [Plan.delete]. */
internal data class Plan<T>(val insert: List<T>, val update: List<Pair<Long, T>>, val delete: List<Long>)

/**
 * From the cards the launcher has ([stored]) to the ones wanted now ([wanted], each with its key and when it was watched).
 * A card the user took off the launcher stays off (its row is kept, so the launcher knows) until it is watched again;
 * then it comes back. A key stored twice keeps one row.
 */
internal fun <T> plan(stored: List<StoredCard>, wanted: List<T>, key: (T) -> String, watchedAt: (T) -> Long = { 0L }): Plan<T> {
    val byKey = stored.groupBy { it.key }
    val wantedKeys = wanted.map(key).toSet()
    val delete = stored.filter { it.key !in wantedKeys }.map { it.id }.toMutableList()
    val insert = mutableListOf<T>()
    val update = mutableListOf<Pair<Long, T>>()
    for (card in wanted) {
        val rows = byKey[key(card)].orEmpty()
        delete += rows.drop(1).map { it.id }
        val row = rows.firstOrNull()
        when {
            row == null -> insert += card
            !row.removed -> update += row.id to card
            watchedAt(card) > row.watchedAt -> {
                delete += row.id
                insert += card
            }
            // Taken off by the user, and not watched since: left as it is.
        }
    }
    return Plan(insert, update, delete)
}
