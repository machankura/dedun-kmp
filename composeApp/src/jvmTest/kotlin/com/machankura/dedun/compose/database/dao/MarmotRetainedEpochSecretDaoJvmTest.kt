package com.machankura.dedun.compose.database.dao

import androidx.room3.Room
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.MarmotRetainedEpochSecret
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.Profile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Retained epoch secrets are what lets a member read a message sent under an epoch the group
 * has since moved past. Both directions of getting this wrong are bad and neither shows up as
 * an error: keep too few and old messages become permanently unreadable, keep too many and
 * secrets that should have been dropped stay on disk.
 *
 * The whole policy is one strict `<` in a query and an IGNORE on an insert, and nothing else
 * checks either.
 */
class MarmotRetainedEpochSecretDaoJvmTest {

    private val db: DedunDatabase = getRoomDatabase(
        Room.inMemoryDatabaseBuilder<DedunDatabase>()
    )

    @AfterTest
    fun closeDb() = db.close()

    private val user = "a".repeat(64)
    private val roomOne = "11".repeat(32)
    private val roomTwo = "22".repeat(32)

    private suspend fun seedRooms() {
        val nostrEventId = "c".repeat(64)
        db.nostrEventDao().upsert(
            NostrEvent(
                id = nostrEventId,
                pubKey = user,
                kind = 0,
                tags = emptyArray(),
                content = "{}",
                sig = "0".repeat(128),
            )
        )
        db.profileDao().upsert(Profile(publicKey = user, userName = "user", nostrEventId = nostrEventId))
        listOf(roomOne, roomTwo).forEach { id ->
            db.chatRoomDao().upsert(
                ChatRoom(
                    id = id,
                    userPublicKey = user,
                    subject = null,
                    description = null,
                    mlsGroupState = null,
                )
            )
        }
    }

    private suspend fun retain(
        epoch: Long,
        chatRoomId: String = roomOne,
        secret: Byte = 1,
    ) = MarmotRetainedEpochSecret(
        chatRoomId = chatRoomId,
        epoch = epoch,
        senderDataSecret = byteArrayOf(secret),
        encryptionSecret = byteArrayOf(secret),
        leafCount = 2,
    ).also { db.marmotRetainedEpochSecretDao().insert(it) }

    private suspend fun defenestratable(cutoff: Long, chatRoomId: String = roomOne) =
        db.marmotRetainedEpochSecretDao()
            .getDefenestratableMarmotRetainedEpochSecretForChatRoomId(chatRoomId, cutoff)

    /**
     * The cutoff is strict. An epoch equal to it is still inside the retention window and
     * dropping it makes every message sent under that epoch unreadable -- an off-by-one here
     * destroys data rather than merely wasting space.
     */
    @Test
    fun `the epoch on the cutoff is kept and only older ones can be dropped`() = runBlocking {
        seedRooms()
        retain(epoch = 3)
        retain(epoch = 4)
        retain(epoch = 5)

        val droppable = defenestratable(cutoff = 4).map { it.epoch }.toSet()

        assertEquals(setOf(3L), droppable, "only epochs strictly below the cutoff are droppable")
        assertTrue(4L !in droppable, "the epoch on the cutoff is still within the window")
        assertTrue(5L !in droppable)
    }

    @Test
    fun `nothing is droppable when the cutoff precedes every retained epoch`() = runBlocking {
        seedRooms()
        retain(epoch = 7)

        assertTrue(defenestratable(cutoff = 7).isEmpty())
        assertTrue(defenestratable(cutoff = 0).isEmpty())
    }

    /**
     * Rooms advance epochs independently, so a sweep driven by one room's cutoff must never
     * reach another room's secrets. This query feeds a delete, so a leak here is not a stale
     * read -- it is another room losing its history.
     */
    @Test
    fun `one rooms cutoff never reaches another rooms secrets`() = runBlocking {
        seedRooms()
        retain(epoch = 1, chatRoomId = roomOne)
        retain(epoch = 1, chatRoomId = roomTwo)

        val droppable = defenestratable(cutoff = 9, chatRoomId = roomOne)

        assertEquals(1, droppable.size)
        assertTrue(droppable.all { it.chatRoomId == roomOne })
        assertEquals(
            1,
            db.marmotRetainedEpochSecretDao().getMarmotRetainedEpochSecretForChatRoomId(roomTwo).size,
            "the other room's secret must be untouched",
        )
    }

    /**
     * Insert is IGNORE over the composite key (chatRoomId, epoch). Re-processing a commit --
     * a redelivery, or a replay -- must not overwrite the retained secret with a
     * re-derivation, because the stored one is what actually decrypts the messages already on
     * disk.
     */
    @Test
    fun `re-retaining an epoch keeps the secret already stored`() = runBlocking {
        seedRooms()
        retain(epoch = 1, secret = 1)

        retain(epoch = 1, secret = 9)

        val stored = db.marmotRetainedEpochSecretDao()
            .getMarmotRetainedEpochSecretForChatRoomId(roomOne)
        assertEquals(1, stored.size, "the composite key should have kept this to one row")
        assertContentEquals(
            byteArrayOf(1),
            stored.single().encryptionSecret,
            "the original secret was overwritten by a re-derivation",
        )
    }

    /** The same epoch number in two rooms is two different secrets, not a conflict. */
    @Test
    fun `the same epoch in two rooms is retained separately`() = runBlocking {
        seedRooms()
        retain(epoch = 1, chatRoomId = roomOne, secret = 1)
        retain(epoch = 1, chatRoomId = roomTwo, secret = 2)

        assertContentEquals(
            byteArrayOf(1),
            db.marmotRetainedEpochSecretDao()
                .getMarmotRetainedEpochSecretForChatRoomId(roomOne).single().encryptionSecret,
        )
        assertContentEquals(
            byteArrayOf(2),
            db.marmotRetainedEpochSecretDao()
                .getMarmotRetainedEpochSecretForChatRoomId(roomTwo).single().encryptionSecret,
        )
    }

    /** Defenestration removes what the sweep selected and nothing else. */
    @Test
    fun `defenestrating drops only the rows handed to it`() = runBlocking {
        seedRooms()
        retain(epoch = 1)
        retain(epoch = 2)
        val kept = retain(epoch = 3)

        db.marmotRetainedEpochSecretDao().defenestrate(defenestratable(cutoff = 3))

        val remaining = db.marmotRetainedEpochSecretDao()
            .getMarmotRetainedEpochSecretForChatRoomId(roomOne)
        assertEquals(listOf(kept.epoch), remaining.map { it.epoch })
    }

    /**
     * The room's secrets are reachable as a set, which is what a rejoin or a full replay reads
     * before deciding what it can still decrypt.
     */
    @Test
    fun `a rooms retained epochs are all readable`() = runBlocking {
        seedRooms()
        retain(epoch = 1)
        retain(epoch = 2)

        val all = db.marmotRetainedEpochSecretDao().getMarmotRetainedEpochSecretForChatRoomId(roomOne)

        assertEquals(setOf(1L, 2L), all.map { it.epoch }.toSet())
    }
}
