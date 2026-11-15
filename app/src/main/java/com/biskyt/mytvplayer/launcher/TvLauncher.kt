package com.biskyt.mytvplayer.launcher

import android.annotation.SuppressLint
import android.app.Activity
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

    /** Writes [rows]: a row of the app's not in them is taken away. */
    fun publish(rows: LauncherRows): Published

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

    /** The app's rows made and not yet returned as made (a write that failed after making one returns it next time). */
    private val made = mutableSetOf<Long>()

    override fun isOn() = context.packageManager.getComponentEnabledSetting(ComponentName(context, LauncherImageProvider::class.java)) ==
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    /** (The app can ask the TV to show the rows made now: see [askToShow].) One write at a time. */
    @Synchronized
    override fun publish(rows: LauncherRows): Published {
        if (!isOn()) setEnabled(true)
        val images = rows.channels.flatMap { ch -> ch.cards.mapNotNull { it.imageUrl } }
        val pictures = LauncherImages.publish(context, rows.playNext.mapNotNull { it.posterUrl } + images)
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
        // Looked up each time: a launcher can delete them (its data cleared), and then asks for the cards again.
        val own = ownChannels()
        val wanted = rows.channels.map { it.key }.toSet()
        dropChannels(own.filterKeys { it !in wanted }.values)
        val ids = rows.channels.map { channel ->
            val id = own[channel.key]?.also { put(TvContractCompat.buildChannelUri(it), channel.values()) } ?: makeChannel(channel)
            // The launcher puts the heaviest first: the app's own order.
            val weight = channel.cards.withIndex().associate { (i, card) -> card.key to channel.cards.size - i }
            val stored = stored(TvContractCompat.buildPreviewProgramsUriForChannel(id), PREVIEW)
            write(
                plan(stored, channel.cards, {
                    it.key
                }),
                TvContractCompat::buildPreviewProgramUri,
                TvContractCompat.PreviewPrograms.CONTENT_URI
            ) {
                it.values(id, weight.getValue(it.key), pictures)
            }
            id
        }.toSet()
        return Published(ids, (made intersect ids).also { made.clear() })
    }

    /** Its pictures are no longer served either, and the parts the launcher calls are off again. */
    @Synchronized
    override fun clear() {
        stored(TvContractCompat.WatchNextPrograms.CONTENT_URI, WATCH_NEXT).forEach {
            resolver.delete(TvContractCompat.buildWatchNextProgramUri(it.id), null, null)
        }
        dropChannels(ownChannels().values)
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

    /** [channel]'s row, made now (with the app's icon as its logo): its id. */
    private fun makeChannel(channel: LauncherChannel): Long {
        val values = channel.values()
        val uri = resolver.insert(TvContractCompat.Channels.CONTENT_URI, values) ?: error("The TV provider did not make the channel")
        written[uri] = values
        val id = ContentUris.parseId(uri)
        made += id
        runCatching {
            ChannelLogoUtils.storeChannelLogo(context, id, context.packageManager.getApplicationIcon(context.packageName).toBitmap())
        }
        return id
    }

    /** The app's rows [ids] taken off the launcher, with their cards (the TV provider deletes them with them). */
    private fun dropChannels(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        ids.forEach { resolver.delete(TvContractCompat.buildChannelUri(it), null, null) }
        written.clear() // (their cards' rows went with them: the rest are written again once)
    }

    /**
     * The app's rows on the launcher, by key (the provider gives each app only its own). A second row with a key (left by
     * a write that failed) is taken away.
     */
    private fun ownChannels(): Map<String, Long> {
        val rows = resolver.query(
            TvContractCompat.Channels.CONTENT_URI,
            arrayOf(BaseColumns._ID, TvContractCompat.Channels.COLUMN_INTERNAL_PROVIDER_ID),
            null,
            null,
            null
        )?.use { c ->
            buildList { while (c.moveToNext()) add(c.getString(1).orEmpty() to c.getLong(0)) }
        }.orEmpty()
        val own = rows.groupBy({ it.first }, { it.second }).mapValues { it.value.min() }
        dropChannels(rows.map { it.second } - own.values.toSet())
        return own
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

        /**
         * Asks the TV to show the app's rows [channelIds] (those not shown yet) on its home screen, from [activity]. The TV
         * shows the first row an app asks for without a question, so that one while none is shown; it asks the user about
         * the others, through its dialog where the launcher has one (else as for the first).
         */
        fun askToShow(activity: Activity, channelIds: Collection<Long>) {
            val shown = runCatching { activity.shownChannels() }.getOrDefault(emptyMap())
            val dialog = Intent(TvContractCompat.ACTION_REQUEST_CHANNEL_BROWSABLE)
            val hasDialog = dialog.resolveActivity(activity.packageManager) != null
            var first = shown.values.none { it }
            channelIds.filter { shown[it] == false }.forEach { id ->
                runCatching {
                    if (first || !hasDialog) {
                        TvContractCompat.requestChannelBrowsable(activity, id)
                    } else {
                        // The launcher checks who asks: started for a result, which is not read.
                        @Suppress("DEPRECATION")
                        activity.startActivityForResult(Intent(dialog).putExtra(TvContractCompat.EXTRA_CHANNEL_ID, id), 0)
                    }
                }
                first = false
            }
        }

        /** Whether each of the app's rows is on the home screen (the user can hide one), by id. */
        private fun Context.shownChannels(): Map<Long, Boolean> = contentResolver.query(
            TvContractCompat.Channels.CONTENT_URI,
            arrayOf(BaseColumns._ID, TvContractCompat.Channels.COLUMN_BROWSABLE),
            null,
            null,
            null
        )?.use { c -> buildMap { while (c.moveToNext()) put(c.getLong(0), c.getInt(1) == 1) } }.orEmpty()
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
private fun LauncherChannel.values(): ContentValues = Channel.Builder()
    .setType(TvContractCompat.Channels.TYPE_PREVIEW)
    .setDisplayName(name)
    .setAppLinkIntentUri(open.asUri())
    .setInternalProviderId(key)
    .build()
    .toContentValues()

@SuppressLint("RestrictedApi")
private fun RowCard.values(channelId: Long, weight: Int, pictures: Map<String, Uri>): ContentValues = PreviewProgram.Builder()
    .setChannelId(channelId)
    .setType(kind.type())
    .setTitle(name)
    .setWeight(weight)
    // A channel's logo is wide; a film's, series' or episode's poster is portrait.
    .apply { imageUrl?.let(pictures::get)?.let { setPosterArtUri(it).setPosterArtAspectRatio(kind.shape()) } }
    .setIntentUri(open.asUri())
    .setInternalProviderId(key)
    .build()
    .toContentValues()

/** [RowCard.Kind]'s type in the TV provider. */
private fun RowCard.Kind.type() = when (this) {
    RowCard.Kind.CHANNEL -> PreviewProgramColumns.TYPE_CHANNEL
    RowCard.Kind.MOVIE -> PreviewProgramColumns.TYPE_MOVIE
    RowCard.Kind.SERIES -> PreviewProgramColumns.TYPE_TV_SERIES
    RowCard.Kind.EPISODE -> PreviewProgramColumns.TYPE_TV_EPISODE
}

/** [RowCard.Kind]'s picture shape: a channel's logo is wide, a poster portrait. */
private fun RowCard.Kind.shape() =
    if (this == RowCard.Kind.CHANNEL) PreviewProgramColumns.ASPECT_RATIO_16_9 else PreviewProgramColumns.ASPECT_RATIO_2_3
