package com.biskyt.mytvplayer.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** The most `IN (…)` arguments passed in one query (Android before 12 allows 999): longer lists go in chunks. */
const val MAX_SQL_ARGS = 900

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlist ORDER BY id")
    fun observeAll(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlist WHERE id = :id")
    suspend fun get(id: Long): PlaylistEntity?

    @Query("UPDATE playlist SET expiresAt = :expiresAt, maxConnections = :maxConnections WHERE id = :id")
    suspend fun setAccount(id: Long, expiresAt: Long?, maxConnections: Int?)

    @Query("UPDATE playlist SET lastSyncAt = :at WHERE id = :id")
    suspend fun setLastSync(id: Long, at: Long)

    @Query("SELECT * FROM playlist ORDER BY id")
    suspend fun all(): List<PlaylistEntity>

    @Insert
    suspend fun insert(p: PlaylistEntity): Long

    @Upsert
    suspend fun upsert(p: PlaylistEntity)

    @Query("DELETE FROM playlist WHERE id = :id")
    suspend fun delete(id: Long)

    // Rows belonging to a playlist, removed together when it is deleted.
    @Query("DELETE FROM category WHERE playlistId = :pid")
    suspend fun deleteCategories(pid: Long)

    @Query("DELETE FROM channel WHERE playlistId = :pid")
    suspend fun deleteChannels(pid: Long)

    @Query("DELETE FROM favourite WHERE playlistId = :pid")
    suspend fun deleteFavourites(pid: Long)

    @Query("DELETE FROM recent WHERE playlistId = :pid")
    suspend fun deleteRecents(pid: Long)

    @Query("DELETE FROM channel_epg_mapping WHERE playlistId = :pid")
    suspend fun deleteMappings(pid: Long)

    @Query("DELETE FROM playlist_epg_source WHERE playlistId = :pid")
    suspend fun deleteGuideLinks(pid: Long)

    @Query("DELETE FROM movie WHERE playlistId = :pid")
    suspend fun deleteMovies(pid: Long)

    @Query("DELETE FROM series WHERE playlistId = :pid")
    suspend fun deleteSeries(pid: Long)

    @Query("DELETE FROM episode WHERE playlistId = :pid")
    suspend fun deleteEpisodes(pid: Long)

    @Query("UPDATE playlist SET vodFeedHash = :hash WHERE id = :id")
    suspend fun setVodFeedHash(id: Long, hash: Long?)

    @Query("DELETE FROM watch_progress WHERE playlistId = :pid")
    suspend fun deleteProgress(pid: Long)

    @Query("DELETE FROM vod_favourite WHERE playlistId = :pid")
    suspend fun deleteVodFavourites(pid: Long)
}

@Dao
interface CategoryDao {
    // Every query is for one [CategoryEntity.type]: live, movie or series.

    @Query("SELECT * FROM category WHERE playlistId = :pid AND type = :type ORDER BY sortOrder")
    fun observe(pid: Long, type: String): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM category WHERE playlistId = :pid AND type = :type ORDER BY sortOrder")
    suspend fun all(pid: Long, type: String): List<CategoryEntity>

    @Query("SELECT * FROM category WHERE playlistId = :pid AND type = :type AND included = 1 ORDER BY sortOrder")
    fun observeIncluded(pid: Long, type: String): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM category WHERE playlistId = :pid AND type = :type AND included = 1")
    suspend fun included(pid: Long, type: String): List<CategoryEntity>

    @Upsert
    suspend fun upsert(items: List<CategoryEntity>)

    @Query("UPDATE category SET included = :included WHERE playlistId = :pid AND type = :type AND categoryId IN (:ids)")
    suspend fun setIncluded(pid: Long, type: String, ids: List<String>, included: Boolean)

    @Query(
        """UPDATE category SET channelCount = CASE type
             WHEN 'live' THEN (SELECT COUNT(*) FROM channel c WHERE c.playlistId = :pid AND c.categoryId = category.categoryId AND c.hidden = 0)
             WHEN 'movie' THEN (SELECT COUNT(*) FROM movie m WHERE m.playlistId = :pid AND m.categoryId = category.categoryId)
             ELSE (SELECT COUNT(*) FROM series s WHERE s.playlistId = :pid AND s.categoryId = category.categoryId) END
           WHERE playlistId = :pid AND type = :type"""
    )
    suspend fun refreshCounts(pid: Long, type: String)

    @Query("DELETE FROM category WHERE playlistId = :pid AND type = :type")
    suspend fun deleteAll(pid: Long, type: String)

    /** At most [MAX_SQL_ARGS] [ids] at a time. */
    @Query("DELETE FROM category WHERE playlistId = :pid AND type = :type AND categoryId IN (:ids)")
    suspend fun delete(pid: Long, type: String, ids: List<String>)
}

// The favourite channels of :pid (c the channel, f the favourite), and their order: the user's, else channel numbers.
private const val CHANNEL_FAVOURITES =
    "FROM channel c JOIN favourite f ON f.playlistId = c.playlistId AND f.streamId = c.streamId WHERE c.playlistId = :pid"
private const val CHANNEL_FAVOURITE_ORDER = "ORDER BY f.position, c.position, c.name"

/** A channel number starting with the typed digits (channels without a number are stored as 0 and never match). */
private const val NUMBER_MATCH = "(number > 0 AND CAST(number AS TEXT) LIKE :q || '%')"

/** Contains the typed text, taken literally: callers escape it with likeArg (so "%" and "_" aren't wildcards). */
private const val LIKE_Q = "LIKE '%' || :q || '%' ESCAPE '\\'"

/** A channel matches a typed filter by name, or by the start of its number. Shared by the lists and search. */
private const val CHANNEL_MATCH = "(name $LIKE_Q OR $NUMBER_MATCH)"

/** Lightweight row used to restore user preferences across syncs. */
data class ChannelPrefs(val streamId: Long, val hidden: Boolean, val sortOrder: Int?)

@Dao
interface ChannelDao {
    // Ordering shared by every list so zapping follows what the user sees.
    // Users can override the provider order with sortOrder.

    @Query(
        """SELECT * FROM channel WHERE playlistId = :pid AND hidden = 0
           AND (:q = '' OR $CHANNEL_MATCH)
           ORDER BY position, name"""
    )
    fun pageAll(pid: Long, q: String): PagingSource<Int, ChannelEntity>

    @Query(
        """SELECT * FROM channel WHERE playlistId = :pid AND hidden = 0 AND categoryId = :cat
           AND (:q = '' OR $CHANNEL_MATCH)
           ORDER BY position, name"""
    )
    fun pageCategory(pid: Long, cat: String, q: String): PagingSource<Int, ChannelEntity>

    @Query("SELECT c.* $CHANNEL_FAVOURITES AND (:q = '' OR $CHANNEL_MATCH) $CHANNEL_FAVOURITE_ORDER")
    fun pageFavourites(pid: Long, q: String): PagingSource<Int, ChannelEntity>

    @Query(
        """SELECT c.* FROM channel c JOIN recent r ON r.playlistId = c.playlistId AND r.streamId = c.streamId
           WHERE c.playlistId = :pid AND (:q = '' OR $CHANNEL_MATCH)
           ORDER BY r.watchedAt DESC"""
    )
    fun pageRecent(pid: Long, q: String): PagingSource<Int, ChannelEntity>

    @Query("SELECT streamId FROM channel WHERE playlistId = :pid AND hidden = 0 ORDER BY position, name")
    suspend fun idsAll(pid: Long): List<Long>

    /** Search: the same match as the list filter (name, or the number's start), first [limit] in list order. */
    @Query(
        """SELECT * FROM channel WHERE playlistId = :pid AND hidden = 0
           AND $CHANNEL_MATCH ORDER BY position, name LIMIT :limit"""
    )
    suspend fun search(pid: Long, q: String, limit: Int): List<ChannelEntity>

    @Query("SELECT * FROM channel WHERE playlistId = :pid AND hidden = 0 AND $NUMBER_MATCH ORDER BY number LIMIT :limit")
    suspend fun searchNumber(pid: Long, q: String, limit: Int): List<ChannelEntity>

    @Query("SELECT streamId FROM channel WHERE playlistId = :pid AND hidden = 0 AND categoryId = :cat ORDER BY position, name")
    suspend fun idsCategory(pid: Long, cat: String): List<Long>

    @Query(
        """SELECT * FROM channel WHERE playlistId = :pid AND hidden = 0 AND archiveDays > 0
           AND (:q = '' OR $CHANNEL_MATCH)
           ORDER BY position, name"""
    )
    fun pageCatchup(pid: Long, q: String): PagingSource<Int, ChannelEntity>

    @Query("SELECT streamId FROM channel WHERE playlistId = :pid AND hidden = 0 AND archiveDays > 0 ORDER BY position, name")
    suspend fun idsCatchup(pid: Long): List<Long>

    @Query(
        """SELECT * FROM channel WHERE playlistId = :pid AND categoryId = :cat AND hidden = 0 AND archiveDays > 0
           AND (:q = '' OR $CHANNEL_MATCH)
           ORDER BY position, name"""
    )
    fun pageCatchupCategory(pid: Long, cat: String, q: String): PagingSource<Int, ChannelEntity>

    @Query(
        """SELECT streamId FROM channel WHERE playlistId = :pid AND categoryId = :cat AND hidden = 0 AND archiveDays > 0
           ORDER BY position, name"""
    )
    suspend fun idsCatchupCategory(pid: Long, cat: String): List<Long>

    @Query(
        """SELECT c.categoryId AS categoryId, k.name AS name, k.lang AS lang, COUNT(*) AS count
           FROM channel c JOIN category k ON k.playlistId = c.playlistId AND k.type = 'live' AND k.categoryId = c.categoryId
           WHERE c.playlistId = :pid AND c.hidden = 0 AND c.archiveDays > 0 AND k.included = 1
           GROUP BY c.categoryId ORDER BY k.sortOrder"""
    )
    fun observeCatchupCategories(pid: Long): Flow<List<CategoryCount>>

    @Query("SELECT EXISTS(SELECT 1 FROM channel WHERE playlistId = :pid AND hidden = 0 AND archiveDays > 0)")
    fun observeHasCatchup(pid: Long): Flow<Boolean>

    @Query("DELETE FROM channel WHERE playlistId = :pid AND categoryId = :cat")
    suspend fun deleteCategory(pid: Long, cat: String)

    @Query("SELECT c.streamId $CHANNEL_FAVOURITES $CHANNEL_FAVOURITE_ORDER")
    suspend fun idsFavourites(pid: Long): List<Long>

    @Query(
        "SELECT c.streamId FROM channel c JOIN recent r ON r.playlistId = c.playlistId AND r.streamId = c.streamId WHERE c.playlistId = :pid ORDER BY r.watchedAt DESC"
    )
    suspend fun idsRecent(pid: Long): List<Long>

    @Query("SELECT c.* $CHANNEL_FAVOURITES $CHANNEL_FAVOURITE_ORDER LIMIT :limit")
    fun observeFavourites(pid: Long, limit: Int): Flow<List<ChannelEntity>>

    @Query(
        "SELECT c.* FROM channel c JOIN recent r ON r.playlistId = c.playlistId AND r.streamId = c.streamId WHERE c.playlistId = :pid ORDER BY r.watchedAt DESC LIMIT :limit"
    )
    fun observeRecent(pid: Long, limit: Int): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channel WHERE playlistId = :pid AND streamId = :id")
    suspend fun get(pid: Long, id: Long): ChannelEntity?

    @Query("SELECT * FROM channel WHERE playlistId = :pid AND number = :number AND hidden = 0 LIMIT 1")
    suspend fun byNumber(pid: Long, number: Int): ChannelEntity?

    @Query("SELECT COUNT(*) FROM channel WHERE playlistId = :pid AND hidden = 0")
    fun observeCount(pid: Long): Flow<Int>

    /** The longest catch-up any channel offers (0 when none do): how far back the TV guide can go. */
    @Query("SELECT COALESCE(MAX(archiveDays), 0) FROM channel WHERE playlistId = :pid AND hidden = 0")
    suspend fun maxArchiveDays(pid: Long): Int

    @Query("SELECT streamId, hidden, sortOrder FROM channel WHERE playlistId = :pid AND (hidden = 1 OR sortOrder IS NOT NULL)")
    suspend fun prefs(pid: Long): List<ChannelPrefs>

    @Upsert
    suspend fun upsert(items: List<ChannelEntity>)

    @Query("DELETE FROM channel WHERE playlistId = :pid AND syncStamp != :stamp")
    suspend fun deleteStale(pid: Long, stamp: Long)

    @Query("UPDATE channel SET hidden = :hidden WHERE playlistId = :pid AND streamId = :id")
    suspend fun setHidden(pid: Long, id: Long, hidden: Boolean)

    // The user's own order, as Move up and down (until 1.2.1) set it; kept, and restored from backups.
    @Query("UPDATE channel SET sortOrder = :order, position = COALESCE(:order, number) WHERE playlistId = :pid AND streamId = :id")
    suspend fun setSortOrder(pid: Long, id: Long, order: Int?)

    @Query("SELECT * FROM channel WHERE playlistId = :pid AND hidden = 1 ORDER BY name")
    fun observeHidden(pid: Long): Flow<List<ChannelEntity>>

    @Query("SELECT streamId, name, providerEpgId FROM channel WHERE playlistId = :pid")
    suspend fun names(pid: Long): List<ChannelName>
}

/** A category and how many of something it has. */
data class CategoryCount(val categoryId: String, val name: String, val lang: String?, val count: Int)

data class ChannelName(val streamId: Long, val name: String, val providerEpgId: String?)

@Dao
interface FavouriteDao {
    @Query("SELECT streamId FROM favourite WHERE playlistId = :pid")
    fun observeIds(pid: Long): Flow<List<Long>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun add(f: FavouriteEntity)

    @Query("DELETE FROM favourite WHERE playlistId = :pid AND streamId = :id")
    suspend fun remove(pid: Long, id: Long)

    @Query("SELECT COALESCE(MAX(position), 0) FROM favourite WHERE playlistId = :pid")
    suspend fun lastPosition(pid: Long): Long

    @Query("UPDATE favourite SET position = :position WHERE playlistId = :pid AND streamId = :id")
    suspend fun setPosition(pid: Long, id: Long, position: Long)

    @Upsert
    suspend fun touchRecent(r: RecentEntity)

    @Query(
        "DELETE FROM recent WHERE playlistId = :pid AND streamId NOT IN (SELECT streamId FROM recent WHERE playlistId = :pid ORDER BY watchedAt DESC LIMIT :keep)"
    )
    suspend fun trimRecent(pid: Long, keep: Int)

    @Query("DELETE FROM recent WHERE playlistId = :pid AND streamId = :id")
    suspend fun removeRecent(pid: Long, id: Long)
}

/** A programme found by search, with its channel. */
data class ProgrammeHit(
    val streamId: Long,
    val channelName: String,
    val logoUrl: String?,
    val startMs: Long,
    val stopMs: Long,
    val title: String,
    val subtitle: String?
)

/** Now/next row: a programme for a playlist channel with the channel's time shift already applied. */
data class ChannelProgramme(
    val streamId: Long,
    val startMs: Long,
    val stopMs: Long,
    val title: String,
    val subtitle: String?,
    val description: String?,
    /** Series, film or neither (a ProgrammeKind constant), and an episode's place in its series. */
    val kind: Int = 0,
    val season: Int? = null,
    val episode: Int? = null
)

/** Mapping row joined with its guide channel name, for the matching screen. */
data class MappingInfo(
    val streamId: Long,
    val sourceId: Long?,
    val xmlId: String?,
    val manual: Boolean,
    val score: Double,
    val offsetMinutes: Int,
    val displayName: String?,
    val sourceName: String?
)

@Dao
interface EpgDao {
    @Query("SELECT * FROM epg_source ORDER BY id")
    fun observeSources(): Flow<List<EpgSourceEntity>>

    @Query("UPDATE epg_source SET lastImportAt = :at WHERE id = :id")
    suspend fun setLastImport(id: Long, at: Long)

    @Query("UPDATE epg_source SET lastError = :error WHERE id = :id")
    suspend fun setSourceError(id: Long, error: String?)

    // Only these columns: a long import mustn't write back an older copy of the rest (a new address, say).
    @Query("UPDATE epg_source SET lastImportAt = :at, channelCount = :channelCount, lastError = :error WHERE id = :id")
    suspend fun setImportResult(id: Long, at: Long, channelCount: Int, error: String?)

    @Query("SELECT * FROM playlist_epg_source")
    fun observeLinks(): Flow<List<PlaylistEpgSourceEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun link(links: List<PlaylistEpgSourceEntity>)

    @Query("DELETE FROM playlist_epg_source WHERE playlistId = :pid AND sourceId = :sourceId")
    suspend fun unlink(pid: Long, sourceId: Long)

    @Query("DELETE FROM playlist_epg_source WHERE sourceId = :sourceId")
    suspend fun deleteLinksForSource(sourceId: Long)

    @Query("SELECT EXISTS (SELECT 1 FROM playlist_epg_source WHERE sourceId = :sourceId)")
    suspend fun isUsed(sourceId: Long): Boolean

    @Query("SELECT xmlId FROM epg_channel WHERE sourceId = :sourceId")
    suspend fun channelXmlIds(sourceId: Long): List<String>

    @Query("SELECT id FROM epg_source")
    suspend fun allSourceIds(): List<Long>

    /** The guides switched on for any of [pids]. */
    @Query("SELECT * FROM epg_source WHERE id IN (SELECT sourceId FROM playlist_epg_source WHERE playlistId IN (:pids)) ORDER BY id")
    suspend fun sourcesFor(pids: List<Long>): List<EpgSourceEntity>

    @Query("SELECT * FROM epg_source WHERE playlistId = :pid")
    suspend fun providerSource(pid: Long): EpgSourceEntity?

    @Query("SELECT * FROM epg_source WHERE url = :url LIMIT 1")
    suspend fun sourceByUrl(url: String): EpgSourceEntity?

    @Insert
    suspend fun insertSource(s: EpgSourceEntity): Long

    @Upsert
    suspend fun upsertSource(s: EpgSourceEntity)

    @Query("DELETE FROM epg_source WHERE id = :id")
    suspend fun deleteSource(id: Long)

    @Query("DELETE FROM channel_epg_mapping WHERE sourceId = :sourceId")
    suspend fun deleteMappingsForSource(sourceId: Long)

    /** [pid]'s automatic matching that found nothing, so the next update tries again. */
    @Query("DELETE FROM channel_epg_mapping WHERE playlistId = :pid AND manual = 0 AND sourceId IS NULL")
    suspend fun deleteFailedAutoMappings(pid: Long)

    /** Programmes of the guides no playlist uses (found among the few guides, then deleted by key). */
    @Query("DELETE FROM programme WHERE sourceId IN (SELECT id FROM epg_source WHERE id NOT IN (SELECT sourceId FROM playlist_epg_source))")
    suspend fun clearUnusedProgrammes()

    /** Automatic matches to guides switched off for their playlist (the user's own choices stay, for when one is back). */
    @Query(
        """DELETE FROM channel_epg_mapping WHERE manual = 0 AND sourceId IS NOT NULL AND NOT EXISTS
             (SELECT 1 FROM playlist_epg_source l WHERE l.playlistId = channel_epg_mapping.playlistId
                AND l.sourceId = channel_epg_mapping.sourceId)"""
    )
    suspend fun deleteUnlinkedAutoMappings()

    @Query("DELETE FROM epg_channel WHERE sourceId = :sourceId")
    suspend fun clearChannels(sourceId: Long)

    @Upsert
    suspend fun upsertChannels(items: List<EpgChannelEntity>)

    /** The channels of the guides switched on for [pid]: what its matching chooses from. */
    @Query("SELECT e.* FROM epg_channel e JOIN playlist_epg_source l ON l.sourceId = e.sourceId WHERE l.playlistId = :pid")
    suspend fun channelsFor(pid: Long): List<EpgChannelEntity>

    /** The guide channels to choose from by hand for [pid]: those of its guides switched on. */
    @Query(
        """SELECT e.* FROM epg_channel e JOIN playlist_epg_source l ON l.sourceId = e.sourceId
           WHERE l.playlistId = :pid AND (:q = '' OR e.displayName $LIKE_Q OR e.xmlId $LIKE_Q)
           ORDER BY e.displayName LIMIT :limit"""
    )
    suspend fun searchChannels(pid: Long, q: String, limit: Int): List<EpgChannelEntity>

    @Query("DELETE FROM programme WHERE sourceId = :sourceId")
    suspend fun clearProgrammes(sourceId: Long)

    @Query("DELETE FROM programme WHERE sourceId = :sourceId AND stamp != :stamp")
    suspend fun deleteOlderProgrammes(sourceId: Long, stamp: Long)

    @Query("DELETE FROM programme WHERE sourceId = :sourceId AND xmlId IN (:xmlIds) AND stamp != :stamp")
    suspend fun deleteOlderProgrammes(sourceId: Long, xmlIds: List<String>, stamp: Long)

    @Query("DELETE FROM programme WHERE stopMs < :before")
    suspend fun pruneProgrammes(before: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProgrammes(items: List<ProgrammeEntity>)

    /**
     * Search: programmes on now or later whose title matches, on visible channels that use the guide, soonest first.
     * Scans every stored programme (only guide channels in use, for a few days), so search asks for 3+ letters.
     */
    @Query(
        """SELECT m.streamId, c.name AS channelName, c.logoUrl, p.startMs + m.offsetMinutes * 60000 AS startMs,
                  p.stopMs + m.offsetMinutes * 60000 AS stopMs, p.title, p.subtitle
           FROM programme p JOIN channel_epg_mapping m ON m.sourceId = p.sourceId AND m.xmlId = p.xmlId
           JOIN channel c ON c.playlistId = m.playlistId AND c.streamId = m.streamId
           JOIN playlist_epg_source l ON l.playlistId = m.playlistId AND l.sourceId = m.sourceId
           WHERE m.playlistId = :pid AND c.hidden = 0
             AND p.startMs > :from - m.offsetMinutes * 60000 - $MAX_PROGRAMME_MS
             AND p.stopMs > :from - m.offsetMinutes * 60000
             AND p.title $LIKE_Q
           ORDER BY startMs, c.position LIMIT :limit"""
    )
    suspend fun searchProgrammes(pid: Long, q: String, from: Long, limit: Int): List<ProgrammeHit>

    /** The guide channels of [sourceId] that a playlist using it has chosen: those whose programmes are stored. */
    @Query(
        """SELECT DISTINCT m.xmlId FROM channel_epg_mapping m
           JOIN playlist_epg_source l ON l.playlistId = m.playlistId AND l.sourceId = m.sourceId
           WHERE m.sourceId = :sourceId"""
    )
    suspend fun mappedXmlIds(sourceId: Long): List<String>

    // Time bounds are applied to the bare startMs column (offset moved to the other side) so SQLite can range-scan the
    // (sourceId, xmlId, startMs) primary key; programmes are assumed to last at most MAX_PROGRAMME_MS.
    @Query(
        """SELECT m.streamId, p.startMs + m.offsetMinutes * 60000 AS startMs, p.stopMs + m.offsetMinutes * 60000 AS stopMs,
                  p.title, p.subtitle, CASE WHEN :details THEN p.description END AS description, p.kind, p.season, p.episode
           FROM channel_epg_mapping m JOIN programme p ON p.sourceId = m.sourceId AND p.xmlId = m.xmlId
           JOIN playlist_epg_source l ON l.playlistId = m.playlistId AND l.sourceId = m.sourceId
           WHERE m.playlistId = :pid AND m.streamId IN (:ids)
             AND p.startMs > :from - m.offsetMinutes * 60000 - $MAX_PROGRAMME_MS
             AND p.startMs < :to - m.offsetMinutes * 60000
             AND p.stopMs > :from - m.offsetMinutes * 60000
           ORDER BY m.streamId, p.startMs"""
    )
    suspend fun programmes(pid: Long, ids: List<Long>, from: Long, to: Long, details: Boolean): List<ChannelProgramme>

    @Query("SELECT streamId FROM channel_epg_mapping WHERE playlistId = :pid AND manual = 1")
    suspend fun manualStreamIds(pid: Long): List<Long>

    /**
     * Channels for automatic matching, other than those with a manual choice: never tried, or matched to a guide channel
     * that has since gone from its guide; [retryFailed] adds those tried before without a match; [visibleOnly] leaves
     * out hidden channels.
     */
    @Query(
        """SELECT c.streamId, c.name, c.providerEpgId FROM channel c
           LEFT JOIN channel_epg_mapping m ON m.playlistId = c.playlistId AND m.streamId = c.streamId
           WHERE c.playlistId = :pid AND (:visibleOnly = 0 OR c.hidden = 0)
             AND (m.streamId IS NULL
                  OR (m.manual = 0 AND m.sourceId IS NULL AND :retryFailed)
                  OR (m.manual = 0 AND m.sourceId IS NOT NULL
                      AND NOT EXISTS (SELECT 1 FROM epg_channel e WHERE e.sourceId = m.sourceId AND e.xmlId = m.xmlId)))"""
    )
    suspend fun namesToMatch(pid: Long, retryFailed: Boolean, visibleOnly: Boolean): List<ChannelName>

    @Query(
        """SELECT m.streamId, m.sourceId, m.xmlId, m.manual, m.score, m.offsetMinutes, e.displayName, s.name AS sourceName
           FROM channel_epg_mapping m LEFT JOIN epg_channel e ON e.sourceId = m.sourceId AND e.xmlId = m.xmlId
           LEFT JOIN epg_source s ON s.id = m.sourceId
           WHERE m.playlistId = :pid AND m.streamId IN (:ids)"""
    )
    suspend fun mappingInfo(pid: Long, ids: List<Long>): List<MappingInfo>

    @Query("SELECT * FROM channel_epg_mapping WHERE playlistId = :pid AND streamId = :id")
    suspend fun mapping(pid: Long, id: Long): ChannelEpgMappingEntity?

    @Upsert
    suspend fun upsertMappings(items: List<ChannelEpgMappingEntity>)

    @Query("DELETE FROM channel_epg_mapping WHERE playlistId = :pid AND streamId = :id")
    suspend fun deleteMapping(pid: Long, id: Long)

    @Query("DELETE FROM channel_epg_mapping WHERE manual = 0 AND playlistId = :pid")
    suspend fun clearAutoMappings(pid: Long)

    /** Channels with their current guide choice joined in, so rows update by themselves when a mapping changes. */
    @Query(
        """SELECT c.*, m.sourceId AS mapSourceId, m.manual AS mapManual, m.score AS mapScore, m.offsetMinutes AS mapOffset,
                  e.displayName AS guideName, s.name AS sourceName
           FROM channel c
           LEFT JOIN channel_epg_mapping m ON m.playlistId = c.playlistId AND m.streamId = c.streamId
           LEFT JOIN epg_channel e ON e.sourceId = m.sourceId AND e.xmlId = m.xmlId
           LEFT JOIN epg_source s ON s.id = m.sourceId
           WHERE c.playlistId = :pid AND c.hidden = 0 AND (:q = '' OR c.name $LIKE_Q)
             AND CASE :status WHEN 1 THEN m.sourceId IS NULL WHEN 2 THEN m.manual = 0 AND m.sourceId IS NOT NULL WHEN 3 THEN m.manual = 1 ELSE 1 END
           ORDER BY c.position, c.name"""
    )
    fun pageForMatching(pid: Long, q: String, status: Int): PagingSource<Int, MatchRow>

    @Query(
        """SELECT
             (SELECT COUNT(*) FROM channel c WHERE c.playlistId = :pid AND c.hidden = 0) AS total,
             (SELECT COUNT(*) FROM channel c WHERE c.playlistId = :pid AND c.hidden = 0 AND NOT EXISTS
                (SELECT 1 FROM channel_epg_mapping m WHERE m.playlistId = c.playlistId AND m.streamId = c.streamId AND m.sourceId IS NOT NULL)) AS unmatched,
             (SELECT COUNT(*) FROM channel_epg_mapping m WHERE m.playlistId = :pid AND m.manual = 0 AND m.sourceId IS NOT NULL) AS auto,
             (SELECT COUNT(*) FROM channel_epg_mapping m WHERE m.playlistId = :pid AND m.manual = 1) AS manual"""
    )
    fun observeMatchCounts(pid: Long): Flow<MatchCounts>

    @Query(
        """SELECT COALESCE(SUM(has), 0) AS withGuide, COUNT(*) - COALESCE(SUM(has), 0) AS noGuide FROM
             (SELECT EXISTS (SELECT 1 FROM channel_epg_mapping m WHERE m.playlistId = c.playlistId AND m.streamId = c.streamId
                AND m.sourceId IS NOT NULL) AS has
              FROM channel c WHERE c.playlistId = :pid AND c.hidden = 0)"""
    )
    suspend fun coverage(pid: Long): GuideCoverage
}

/** Longest programme the time-range queries allow for; IPTV guides often use 24-hour placeholder entries. */
const val MAX_PROGRAMME_MS = 26 * 3_600_000L

data class MatchRow(
    @androidx.room.Embedded val channel: ChannelEntity,
    val mapSourceId: Long?,
    val mapManual: Boolean?,
    val mapScore: Double?,
    val mapOffset: Int?,
    val guideName: String?,
    val sourceName: String?
)

data class MatchCounts(val total: Int, val unmatched: Int, val auto: Int, val manual: Int)

/** How many of a playlist's visible channels have a guide and how many have none. */
data class GuideCoverage(val withGuide: Int, val noGuide: Int)

/** A poster in a movie or series grid: only what the grid shows, so paging holds as little as possible. */
data class VodCard(val id: Long, val title: String, val year: Int?, val imageUrl: String?)

/**
 * A movie or series found by a search, with the name of its category ([category]; blank for an M3U title with no
 * group, null if its category is gone). The same film is often in several categories (languages, qualities).
 */
data class VodHit(val id: Long, val title: String, val year: Int?, val imageUrl: String?, val category: String?) {
    val card get() = VodCard(id, title, year, imageUrl)
}

private const val MOVIE_CARD = "SELECT streamId AS id, title, year, posterUrl AS imageUrl"
private const val SERIES_CARD = "SELECT seriesId AS id, title, year, coverUrl AS imageUrl"

// Favourite movies and series of :pid (f is the favourite), and their order: the user's, else newest first.
private const val MOVIE_FAVOURITES =
    "FROM movie m JOIN vod_favourite f ON f.playlistId = m.playlistId AND f.kind = 'movie' AND f.itemId = m.streamId WHERE m.playlistId = :pid"
private const val SERIES_FAVOURITES =
    "FROM series s JOIN vod_favourite f ON f.playlistId = s.playlistId AND f.kind = 'series' AND f.itemId = s.seriesId WHERE s.playlistId = :pid"
private const val FAVOURITE_ORDER = "ORDER BY f.position, f.addedAt DESC"

/** Movies, series, favourites among them, and how far the user has watched. */
@Dao
interface VodDao {
    // Lists filter by title as the user types; provider order unless sorted by date.

    @Query("$MOVIE_CARD FROM movie WHERE playlistId = :pid AND (:q = '' OR title $LIKE_Q) ORDER BY position, title")
    fun pageMovies(pid: Long, q: String): PagingSource<Int, VodCard>

    @Query(
        "$MOVIE_CARD FROM movie WHERE playlistId = :pid AND categoryId = :cat AND (:q = '' OR title $LIKE_Q) ORDER BY position, title"
    )
    fun pageMovieCategory(pid: Long, cat: String, q: String): PagingSource<Int, VodCard>

    @Query("$MOVIE_CARD FROM movie WHERE playlistId = :pid AND (:q = '' OR title $LIKE_Q) ORDER BY added DESC")
    fun pageMoviesByAdded(pid: Long, q: String): PagingSource<Int, VodCard>

    @Query("$MOVIE_CARD $MOVIE_FAVOURITES AND (:q = '' OR m.title $LIKE_Q) $FAVOURITE_ORDER")
    fun pageMovieFavourites(pid: Long, q: String): PagingSource<Int, VodCard>

    @Query("$MOVIE_CARD $MOVIE_FAVOURITES $FAVOURITE_ORDER LIMIT :limit")
    fun observeMovieFavourites(pid: Long, limit: Int): Flow<List<VodCard>>

    @Query("$SERIES_CARD FROM series WHERE playlistId = :pid AND (:q = '' OR title $LIKE_Q) ORDER BY position, title")
    fun pageSeries(pid: Long, q: String): PagingSource<Int, VodCard>

    @Query(
        "$SERIES_CARD FROM series WHERE playlistId = :pid AND categoryId = :cat AND (:q = '' OR title $LIKE_Q) ORDER BY position, title"
    )
    fun pageSeriesCategory(pid: Long, cat: String, q: String): PagingSource<Int, VodCard>

    @Query("$SERIES_CARD FROM series WHERE playlistId = :pid AND (:q = '' OR title $LIKE_Q) ORDER BY lastModified DESC")
    fun pageSeriesByUpdated(pid: Long, q: String): PagingSource<Int, VodCard>

    @Query("$SERIES_CARD $SERIES_FAVOURITES AND (:q = '' OR s.title $LIKE_Q) $FAVOURITE_ORDER")
    fun pageSeriesFavourites(pid: Long, q: String): PagingSource<Int, VodCard>

    @Query("$SERIES_CARD $SERIES_FAVOURITES $FAVOURITE_ORDER LIMIT :limit")
    fun observeSeriesFavourites(pid: Long, limit: Int): Flow<List<VodCard>>

    /** The favourite movies' ids, in order. */
    @Query("SELECT m.streamId $MOVIE_FAVOURITES $FAVOURITE_ORDER")
    suspend fun idsMovieFavourites(pid: Long): List<Long>

    /** The favourite series' ids, in order. */
    @Query("SELECT s.seriesId $SERIES_FAVOURITES $FAVOURITE_ORDER")
    suspend fun idsSeriesFavourites(pid: Long): List<Long>

    @Query("SELECT COALESCE(MAX(position), 0) FROM vod_favourite WHERE playlistId = :pid AND kind = :kind")
    suspend fun lastFavouritePosition(pid: Long, kind: String): Long

    @Query("UPDATE vod_favourite SET position = :position WHERE playlistId = :pid AND kind = :kind AND itemId = :id")
    suspend fun setFavouritePosition(pid: Long, kind: String, id: Long, position: Long)

    @Query("SELECT COUNT(*) FROM movie WHERE playlistId = :pid")
    fun observeMovieCount(pid: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM series WHERE playlistId = :pid")
    fun observeSeriesCount(pid: Long): Flow<Int>

    @Query("SELECT (SELECT COUNT(*) FROM movie WHERE playlistId = :pid) + (SELECT COUNT(*) FROM series WHERE playlistId = :pid)")
    suspend fun titleCount(pid: Long): Int

    // The titles first, then the category of each one found: not for every title that matches a short query.
    @Query(
        """SELECT h.id, h.title, h.year, h.imageUrl, c.name AS category FROM (
             SELECT streamId AS id, title, year, posterUrl AS imageUrl, categoryId, position FROM movie
             WHERE playlistId = :pid AND title $LIKE_Q ORDER BY position, title LIMIT :limit
           ) h LEFT JOIN category c ON c.playlistId = :pid AND c.type = '$CAT_MOVIE' AND c.categoryId = h.categoryId
           ORDER BY h.position, h.title"""
    )
    suspend fun searchMovies(pid: Long, q: String, limit: Int): List<VodHit>

    @Query(
        """SELECT h.id, h.title, h.year, h.imageUrl, c.name AS category FROM (
             SELECT seriesId AS id, title, year, coverUrl AS imageUrl, categoryId, position FROM series
             WHERE playlistId = :pid AND title $LIKE_Q ORDER BY position, title LIMIT :limit
           ) h LEFT JOIN category c ON c.playlistId = :pid AND c.type = '$CAT_SERIES' AND c.categoryId = h.categoryId
           ORDER BY h.position, h.title"""
    )
    suspend fun searchSeries(pid: Long, q: String, limit: Int): List<VodHit>

    // Whole lists, used by tests (screens page).
    @Query("SELECT * FROM movie WHERE playlistId = :pid ORDER BY position, title")
    suspend fun moviesSnapshot(pid: Long): List<MovieEntity>

    @Query("SELECT * FROM series WHERE playlistId = :pid ORDER BY position, title")
    suspend fun seriesSnapshot(pid: Long): List<SeriesEntity>

    @Query("SELECT * FROM movie WHERE playlistId = :pid AND streamId = :id")
    suspend fun movie(pid: Long, id: Long): MovieEntity?

    @Query("SELECT * FROM series WHERE playlistId = :pid AND seriesId = :id")
    suspend fun series(pid: Long, id: Long): SeriesEntity?

    // Replace, not upsert: these rows hold no user state, and upserting tens of thousands of existing rows costs a
    // failed insert each.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMovies(items: List<MovieEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSeries(items: List<SeriesEntity>)

    @Query("DELETE FROM movie WHERE playlistId = :pid AND categoryId = :cat")
    suspend fun deleteMovieCategory(pid: Long, cat: String)

    /** A category's series, and their episodes (M3U). */
    @Transaction
    suspend fun deleteSeriesCategory(pid: Long, cat: String) {
        deleteEpisodesInCategory(pid, cat)
        deleteSeriesRows(pid, cat)
    }

    @Query(
        "DELETE FROM episode WHERE playlistId = :pid AND seriesId IN (SELECT seriesId FROM series WHERE playlistId = :pid AND categoryId = :cat)"
    )
    suspend fun deleteEpisodesInCategory(pid: Long, cat: String)

    @Query("DELETE FROM series WHERE playlistId = :pid AND categoryId = :cat")
    suspend fun deleteSeriesRows(pid: Long, cat: String)

    @Query("DELETE FROM movie WHERE playlistId = :pid AND syncStamp != :stamp")
    suspend fun deleteStaleMovies(pid: Long, stamp: Long)

    @Query("DELETE FROM series WHERE playlistId = :pid AND syncStamp != :stamp")
    suspend fun deleteStaleSeries(pid: Long, stamp: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEpisodes(items: List<EpisodeEntity>)

    @Query("DELETE FROM episode WHERE playlistId = :pid AND syncStamp != :stamp")
    suspend fun deleteStaleEpisodes(pid: Long, stamp: Long)

    @Query("SELECT * FROM episode WHERE playlistId = :pid AND seriesId = :seriesId ORDER BY season, number")
    suspend fun episodes(pid: Long, seriesId: Long): List<EpisodeEntity>

    @Query("SELECT * FROM episode WHERE playlistId = :pid AND episodeId = :id")
    suspend fun episode(pid: Long, id: Long): EpisodeEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM vod_favourite WHERE playlistId = :pid AND kind = :kind AND itemId = :id)")
    fun observeFavourite(pid: Long, kind: String, id: Long): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addFavourite(f: VodFavouriteEntity)

    @Query("SELECT itemId FROM vod_favourite WHERE playlistId = :pid AND kind = :kind")
    fun observeFavouriteIds(pid: Long, kind: String): Flow<List<Long>>

    @Query("DELETE FROM vod_favourite WHERE playlistId = :pid AND kind = :kind AND itemId = :id")
    suspend fun removeFavourite(pid: Long, kind: String, id: Long)

    @Upsert
    suspend fun saveProgress(p: WatchProgressEntity)

    @Query("SELECT * FROM watch_progress WHERE playlistId = :pid AND kind = :kind AND itemId = :id")
    suspend fun progress(pid: Long, kind: String, id: Long): WatchProgressEntity?

    @Query("SELECT * FROM watch_progress WHERE playlistId = :pid AND kind = 'episode' AND seriesId = :seriesId")
    suspend fun seriesProgress(pid: Long, seriesId: Long): List<WatchProgressEntity>

    @Query("SELECT * FROM watch_progress WHERE playlistId = :pid AND kind = 'episode' AND seriesId = :seriesId")
    fun observeSeriesProgress(pid: Long, seriesId: Long): Flow<List<WatchProgressEntity>>

    @Query("SELECT * FROM watch_progress WHERE playlistId = :pid AND kind = 'movie' AND itemId = :id")
    fun observeMovieProgress(pid: Long, id: Long): Flow<WatchProgressEntity?>

    /**
     * "Carry on watching", newest first: unfinished movies, and per series its most recent episode, unless that was
     * finished and the last one. [finished] is [WatchProgressEntity.FINISHED_FRACTION].
     */
    @Query(
        """SELECT * FROM watch_progress w WHERE playlistId = :pid
           AND (NOT (durationMs > 0 AND positionMs >= durationMs * :finished) OR hasNext)
           AND (seriesId IS NULL OR updatedAt = (SELECT MAX(x.updatedAt) FROM watch_progress x WHERE x.playlistId = w.playlistId AND x.seriesId = w.seriesId))
           ORDER BY updatedAt DESC LIMIT :limit"""
    )
    fun observeCarryOn(pid: Long, limit: Int, finished: Double): Flow<List<WatchProgressEntity>>

    @Query(
        "DELETE FROM watch_progress WHERE playlistId = :pid AND rowid NOT IN (SELECT rowid FROM watch_progress WHERE playlistId = :pid ORDER BY updatedAt DESC LIMIT :keep)"
    )
    suspend fun trimProgress(pid: Long, keep: Int)

    @Query("DELETE FROM watch_progress WHERE playlistId = :pid AND kind = 'movie' AND itemId = :id")
    suspend fun deleteMovieProgress(pid: Long, id: Long)

    @Query("DELETE FROM watch_progress WHERE playlistId = :pid AND kind = 'episode' AND seriesId = :seriesId")
    suspend fun deleteSeriesProgress(pid: Long, seriesId: Long)

    @Query("DELETE FROM watch_progress WHERE playlistId = :pid AND kind = 'episode' AND itemId = :id")
    suspend fun deleteEpisodeProgress(pid: Long, id: Long)

    /** An episode's row only while it's up next, not started. */
    @Query("DELETE FROM watch_progress WHERE playlistId = :pid AND kind = 'episode' AND itemId = :id AND positionMs = 0")
    suspend fun deleteUpNext(pid: Long, id: Long)
}

/** Everything a backup holds (see BackupRepository), read and replaced as a whole. */
@Dao
interface BackupDao {
    @Query("SELECT * FROM category")
    suspend fun categories(): List<CategoryEntity>

    /** Only channels the user hid or moved: the next sync keeps these choices and fills in the rest. */
    @Query("SELECT * FROM channel WHERE hidden = 1 OR sortOrder IS NOT NULL")
    suspend fun channelChoices(): List<ChannelEntity>

    @Query("SELECT * FROM favourite")
    suspend fun favourites(): List<FavouriteEntity>

    @Query("SELECT * FROM vod_favourite")
    suspend fun vodFavourites(): List<VodFavouriteEntity>

    /** For older versions, which read [EpgSourceEntity.enabled]: on if any playlist uses it. */
    @Query(
        """SELECT id, name, url, playlistId, EXISTS (SELECT 1 FROM playlist_epg_source l WHERE l.sourceId = s.id) AS enabled,
                  lastImportAt, channelCount, lastError
           FROM epg_source s ORDER BY id"""
    )
    suspend fun epgSources(): List<EpgSourceEntity>

    @Query("SELECT * FROM playlist_epg_source")
    suspend fun guideLinks(): List<PlaylistEpgSourceEntity>

    /** Automatic matches are worked out again on the next guide update. */
    @Query("SELECT * FROM channel_epg_mapping WHERE manual = 1")
    suspend fun manualMappings(): List<ChannelEpgMappingEntity>

    @Query("SELECT * FROM watch_progress")
    suspend fun progress(): List<WatchProgressEntity>

    @Insert
    @Suppress("LongParameterList") // one per table
    suspend fun insert(
        playlists: List<PlaylistEntity>,
        categories: List<CategoryEntity>,
        channels: List<ChannelEntity>,
        favourites: List<FavouriteEntity>,
        vodFavourites: List<VodFavouriteEntity>,
        epgSources: List<EpgSourceEntity>,
        guideLinks: List<PlaylistEpgSourceEntity>,
        mappings: List<ChannelEpgMappingEntity>,
        progress: List<WatchProgressEntity>
    )
}
