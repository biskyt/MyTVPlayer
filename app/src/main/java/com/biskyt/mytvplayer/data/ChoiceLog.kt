package com.biskyt.mytvplayer.data

import com.biskyt.mytvplayer.data.db.AppDatabase
import com.biskyt.mytvplayer.data.db.ChannelEpgMappingEntity
import com.biskyt.mytvplayer.data.db.EpgSourceEntity
import com.biskyt.mytvplayer.data.db.SyncChoiceEntity
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext

/**
 * What a [SyncChoiceEntity] is about. The sync between TVs sends each one as it is (see ProgressSync), and
 * SyncedChoices applies them on the other TVs.
 */
object ChoiceKinds {
    /** A favourite channel: the value is its place in the user's order. */
    const val FAVOURITE = "fav"

    /** A favourite film or series ([vodFavourite]): the value is its place in the user's order. */
    const val VOD_FAVOURITE = "fav:"

    /** A channel on "Recently watched": "1". The time is when it was watched. */
    const val RECENT = "recent"

    /** A hidden channel: "1". */
    const val HIDDEN = "hidden"

    /** A guide switched on for the playlist: the id is its address, the value its name. */
    const val GUIDE = "guide"

    /**
     * A guide's place in the playlist's order (see EpgRepository.moveSource): the id is its address ("" for the
     * provider's own guide), the value its place.
     */
    const val GUIDE_ORDER = "guide-order"

    /** The guide channel that the user chose for a channel: see [GuideChoice]. */
    const val GUIDE_CHANNEL = "guide-channel"

    const val INCLUDED = "cat:"
    const val RESTRICTED = "restricted:"

    /** A row (playlist 0, never sent) that says the choices from before this version have a row: see SyncedChoices. */
    const val SEEDED = "seeded"

    const val ON = "1"
    private const val OFF = "0"

    fun vodFavourite(type: String) = "$VOD_FAVOURITE$type"

    /** A category of [type] ticked ("1") or unticked ("0") in Settings › Categories. */
    fun included(type: String) = "$INCLUDED$type"

    /** A category of [type] restricted ("1") or not ("0") by the parental controls. */
    fun restricted(type: String) = "$RESTRICTED$type"

    /** The category type of an [included] or [restricted] kind, or of a [vodFavourite]. */
    fun typeOf(kind: String) = kind.substringAfter(':')

    fun isVodFavourite(kind: String) = kind.startsWith(VOD_FAVOURITE)

    fun isIncluded(kind: String) = kind.startsWith(INCLUDED)

    fun isRestricted(kind: String) = kind.startsWith(RESTRICTED)

    fun flag(on: Boolean) = if (on) ON else OFF

    /** A choice that is on or none, such as a hidden channel. */
    fun onOrNone(on: Boolean) = if (on) ON else null
}

/**
 * The guide channel the user chose for a channel, as a [ChoiceKinds.GUIDE_CHANNEL] value: [guide] is the guide's
 * address, or empty for the provider's own guide (its address holds the login, so it is never sent). [xmlId] null:
 * the user chose no guide.
 */
data class GuideChoice(val guide: String, val xmlId: String?, val offsetMinutes: Int) {
    fun encode() = if (xmlId == null) NONE else listOf(guide, xmlId, offsetMinutes).joinToString(SEP)

    companion object {
        private const val NONE = "-"
        private const val SEP = "\t"

        val noGuide = GuideChoice("", null, 0)

        /** The choice in [m], whose guide is [source]; null when it is not the user's (matched automatically). */
        fun of(m: ChannelEpgMappingEntity, source: EpgSourceEntity?): GuideChoice? = when {
            !m.manual -> null
            m.sourceId == null || m.xmlId == null || source == null -> noGuide
            else -> GuideChoice(source.syncKey, m.xmlId, m.offsetMinutes)
        }

        fun decode(value: String): GuideChoice? {
            if (value == NONE) return noGuide
            val parts = value.split(SEP)
            return if (parts.size == 3) GuideChoice(parts[0], parts[1], parts[2].toIntOrNull() ?: 0) else null
        }
    }
}

/**
 * Keeps when the user last changed each of their choices, for the sync between TVs. The repositories call it with
 * each change. It also keeps changes made while the sync is off, so they are sent once it is on.
 */
class ChoiceLog(
    private val db: AppDatabase,
    private val listener: ProgressListener = ProgressListener.NONE,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * The user changed the [values] of items (id to value) of [kind] in [playlistId], now. [send]: send them soon
     * (else with the next sync, as while a channel plays).
     */
    suspend fun record(playlistId: Long, kind: String, values: Map<String, String?>, send: Boolean = true) {
        // A choice taken from another TV keeps that TV's time: the sync writes it itself.
        if (values.isEmpty() || currentCoroutineContext()[ApplyingKey] != null) return
        val at = clock()
        db.choices().upsert(values.map { (id, value) -> SyncChoiceEntity(playlistId, kind, id, value, at) })
        listener.changed(stopped = send)
    }

    suspend fun record(playlistId: Long, kind: String, id: Any, value: String?, send: Boolean = true) =
        record(playlistId, kind, mapOf(id.toString() to value), send)

    /** Favourites ([kind]) put in a new order: id to place. */
    suspend fun recordOrder(playlistId: Long, kind: String, positions: Map<Long, Long>) =
        record(playlistId, kind, positions.entries.associate { (id, p) -> id.toString() to p.toString() })

    /** While it is in the coroutine context, changes are not recorded: they come from another TV. */
    object Applying : AbstractCoroutineContextElement(ApplyingKey)

    private object ApplyingKey : CoroutineContext.Key<Applying>
}

/** A provider's own guide: its address holds the login, so the sync never sends it. Each TV adds its own. */
val EpgSourceEntity.isProviders: Boolean get() = playlistId != null

/** A guide's id in the synced choices: its address, or "" for a provider's own guide (its address holds the login). */
val EpgSourceEntity.syncKey: String get() = if (isProviders) "" else url
