package com.machankura.dedun.compose.database

import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import androidx.room3.useWriterConnection
import com.machankura.dedun.compose.database.builder.SecureDeleteDriver
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.DkgSession
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.database.model.types.DkgRitualStage
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * A share this device deletes has to be gone from `aux.db`, not only from the query results.
 *
 * Rotating a group's shares (docs/frost-share-refresh.md) ends with each device deleting its old
 * one, and the whole value of the rotation rests on that delete. SQLite does not, by default,
 * erase what it frees: an overwritten column's old bytes stay in the page, and in WAL mode a whole
 * page image of the write that put them there stays in `-wal`. These tests read the database's
 * files byte by byte for the old share's hex.
 *
 * The control matters as much as the claim. Without it, a test that looks for bytes in the wrong
 * file, or after the wrong step, would pass for the same reason it passes when the delete works.
 */
class SecureDeleteJvmTest {

    private val directory: File = Files.createTempDirectory("secure-delete").toFile()

    @AfterTest
    fun removeDirectory() {
        directory.deleteRecursively()
    }

    private val user = KeyPair().pubKey.toHexKey()
    private val room = "11".repeat(32)

    /** A share no other bytes in the file could happen to equal. */
    private val share = Random.nextBytes(32).toHexKey()

    private fun session(secretShare: String?) = DkgSession(
        id = "ceremony",
        chatRoomId = room,
        coordinatorPublicKey = user,
        userPublicKey = user,
        threshold = 2,
        participantCount = 3,
        stage = DkgRitualStage.COMPLETE,
        hostPublicKey = user,
        round1Random = "aa".repeat(32),
        round2AuxRandom = "bb".repeat(32),
        thresholdPublicKey = "02" + "cc".repeat(32),
        secretShare = secretShare,
        createdAt = Instant.fromEpochSeconds(1_000),
    )

    private suspend fun seed(db: DedunDatabase) {
        val nostrEventId = "c".repeat(64)
        db.nostrEventDao().upsert(
            NostrEvent(id = nostrEventId, pubKey = user, kind = 0, tags = emptyArray(), content = "{}", sig = "0".repeat(128))
        )
        db.profileDao().upsert(Profile(publicKey = user, userName = "user", nostrEventId = nostrEventId))
        db.chatRoomDao().upsert(ChatRoom(id = room, userPublicKey = user, subject = null, description = null, mlsGroupState = null))
    }

    private fun builder(): RoomDatabase.Builder<DedunDatabase> =
        Room.databaseBuilder<DedunDatabase>(name = File(directory, "aux.db").absolutePath)

    /** Every byte SQLite keeps for this database: the file, its write-ahead log and its index. */
    private fun bytesOnDisk(): String =
        directory.listFiles().orEmpty()
            .filter { it.name.startsWith("aux.db") }
            .joinToString("") { it.readBytes().toString(Charsets.ISO_8859_1) }

    /**
     * Writes the share, then gets rid of it: by overwriting the column with null, which is what a
     * wipe does, or by deleting the row.
     *
     * Both, because they free different things. An overwrite drops the old cell and writes a
     * shorter one, and where SQLite places the new cell decides whether the old share's bytes
     * happen to be written over -- in this schema they are, so an overwrite cannot serve as the
     * control. A deleted row's cell is freed and nothing is written over it, so without
     * secure_delete its bytes stay in the page for certain.
     */
    private suspend fun storeThenDelete(db: DedunDatabase, deleteRow: Boolean = false) {
        seed(db)
        db.dkgSessionDao().upsert(session(secretShare = share))
        assertEquals(share, db.dkgSessionDao().getSessionById("ceremony")?.secretShare)
        if (deleteRow) {
            db.useWriterConnection { transactor ->
                transactor.usePrepared("DELETE FROM DkgSession") { it.step() }
            }
        } else {
            db.dkgSessionDao().upsert(session(secretShare = null))
        }
    }

    @Test
    fun `a deleted share leaves no bytes in the database files`() = runBlocking {
        val db = getRoomDatabase(builder())
        storeThenDelete(db)
        db.scrubFreedPages()

        // Read while the database is open, which is how it is on a phone: a close checkpoints
        // and removes the log, which would hide exactly what the scrub is for.
        assertFalse(share in bytesOnDisk(), "the deleted share is still in the database files")
        db.close()
    }

    @Test
    fun `a deleted row leaves no bytes in the database files`() = runBlocking {
        val db = getRoomDatabase(builder())
        storeThenDelete(db, deleteRow = true)
        db.scrubFreedPages()

        assertFalse(share in bytesOnDisk(), "the deleted row's share is still in the database files")
        db.close()
    }

    @Test
    fun `secure delete alone leaves the share in the write-ahead log`() = runBlocking {
        val db = getRoomDatabase(builder())
        storeThenDelete(db)

        assertTrue(share in bytesOnDisk(), "nothing to scrub, so the scrub above proves nothing")
        db.scrubFreedPages()
        assertFalse(share in bytesOnDisk())
        db.close()
    }

    @Test
    fun `with secure delete off, a deleted row leaves the share in the page`() = runBlocking {
        val db = builder()
            .setDriver(SecureDeleteOff(BundledSQLiteDriver()))
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        storeThenDelete(db, deleteRow = true)
        db.scrubFreedPages()

        assertTrue(share in bytesOnDisk(), "the control found nothing, so secure_delete is not what the first test shows")
        db.close()
    }

    /** The control's driver: the same as the app's but for the one pragma, set the other way. */
    private class SecureDeleteOff(private val delegate: SQLiteDriver) : SQLiteDriver {
        override val hasConnectionPool: Boolean get() = delegate.hasConnectionPool
        override fun open(fileName: String): SQLiteConnection =
            delegate.open(fileName).also { it.execSQL("PRAGMA secure_delete = OFF") }
    }

    @Test
    fun `every connection the driver opens has secure delete on`() {
        val driver = SecureDeleteDriver(BundledSQLiteDriver())
        val path = File(directory, "pragma.db").absolutePath
        val connections = List(3) { driver.open(path) }
        try {
            connections.forEach { connection ->
                val on = connection.prepare("PRAGMA secure_delete").use { statement ->
                    statement.step()
                    statement.getLong(0)
                }
                assertEquals(1L, on)
            }
        } finally {
            connections.forEach { it.close() }
        }
    }
}
