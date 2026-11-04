package com.biskyt.mytvplayer.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.room.withTransaction
import com.biskyt.mytvplayer.data.db.AppDatabase
import com.biskyt.mytvplayer.data.db.CAT_LIVE
import com.biskyt.mytvplayer.data.db.ChannelEntity
import com.biskyt.mytvplayer.data.db.FavouriteEntity
import com.biskyt.mytvplayer.data.db.RecentEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Which channels a list shows. Zapping in the player follows the same list. */
sealed interface ChannelFilter {
    data object All : ChannelFilter
    data object Favourites : ChannelFilter
    data object Recent : ChannelFilter

    /** Channels whose provider keeps past programmes to watch again. */
    data object Catchup : ChannelFilter

    /** The catch-up channels of one category. */
    data class CatchupCategory(val id: String) : ChannelFilter

    /** A catch-up list (shown on the Catch-up tab, and never remembered for Live TV). */
    val isCatchup: Boolean get() = this == Catchup || this is CatchupCategory
    data class Category(val id: String) : ChannelFilter
}

class ChannelRepository(
    private val db: AppDatabase,
    /** Keeps the user's changes for the sync between TVs. */
    private val log: ChoiceLog = ChoiceLog(db),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val channels = db.channels()
    private val favourites = db.favourites()

    /** Paged so thousands of channels cost only the rows on screen. */
    fun page(playlistId: Long, filter: ChannelFilter, query: String): Flow<PagingData<ChannelEntity>> {
        val q = likeArg(query)
        return Pager(PagingConfig(pageSize = 40, prefetchDistance = 20, initialLoadSize = 60, enablePlaceholders = true)) {
            when (filter) {
                ChannelFilter.All -> channels.pageAll(playlistId, q)
                ChannelFilter.Favourites -> channels.pageFavourites(playlistId, q)
                ChannelFilter.Recent -> channels.pageRecent(playlistId, q)
                ChannelFilter.Catchup -> channels.pageCatchup(playlistId, q)
                is ChannelFilter.CatchupCategory -> channels.pageCatchupCategory(playlistId, filter.id, q)
                is ChannelFilter.Category -> channels.pageCategory(playlistId, filter.id, q)
            }
        }.flow
    }

    /** Ordered stream ids of a list, used for up/down zapping in the player. */
    suspend fun ids(playlistId: Long, filter: ChannelFilter): List<Long> = when (filter) {
        ChannelFilter.All -> channels.idsAll(playlistId)
        ChannelFilter.Favourites -> channels.idsFavourites(playlistId)
        ChannelFilter.Recent -> channels.idsRecent(playlistId)
        ChannelFilter.Catchup -> channels.idsCatchup(playlistId)
        is ChannelFilter.CatchupCategory -> channels.idsCatchupCategory(playlistId, filter.id)
        is ChannelFilter.Category -> channels.idsCategory(playlistId, filter.id)
    }

    suspend fun get(playlistId: Long, streamId: Long) = channels.get(playlistId, streamId)

    suspend fun byNumber(playlistId: Long, number: Int) = channels.byNumber(playlistId, number)

    fun observeCount(playlistId: Long) = channels.observeCount(playlistId)

    /** The categories with catch-up channels, with how many each has. */
    fun observeCatchupCategories(playlistId: Long) = channels.observeCatchupCategories(playlistId)

    fun observeHasCatchup(playlistId: Long) = channels.observeHasCatchup(playlistId).distinctUntilChanged()

    fun observeFavouriteIds(playlistId: Long): Flow<List<Long>> = favourites.observeIds(playlistId)

    fun observeFavourites(playlistId: Long, limit: Int = 12) = channels.observeFavourites(playlistId, limit)

    fun observeRecent(playlistId: Long, limit: Int = 12) = channels.observeRecent(playlistId, limit)

    fun observeHidden(playlistId: Long) = channels.observeHidden(playlistId)

    /** A new favourite goes at the end of the user's order. */
    suspend fun setFavourite(playlistId: Long, streamId: Long, favourite: Boolean) {
        val position = if (favourite) {
            db.withTransaction {
                (favourites.lastPosition(playlistId) + 1).also { favourites.add(FavouriteEntity(playlistId, streamId, clock(), it)) }
            }
        } else {
            favourites.remove(playlistId, streamId)
            null
        }
        log.record(playlistId, ChoiceKinds.FAVOURITE, streamId, position?.toString())
    }

    /**
     * A favourite from another TV (null [position]: not a favourite). A new one goes at the end here, as places differ
     * between TVs; one already here moves to its [position] there (the user put them in a new order).
     */
    suspend fun takeFavourite(playlistId: Long, streamId: Long, position: Long?, at: Long) = db.withTransaction {
        when {
            position == null -> favourites.remove(playlistId, streamId)
            streamId in favourites.ids(playlistId) -> favourites.setPosition(playlistId, streamId, position)
            else -> favourites.add(FavouriteEntity(playlistId, streamId, at, favourites.lastPosition(playlistId) + 1))
        }
    }

    /** The favourite channels' ids, in their order. */
    suspend fun favouriteOrder(playlistId: Long): List<Long> = channels.idsFavourites(playlistId)

    /** Stores favourite channels' places in the order ([positions]: stream id to place). */
    suspend fun setFavouritePositions(playlistId: Long, positions: Map<Long, Long>) {
        db.withTransaction { positions.forEach { (id, position) -> favourites.setPosition(playlistId, id, position) } }
        log.recordOrder(playlistId, ChoiceKinds.FAVOURITE, positions)
    }

    /** Puts a channel on "Recently watched", unless it's restricted and those are hidden there (parental controls). */
    suspend fun markWatched(playlistId: Long, streamId: Long) {
        addRecent(playlistId, streamId, clock())
        // Sent with the next sync, not while the channel plays.
        log.record(playlistId, ChoiceKinds.RECENT, streamId, ChoiceKinds.ON, send = false)
    }

    /** Puts a channel on "Recently watched" as watched [at] (on this TV, or on another TV). */
    suspend fun addRecent(playlistId: Long, streamId: Long, at: Long) = db.withTransaction {
        favourites.touchRecent(RecentEntity(playlistId, streamId, at))
        // Before the trim, so a restricted channel doesn't push out one that is kept.
        db.parental().forgetRecent(playlistId)
        favourites.trimRecent(playlistId, RECENT_LIMIT)
    }

    /** Takes a channel off "Recently watched". */
    suspend fun removeRecent(playlistId: Long, streamId: Long) {
        favourites.removeRecent(playlistId, streamId)
        log.record(playlistId, ChoiceKinds.RECENT, streamId, null)
    }

    /** The channel, unless it's restricted while locked (parental controls). */
    suspend fun getOpen(playlistId: Long, streamId: Long) = channels.getOpen(playlistId, streamId)

    suspend fun setHidden(playlistId: Long, streamId: Long, hidden: Boolean) {
        channels.setHidden(playlistId, streamId, hidden)
        db.categories().refreshCounts(playlistId, CAT_LIVE)
        log.record(playlistId, ChoiceKinds.HIDDEN, streamId, ChoiceKinds.onOrNone(hidden))
    }

    private companion object {
        const val RECENT_LIMIT = 30
    }
}
