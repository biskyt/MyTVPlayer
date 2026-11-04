package com.biskyt.mytvplayer.data

import androidx.room.withTransaction
import com.biskyt.mytvplayer.core.epg.CategoryLanguageDetector
import com.biskyt.mytvplayer.core.sync.SyncedChoice
import com.biskyt.mytvplayer.data.db.AppDatabase
import com.biskyt.mytvplayer.data.db.CAT_LIVE
import com.biskyt.mytvplayer.data.db.EpgSourceEntity
import com.biskyt.mytvplayer.data.db.SyncChoiceEntity

/** What a playlist must download again after choices from another TV. */
data class Reload(val types: MutableSet<String> = HashSet(), var guides: Boolean = false) {
    val any: Boolean get() = types.isNotEmpty() || guides
}

/** This TV's side of the choices that the sync between TVs keeps the same (see ProgressSync). */
interface ChoiceTarget {
    /** Before each sync: this TV's choices from before this version get a row, so they count in the merge too. */
    suspend fun seed()

    /** All of this TV's choices, for its file. */
    suspend fun local(): List<SyncChoiceEntity>

    /**
     * Takes [choices] of one [kind], from another TV, for playlist [playlistId]. Returns those it took: one whose item
     * is not on this TV yet (a channel or a category not loaded) is tried again with the next sync. Adds to [reload]
     * what must download again.
     */
    suspend fun apply(playlistId: Long, kind: String, choices: List<SyncedChoice>, reload: Reload): List<SyncedChoice>

    companion object {
        val NONE = object : ChoiceTarget {
            override suspend fun seed() = Unit

            override suspend fun local() = emptyList<SyncChoiceEntity>()

            override suspend fun apply(playlistId: Long, kind: String, choices: List<SyncedChoice>, reload: Reload) = choices
        }
    }
}

/** Applies the choices from other TVs through the repositories, so that each change has its usual effects. */
class SyncedChoices(
    private val db: AppDatabase,
    private val channels: ChannelRepository,
    private val vod: VodRepository,
    private val categories: CategoryStore,
    private val parental: ParentalRepository,
    private val epg: EpgRepository,
) : ChoiceTarget {
    private val dao = db.choices()

    override suspend fun local(): List<SyncChoiceEntity> = dao.all()

    /**
     * Once (again after a restore, which empties the table): what was chosen before this version, or restored from a
     * backup, gets a row as the oldest choice. Rows already there stay.
     */
    override suspend fun seed() {
        if (!dao.has(ChoiceKinds.SEEDED)) db.withTransaction { seedAll() }
    }

    private suspend fun seedAll() {
        dao.seedIncluded(ChoiceKinds.INCLUDED, ChoiceKinds.flag(true), ChoiceKinds.flag(false))
        dao.seedFavourites(ChoiceKinds.FAVOURITE)
        dao.seedVodFavourites(ChoiceKinds.VOD_FAVOURITE)
        dao.seedRecent(ChoiceKinds.RECENT, ChoiceKinds.ON)
        dao.seedHidden(ChoiceKinds.HIDDEN, ChoiceKinds.ON)
        dao.seedRestricted(ChoiceKinds.RESTRICTED, ChoiceKinds.flag(true), ChoiceKinds.flag(false), CategoryLanguageDetector.ADULT)
        dao.seedGuides(ChoiceKinds.GUIDE)
        val known = dao.all().filter { it.kind == ChoiceKinds.GUIDE_CHANNEL }.mapTo(HashSet()) { it.playlistId to it.itemId }
        val sources = HashMap<Long, EpgSourceEntity?>()
        dao.upsert(
            db.backup().manualMappings().filter { (it.playlistId to it.streamId.toString()) !in known }.mapNotNull { m ->
                val source = m.sourceId?.let { id -> sources.getOrPut(id) { db.epg().source(id) } }
                GuideChoice.of(m, source)?.let {
                    SyncChoiceEntity(m.playlistId, ChoiceKinds.GUIDE_CHANNEL, m.streamId.toString(), it.encode(), 1)
                }
            } + SyncChoiceEntity(0, ChoiceKinds.SEEDED, "", ChoiceKinds.ON, 0)
        )
    }

    override suspend fun apply(playlistId: Long, kind: String, choices: List<SyncedChoice>, reload: Reload): List<SyncedChoice> = when {
        kind == ChoiceKinds.FAVOURITE -> choices.onEach {
            channels.takeFavourite(playlistId, it.id.toLong(), it.value?.toLongOrNull(), it.at)
        }
        ChoiceKinds.isVodFavourite(kind) -> choices.onEach {
            vod.takeFavourite(playlistId, ChoiceKinds.typeOf(kind), it.id.toLong(), it.value?.toLongOrNull(), it.at)
        }
        kind == ChoiceKinds.RECENT -> choices.onEach { recent(playlistId, it) }
        kind == ChoiceKinds.HIDDEN -> hidden(playlistId, choices)
        ChoiceKinds.isIncluded(kind) -> included(playlistId, ChoiceKinds.typeOf(kind), choices, reload)
        ChoiceKinds.isRestricted(kind) -> restricted(playlistId, ChoiceKinds.typeOf(kind), choices)
        kind == ChoiceKinds.GUIDE -> choices.onEach { guide(playlistId, it.id, it.value, reload) }
        kind == ChoiceKinds.GUIDE_CHANNEL -> choices.filter {
            guideChannel(playlistId, it.id.toLong(), it.value)
        }.also { epg.releaseMatcher() }
        // A kind from a newer version: left for it.
        else -> choices
    }

    private suspend fun recent(playlistId: Long, choice: SyncedChoice) {
        val id = choice.id.toLong()
        if (choice.value == null) channels.removeRecent(playlistId, id) else channels.addRecent(playlistId, id, choice.at)
    }

    /** The channel counts are worked out once, after all of them. */
    private suspend fun hidden(playlistId: Long, choices: List<SyncedChoice>): List<SyncedChoice> {
        val dao = db.channels()
        val taken = choices.filter { dao.get(playlistId, it.id.toLong()) != null }
        taken.forEach { dao.setHidden(playlistId, it.id.toLong(), it.value != null) }
        if (taken.isNotEmpty()) db.categories().refreshCounts(playlistId, CAT_LIVE)
        return taken
    }

    /** Ticks or unticks the categories this TV has, in one write for each. */
    private suspend fun included(playlistId: Long, type: String, choices: List<SyncedChoice>, reload: Reload): List<SyncedChoice> {
        val local = db.categories().all(playlistId, type).associateBy { it.categoryId }
        val taken = choices.filter { it.id in local }
        taken.filter { local.getValue(it.id).included != (it.value == ChoiceKinds.ON) }
            .groupBy { it.value == ChoiceKinds.ON }
            .forEach { (included, changed) ->
                categories.choose(playlistId, type, changed.map { it.id }, included)
                reload.types += type
            }
        return taken
    }

    /**
     * With a PIN set on this TV, another TV cannot lift a restriction here (as a restore cannot): only the PIN can.
     * Such a choice still counts as taken, so it is not tried again.
     */
    private suspend fun restricted(playlistId: Long, type: String, choices: List<SyncedChoice>): List<SyncedChoice> {
        val local = db.categories().all(playlistId, type).mapTo(HashSet()) { it.categoryId }
        val pin = parental.pinSet()
        return choices.filter { it.id in local }.onEach {
            val restricted = it.value == ChoiceKinds.ON
            if (restricted || !pin) parental.setRestricted(playlistId, type, it.id, restricted)
        }
    }

    /** [name] null: switched off. A guide this TV does not have yet is added. */
    private suspend fun guide(playlistId: Long, url: String, name: String?, reload: Reload) {
        val source = db.epg().sourceByUrl(url)
        when {
            name != null && source == null -> epg.addSource(name, url, playlistId)
            source != null -> epg.setSourceOn(playlistId, source, on = name != null)
            else -> return
        }
        if (name != null) reload.guides = true
    }

    /** [value] null: matched automatically again. Returns false when the channel, or its guide, is not here yet. */
    private suspend fun guideChannel(playlistId: Long, streamId: Long, value: String?): Boolean {
        val channel = db.channels().get(playlistId, streamId) ?: return false
        val choice = value?.let { GuideChoice.decode(it) ?: return true }
        when {
            choice == null -> epg.resetToAutomatic(playlistId, streamId, channel.name, channel.providerEpgId)
            choice.xmlId == null -> epg.setNoGuide(playlistId, streamId)
            else -> {
                val dao = db.epg()
                val source = (if (choice.guide.isEmpty()) dao.providerSource(playlistId) else dao.sourceByUrl(choice.guide)) ?: return false
                epg.setManual(playlistId, streamId, source.id, choice.xmlId, choice.offsetMinutes)
            }
        }
        return true
    }
}
