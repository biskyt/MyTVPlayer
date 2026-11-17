package com.biskyt.mytvplayer.data

import com.biskyt.mytvplayer.core.sync.SyncFile
import com.biskyt.mytvplayer.core.sync.SyncStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DriveBackupTest {
    /** The app's folder in Drive: id to name, content and Drive's time. */
    private class Folder : SyncStore {
        data class File(val name: String, val content: ByteArray, val time: String)

        val files = LinkedHashMap<String, File>()
        var clock = 0

        private fun now() = "2026-11-17T10:00:%02d.000Z".format(clock++)

        override suspend fun list() = files.map { (id, f) -> SyncFile(id, f.name, f.time) }

        override suspend fun download(id: String) = files.getValue(id).content

        override suspend fun create(name: String, content: ByteArray) = "${files.size + 1}".also { files[it] = File(name, content, now()) }

        override suspend fun update(id: String, content: ByteArray) {
            files[id] = files.getValue(id).copy(content = content, time = now())
        }

        override suspend fun delete(id: String) {
            files.remove(id)
        }
    }

    private val folder = Folder()
    private val backup = DriveBackup({ folder }) { NOW }

    @Test
    fun `the first backup makes the file, and the next replaces it`() = runBlocking {
        folder.create("progress-tv.json.gz", byteArrayOf(1))
        assertNull(backup.find())

        val first = backup.save("one".toByteArray())
        assertEquals(DriveBackup.NAME, first.file.name)
        val second = backup.save("two".toByteArray())

        assertEquals(first.file.id, second.file.id)
        assertEquals(2, folder.files.size)
        assertEquals("two", backup.read(second).decodeToString())
        assertEquals(NOW, second.modifiedAt)
    }

    @Test
    fun `of two backups (two TVs made one each at the same time), the newest is used`() = runBlocking {
        folder.create(DriveBackup.NAME, "older".toByteArray())
        folder.create(DriveBackup.NAME, "newer".toByteArray())
        val found = backup.find()!!
        assertEquals("newer", backup.read(found).decodeToString())

        backup.save("next".toByteArray())
        assertEquals("next", folder.files.getValue(found.file.id).content.decodeToString())
    }

    private companion object {
        const val NOW = 1_000L
    }
}
