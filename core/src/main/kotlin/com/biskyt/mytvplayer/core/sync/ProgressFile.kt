package com.biskyt.mytvplayer.core.sync

import com.biskyt.mytvplayer.core.xtream.XtreamCredentials
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** A film or an episode in one playlist. [playlist] is [ProgressFile.playlistKey], the same on every TV. */
data class SyncKey(val playlist: String, val kind: String, val id: Long)

/** How far a film or an episode got on a TV, at [updatedAt]. The fields match the app's saved progress. */
@Serializable
data class SyncedProgress(
    val playlist: String,
    val kind: String,
    val id: Long,
    val position: Long,
    val duration: Long,
    val updatedAt: Long,
    val title: String,
    val image: String? = null,
    val seriesId: Long? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val hasNext: Boolean = false,
) {
    val key: SyncKey get() = SyncKey(playlist, kind, id)
}

/** A TV forgot how far a film or an episode got, at [deletedAt]. */
@Serializable
data class SyncedDelete(val playlist: String, val kind: String, val id: Long, val deletedAt: Long) {
    val key: SyncKey get() = SyncKey(playlist, kind, id)
}

/**
 * A choice the user made on a TV, at [at]: a favourite, a hidden channel, a ticked category, a guide, for example.
 * [kind] says what it is, [id] which item (the provider's id, the same on every TV), and [value] the choice (null: none,
 * such as a favourite removed). The app gives the kinds and values their meaning.
 */
@Serializable
data class SyncedChoice(val playlist: String, val kind: String, val id: String, val value: String?, val at: Long) {
    val key: ChoiceKey get() = ChoiceKey(playlist, kind, id)
}

/** An item that a choice is about, in one playlist. [playlist] is [ProgressFile.playlistKey], the same on every TV. */
data class ChoiceKey(val playlist: String, val kind: String, val id: String)

/**
 * One TV's watch progress and choices, as it keeps it in the sync folder. Each TV writes only its own file, so two TVs never write
 * the same file. The other TVs read it and keep the newest change to each item.
 *
 * A newer app version can add fields: older versions ignore them.
 */
@Serializable
data class ProgressFile(
    val version: Int = VERSION,
    val rows: List<SyncedProgress> = emptyList(),
    val deleted: List<SyncedDelete> = emptyList(),
    /** Since 1.12.0: the user's choices on this TV (favourites, ticked categories and more). Not the lists themselves. */
    val choices: List<SyncedChoice> = emptyList(),
) {
    /** Compressed JSON. */
    fun encode(): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(json.encodeToString(serializer(), this).toByteArray()) }
        return out.toByteArray()
    }

    /** The newest change to one item: a row, or a delete. */
    sealed interface Change {
        val at: Long
    }

    data class Saved(val row: SyncedProgress) : Change {
        override val at get() = row.updatedAt
    }

    data class Deleted(val delete: SyncedDelete) : Change {
        override val at get() = delete.deletedAt
    }

    companion object {
        const val VERSION = 1
        private const val PREFIX = "progress-"

        /** Query parameters that hold a login in an M3U address. */
        private val LOGIN = listOf("username", "password", "user", "pass")
        private val json = Json { ignoreUnknownKeys = true }

        /** This TV's file in the sync folder. */
        fun fileName(device: String) = "$PREFIX$device.json.gz"

        fun isProgressFile(name: String) = name.startsWith(PREFIX)

        fun decode(bytes: ByteArray): ProgressFile = try {
            json.decodeFromString(serializer(), GZIPInputStream(bytes.inputStream()).use { String(it.readBytes()) })
        } catch (e: IOException) {
            throw SyncException("Unreadable progress file", e)
        } catch (e: SerializationException) {
            throw SyncException("Unreadable progress file", e)
        }

        /**
         * The newest change to each item in [files]. When a delete and a save have the same time, the delete wins.
         */
        fun newest(files: List<ProgressFile>): Map<SyncKey, Change> {
            val out = HashMap<SyncKey, Change>()
            fun offer(key: SyncKey, change: Change) {
                val old = out[key]
                if (old == null || change.at > old.at || (change.at == old.at && change is Deleted)) out[key] = change
            }
            files.forEach { f ->
                f.rows.forEach { offer(it.key, Saved(it)) }
                f.deleted.forEach { offer(it.key, Deleted(it)) }
            }
            return out
        }

        /** The newest choice for each item in [files]. */
        fun newestChoices(files: List<ProgressFile>): Map<ChoiceKey, SyncedChoice> =
            files.flatMap { it.choices }.groupBy { it.key }.mapValues { (_, choices) -> choices.maxBy { it.at } }

        /**
         * The version of [playlistKey]. When it changes, each TV sends its file again once, so that the TVs match with
         * the new keys. 2 (1.10.2): the scheme and the default port are ignored. 3 (1.11.0): the playlist's name in
         * place of its user name.
         */
        const val KEY_FORMAT = 3

        /**
         * The same playlist on every TV: the same kind, server and [name]. Hashed, so the file holds no name or server
         * address.
         * - The name tells apart playlists for the same account (one for each person in a family, each with their own
         *   progress). Capitals and spaces at the ends do not matter.
         * - The user name and password are not part of it: a renewed account often has a new user name, and its
         *   progress stays.
         * - The server is the same whatever the scheme and with or without its default port: "http://example.tv:80/",
         *   "https://example.tv" and "example.tv" match.
         */
        fun playlistKey(kind: String, server: String, name: String): String {
            val identity = listOf(kind, address(server), name.trim().lowercase()).joinToString("\n")
            return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }

        /**
         * [server] without its scheme, without its port when that is the scheme's default (80, 443), and without a login in
         * its query (an M3U address such as get.php?username=…&password=…).
         */
        private fun address(server: String): String {
            val base = XtreamCredentials(server, "", "").base
            val parsed = base.toHttpUrlOrNull() ?: return base.lowercase()
            val url = LOGIN.fold(parsed.newBuilder()) { b, name -> b.removeAllQueryParameters(name) }.build()
            val port = if (url.port == HttpUrl.defaultPort(url.scheme)) "" else ":${url.port}"
            val query = url.encodedQuery?.let { "?$it" }.orEmpty()
            // The host is in lower case already. A path or query (an M3U address) keeps its case.
            return url.host + port + url.encodedPath.trimEnd('/') + query
        }
    }
}
