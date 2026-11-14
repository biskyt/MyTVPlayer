package com.biskyt.mytvplayer.launcher

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.BaseColumns
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import androidx.tvprovider.media.tv.Channel
import androidx.tvprovider.media.tv.ChannelLogoUtils
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.TvContractCompat.PreviewProgramColumns
import androidx.tvprovider.media.tv.WatchNextProgram

/** Writes the cards to the launcher ([TvLauncher]): an interface, so that an app's tests can see what it writes. */
interface LauncherWriter {
    /** Whether the app's cards are on the launcher now (written, and not cleared since): kept by Android. */
    fun isOn(): Boolean

    /** Writes [rows]; the id of the app's channel if it was made now. */
    fun publish(rows: LauncherRows): Long?

    /** Takes everything off the launcher. */
    fun clear()
}

/**
 * Writes the app's cards to the launcher, through Android TV's TV provider: any launcher that reads it shows them
 * (Android TV's own, MyTVLauncher). Each call makes the launcher match [LauncherRows]: cards added, changed and taken
 * away; a card that has not changed is not written again. A card the user took off the launcher stays off until it is
 * watched again (see [plan]). The parts the launcher calls (the picture provider, the receiver) are on only while the
 * cards are: they cost nothing while the rows are off.
 */
class TvLauncher(private val context: Context) : LauncherWriter {
    private val resolver: ContentResolver = context.contentResolver

    /** What each of the app's rows in the TV provider holds, as written last (by its address): not written again. */
    private val written = mutableMapOf<Uri, ContentValues>()

    override fun isOn() = context.packageManager.getComponentEnabledSetting(ComponentName(context, LauncherImageProvider::class.java)) ==
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    /** (The app can ask the TV to show a channel made now: see [askToShow].) One write at a time. */
    @Synchronized
    override fun publish(rows: LauncherRows): Long? {
        if (!isOn()) setEnabled(true)
        val pictures = LauncherImages.publish(context, rows.playNext.mapNotNull { it.posterUrl } + rows.channel.mapNotNull { it.logoUrl })
        val playNext = stored(TvContractCompat.WatchNextPrograms.CONTENT_URI, WATCH_NEXT)
        write(
            plan(playNext, rows.playNext, {
                it.key
            }, { it.watchedAt }),
            TvContractCompat::buildWatchNextProgramUri,
            TvContractCompat.WatchNextPrograms.CONTENT_URI
        ) {
            it.values(pictures)
        }
        val (channel, made) = channel(rows)
        // The launcher puts the heaviest first: the favourites' own order.
        val weight = rows.channel.withIndex().associate { (i, card) -> card.key to rows.channel.size - i }
        write(
            plan(stored(TvContractCompat.buildPreviewProgramsUriForChannel(channel), PREVIEW), rows.channel, {
                it.key
            }),
            TvContractCompat::buildPreviewProgramUri,
            TvContractCompat.PreviewPrograms.CONTENT_URI
        ) {
            it.values(channel, weight.getValue(it.key), pictures)
        }
        return channel.takeIf { made }
    }

    /** Its pictures are no longer served either, and the parts the launcher calls are off again. */
    @Synchronized
    override fun clear() {
        stored(TvContractCompat.WatchNextPrograms.CONTENT_URI, WATCH_NEXT).forEach {
            resolver.delete(TvContractCompat.buildWatchNextProgramUri(it.id), null, null)
        }
        ownChannel()?.let { resolver.delete(TvContractCompat.buildChannelUri(it), null, null) }
        written.clear()
        LauncherImages.clear(context)
        setEnabled(false)
    }

    /** Carries out [plan]: rows at [row] (by id) in [table], holding [values]. */
    private fun <T> write(plan: Plan<T>, row: (Long) -> Uri, table: Uri, values: (T) -> ContentValues) {
        plan.delete.map(row).forEach {
            resolver.delete(it, null, null)
            written.remove(it)
        }
        plan.update.forEach { (id, card) -> put(row(id), values(card)) }
        plan.insert.forEach { card -> values(card).let { v -> resolver.insert(table, v)?.let { written[it] = v } } }
    }

    /** [values] at [uri], unless it holds them already. */
    private fun put(uri: Uri, values: ContentValues) {
        if (written[uri] == values) return
        resolver.update(uri, values, null, null)
        written[uri] = values
    }

    /** The app's channel, made now if there is none (with the app's icon as its logo); whether it was made now. */
    private fun channel(rows: LauncherRows): Pair<Long, Boolean> {
        val values = Channel.Builder()
            .setType(TvContractCompat.Channels.TYPE_PREVIEW)
            .setDisplayName(rows.channelName)
            .setAppLinkIntentUri(rows.channelOpen.asUri())
            .setInternalProviderId(CHANNEL_KEY)
            .build()
            .toContentValues()
        // Looked up each time: a launcher can delete it (its data cleared), and then asks for the cards again.
        ownChannel()?.let { id ->
            put(TvContractCompat.buildChannelUri(id), values)
            return id to false
        }
        val uri = resolver.insert(TvContractCompat.Channels.CONTENT_URI, values) ?: error("The TV provider did not make the channel")
        val id = ContentUris.parseId(uri)
        written[uri] = values
        runCatching {
            ChannelLogoUtils.storeChannelLogo(context, id, context.packageManager.getApplicationIcon(context.packageName).toBitmap())
        }
        return id to true
    }

    private fun ownChannel(): Long? = resolver.query(
        TvContractCompat.Channels.CONTENT_URI,
        arrayOf(BaseColumns._ID, TvContractCompat.Channels.COLUMN_INTERNAL_PROVIDER_ID),
        null,
        null,
        null
    )
        ?.use { c ->
            generateSequence { if (c.moveToNext()) c.getLong(0) to c.getString(1) else null }.firstOrNull {
                it.second ==
                    CHANNEL_KEY
            }?.first
        }

    /** The app's cards at [uri] (the provider gives each app only its own). */
    private fun stored(uri: Uri, columns: Columns): List<StoredCard> {
        val projection = listOfNotNull(BaseColumns._ID, columns.key, columns.browsable, columns.watchedAt).toTypedArray()
        return resolver.query(uri, projection, null, null, null)?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val key = c.getString(1) ?: continue
                    val watchedAt = if (columns.watchedAt == null || c.isNull(3)) 0L else c.getLong(3)
                    add(StoredCard(c.getLong(0), key, removed = c.getInt(2) == 0, watchedAt = watchedAt))
                }
            }
        }.orEmpty()
    }

    /** A table's columns: the app's key, whether the user left it on, and when it was watched (none for a channel card). */
    private class Columns(val key: String, val browsable: String, val watchedAt: String?)

    private fun setEnabled(on: Boolean) {
        val state = if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        listOf(LauncherImageProvider::class.java, LauncherReceiver::class.java).forEach {
            context.packageManager.setComponentEnabledSetting(ComponentName(context, it), state, PackageManager.DONT_KILL_APP)
        }
    }

    companion object {
        /** The app's one channel (a row of its favourite channels). */
        private const val CHANNEL_KEY = "favourites"

        private val WATCH_NEXT = Columns(
            TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID,
            TvContractCompat.WatchNextPrograms.COLUMN_BROWSABLE,
            TvContractCompat.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS
        )
        private val PREVIEW = Columns(
            TvContractCompat.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID,
            TvContractCompat.PreviewPrograms.COLUMN_BROWSABLE,
            // No time watched for a channel card: one the user takes off stays off.
            null
        )

        /** Asks the TV to show the app's channel on its home screen (it asks the user). From an activity. */
        fun askToShow(context: Context, channelId: Long) {
            runCatching { TvContractCompat.requestChannelBrowsable(context, channelId) }
        }
    }
}

private fun Intent.asUri(): Uri = toUri(Intent.URI_INTENT_SCHEME).toUri()

// The builders' setters are public, but declared on a base class that is private to the library, which lint takes for
// a restricted API.
@SuppressLint("RestrictedApi")
private fun PlayNextCard.values(pictures: Map<String, Uri>): ContentValues = WatchNextProgram.Builder()
    .setType(if (episode != null) PreviewProgramColumns.TYPE_TV_EPISODE else PreviewProgramColumns.TYPE_MOVIE)
    .setWatchNextType(
        if (upNext) TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT else TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE
    )
    .setLastEngagementTimeUtcMillis(watchedAt)
    .setTitle(title)
    .apply {
        episode?.season?.let { setSeasonNumber(it) }
        episode?.number?.let { setEpisodeNumber(it) }
        episode?.title?.let { setEpisodeTitle(it) }
        if (durationMs > 0) setDurationMillis(durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        if (!upNext) setLastPlaybackPositionMillis(positionMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        posterUrl?.let(pictures::get)?.let { setPosterArtUri(it).setPosterArtAspectRatio(PreviewProgramColumns.ASPECT_RATIO_2_3) }
    }
    .setIntentUri(open.asUri())
    .setInternalProviderId(key)
    .build()
    .toContentValues()

@SuppressLint("RestrictedApi")
private fun ChannelCard.values(channelId: Long, weight: Int, pictures: Map<String, Uri>): ContentValues = PreviewProgram.Builder()
    .setChannelId(channelId)
    .setType(PreviewProgramColumns.TYPE_CHANNEL)
    .setTitle(name)
    .setWeight(weight)
    .apply { logoUrl?.let(pictures::get)?.let { setPosterArtUri(it).setPosterArtAspectRatio(PreviewProgramColumns.ASPECT_RATIO_16_9) } }
    .setIntentUri(open.asUri())
    .setInternalProviderId(key)
    .build()
    .toContentValues()
