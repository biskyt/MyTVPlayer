package com.biskyt.mytvplayer.data

import com.biskyt.mytvplayer.data.db.EpgDao
import com.biskyt.mytvplayer.data.db.ProgrammeEntity
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Writes a guide's programmes to the database in batches of [batchSize], on another thread while they are read (see
 * EpgRepository.importProgrammes). The file is unpacked on a third (see EpgRepository.open, and ReadAhead).
 */
internal class ProgrammeWriter(private val epg: EpgDao, private val batchSize: Int) {
    /**
     * Opens the file with [open] and lets [read] go through it, giving its rows to `add`. With [allAtEnd], all are
     * written once the reading is done. Returns how long the writes took, in ms (they overlap with the reading).
     */
    suspend fun write(
        open: () -> InputStream,
        allAtEnd: Boolean,
        read: suspend (InputStream, add: suspend (ProgrammeEntity) -> Unit) -> Unit
    ): Long {
        var savingNs = 0L
        var batch = ArrayList<ProgrammeEntity>(batchSize)
        coroutineScope {
            val writes = Channel<List<ProgrammeEntity>>(WRITES_AHEAD)
            val writer = launch(Dispatchers.IO) {
                for (rows in writes) {
                    val started = System.nanoTime()
                    epg.insertProgrammes(rows)
                    savingNs += System.nanoTime() - started
                }
            }
            try {
                open().use { input ->
                    read(input) { row ->
                        batch += row
                        if (batch.size >= batchSize && !allAtEnd) {
                            writes.send(batch)
                            batch = ArrayList(batchSize)
                        }
                    }
                }
                if (batch.isNotEmpty()) writes.send(batch)
            } finally {
                writes.close()
            }
            writer.join()
        }
        return savingNs / 1_000_000
    }

    private companion object {
        /**
         * Batches read but not yet written, at most: the reading waits for the database beyond this. With the one being
         * written and the one being filled, about 5 MB at most (see PROGRAMME_BATCH).
         */
        const val WRITES_AHEAD = 1
    }
}

/** Counts what is read of a file of [length] bytes and reports the percentage done, for progress messages. */
internal class CountingInputStream(input: InputStream, private val length: Long, private val onPercent: (Int) -> Unit) :
    java.io.FilterInputStream(input) {
    private var read = 0L
    private var reported = -1

    override fun read(): Int = super.read().also { if (it >= 0) count(1) }

    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count(it.toLong()) }

    private var reportedAt = 0L

    private fun count(n: Long) {
        if (length <= 0) return
        read += n
        val percent = (read * 100 / length).toInt().coerceAtMost(100)
        // Every progress message redraws the progress shown: at most twice a second.
        val now = System.currentTimeMillis()
        if (percent != reported && (now - reportedAt >= REPORT_EVERY_MS || percent == 100)) {
            reported = percent
            reportedAt = now
            onPercent(percent)
        }
    }
}

/** How often progress is reported, at most (a file being read, a guide being matched). */
internal const val REPORT_EVERY_MS = 500L
