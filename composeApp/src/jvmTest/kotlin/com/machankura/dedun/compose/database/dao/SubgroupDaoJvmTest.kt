package com.machankura.dedun.compose.database.dao

import androidx.room3.Room
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.DkgSession
import com.machankura.dedun.compose.database.model.GroupKeyState
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.database.model.types.DkgRitualStage

/**
 * What schema 17 has to survive, and the one thing it must refuse to do.
 *
 * Four nullable columns say a group is somebody's child. Three properties of
 * them are invisible to the compiler and each one is a way subgroups could break
 * quietly.
 *
 * A parent pointer **may name a room this device does not have**. A member of a
 * subgroup who was never in its parent holds the id and nothing else, so a
 * dangling value is the normal state rather than an error -- and if any of these
 * columns were ever given a foreign key, that member's rows would stop being
 * writable at all.
 *
 * Deleting a parent **must not touch its children**. Every other reference to a
 * room in this schema cascades. A self-referential cascade here would mean a user
 * tidying away a group they had left silently destroying a group they are still
 * in, together with its messages, participants, key state and signed work.
 *
 * And the two `GroupKeyState` columns **travel together**. One without the other
 * is a state whose parentage cannot be rechecked from the row, which is the whole
 * reason the certificate is stored whole rather than as a signature.
 */
class SubgroupDaoJvmTest {

    private val db: DedunDatabase = getRoomDatabase(
        Room.inMemoryDatabaseBuilder<DedunDatabase>()
    )

    @AfterTest
    fun closeDb() = db.close()

    private val user = KeyPair().pubKey.toHexKey()

    private val parentRoom = "11".repeat(32)
    private val childRoom = "22".repeat(32)

    /** A parent this device has never held a room for. */
    private val absentParent = "33".repeat(32)

    private val certificate =
        """{"id":"aa","pubkey":"bb","created_at":1700000000,"kind":30329,"tags":[],"content":"cc","sig":"dd"}"""

    @Test
    fun `a key state keeps its parent and the certificate behind it`() = runBlocking {
        seedRooms()

        db.groupKeyStateDao().upsert(
            keyState(
                chatRoomId = childRoom,
                parentChatRoomId = parentRoom,
                birthCertificateJson = certificate
            )
        )

        val stored = db.groupKeyStateDao().getByChatRoomId(childRoom)

        assertEquals(parentRoom, stored?.parentChatRoomId)
        assertEquals(certificate, stored?.birthCertificateJson)
    }

    @Test
    fun `a group that is nobody's child keeps both columns null`() = runBlocking {
        seedRooms()

        db.groupKeyStateDao().upsert(keyState(chatRoomId = parentRoom))

        val stored = db.groupKeyStateDao().getByChatRoomId(parentRoom)

        // Every group made before subgroups existed reads back this way, and so
        // does every top-level group made after. Null here is not missing data.
        assertNull(stored?.parentChatRoomId)
        assertNull(stored?.birthCertificateJson)
    }

    @Test
    fun `a key state may name a parent this device holds no room for`() = runBlocking {
        seedRooms()

        // The position a subgroup member who was never in the parent is in: they
        // hold the id off a certificate and nothing else. If this column were a
        // foreign key their state could not be written at all.
        db.groupKeyStateDao().upsert(
            keyState(
                chatRoomId = childRoom,
                parentChatRoomId = absentParent,
                birthCertificateJson = certificate
            )
        )

        assertEquals(absentParent, db.groupKeyStateDao().getByChatRoomId(childRoom)?.parentChatRoomId)
        assertNull(db.chatRoomDao().findChatRoomById(absentParent))
    }

    @Test
    fun `a room may name a parent this device holds no room for`() = runBlocking {
        seedRooms()

        db.chatRoomDao().upsert(room(childRoom).copy(parentChatRoomId = absentParent))

        assertEquals(
            absentParent,
            db.chatRoomDao().findChatRoomById(childRoom)?.chatRoom?.parentChatRoomId
        )
    }

    @Test
    fun `deleting a parent room leaves its subgroup standing`() = runBlocking {
        seedRooms()

        db.chatRoomDao().upsert(room(childRoom).copy(parentChatRoomId = parentRoom))
        db.groupKeyStateDao().upsert(
            keyState(
                chatRoomId = childRoom,
                parentChatRoomId = parentRoom,
                birthCertificateJson = certificate
            )
        )

        db.chatRoomDao().delete(room(parentRoom))

        // The child survives with its lineage intact and pointing at a room that
        // is gone, which is exactly the dangling-reference case above. A cascade
        // here would have taken the room, its key state and everything either of
        // them owns.
        assertNull(db.chatRoomDao().findChatRoomById(parentRoom))
        assertEquals(
            parentRoom,
            db.chatRoomDao().findChatRoomById(childRoom)?.chatRoom?.parentChatRoomId
        )
        assertEquals(parentRoom, db.groupKeyStateDao().getByChatRoomId(childRoom)?.parentChatRoomId)
    }

    @Test
    fun `a parent lists the states of the children it certified, newest first`() = runBlocking {
        seedRooms()

        val second = "44".repeat(32)
        db.chatRoomDao().upsert(room(second))

        db.groupKeyStateDao().upsert(
            keyState(
                chatRoomId = childRoom,
                parentChatRoomId = parentRoom,
                birthCertificateJson = certificate,
                announcedAt = Instant.fromEpochSeconds(1_000)
            )
        )
        db.groupKeyStateDao().upsert(
            keyState(
                chatRoomId = second,
                parentChatRoomId = parentRoom,
                birthCertificateJson = certificate,
                announcedAt = Instant.fromEpochSeconds(2_000)
            )
        )
        // Another group's child, to prove the filter is on the parent rather
        // than on having a parent at all.
        db.groupKeyStateDao().upsert(
            keyState(
                chatRoomId = parentRoom,
                parentChatRoomId = absentParent,
                birthCertificateJson = certificate
            )
        )

        assertEquals(
            listOf(second, childRoom),
            db.groupKeyStateDao().getByParentChatRoomId(parentRoom).map { it.chatRoomId }
        )
        assertEquals(
            listOf(second, childRoom),
            db.groupKeyStateDao().observeByParentChatRoomId(parentRoom).first().map { it.chatRoomId }
        )
    }

    @Test
    fun `a parent lists only the subgroup rooms this device actually holds`() = runBlocking {
        seedRooms()

        db.chatRoomDao().upsert(room(childRoom).copy(parentChatRoomId = parentRoom))

        val rooms = db.chatRoomDao().observeByParentChatRoomId(parentRoom).first()

        assertEquals(listOf(childRoom), rooms.map { it.chatRoom.id })

        // A child that was certified but whose room was never created here shows
        // up nowhere in this query. That is the difference between it and
        // SubgroupManager.subgroupsOf, which reads the parent's certificates.
        assertTrue(db.chatRoomDao().observeByParentChatRoomId(absentParent).first().isEmpty())
    }

    @Test
    fun `a soft-deleted subgroup drops out of its parent's list`() = runBlocking {
        seedRooms()

        db.chatRoomDao().upsert(room(childRoom).copy(parentChatRoomId = parentRoom))
        db.chatRoomDao().upsert(
            room(childRoom).copy(
                parentChatRoomId = parentRoom,
                deletedAt = Instant.fromEpochSeconds(9_000)
            )
        )

        // A room the user has cleared away must not come back because its parent
        // still lists it.
        assertTrue(db.chatRoomDao().observeByParentChatRoomId(parentRoom).first().isEmpty())
    }

    @Test
    fun `a ceremony remembers which group it was opened to make a subgroup of`() = runBlocking {
        seedRooms()

        db.dkgSessionDao().upsert(session("s1", parentChatRoomId = parentRoom))
        db.dkgSessionDao().upsert(session("s2"))

        // Unverified, and read for nothing but a screen's title and a way back
        // into an unfinished flow -- but it has to survive a round trip or there
        // is no way back at all.
        assertEquals(
            listOf("s1"),
            db.dkgSessionDao().getByParentChatRoomId(parentRoom).map { it.id }
        )
        assertNull(db.dkgSessionDao().getSessionById("s2")?.parentChatRoomId)
    }

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
        db.profileDao().upsert(
            Profile(publicKey = user, userName = "user", nostrEventId = nostrEventId)
        )
        listOf(parentRoom, childRoom).forEach { db.chatRoomDao().upsert(room(it)) }
    }

    private fun room(id: String) = ChatRoom(
        id = id,
        userPublicKey = user,
        subject = null,
        description = null,
        mlsGroupState = null,
    )

    private fun keyState(
        chatRoomId: String,
        parentChatRoomId: String? = null,
        birthCertificateJson: String? = null,
        announcedAt: Instant = Instant.fromEpochSeconds(1_700_000_000)
    ) = GroupKeyState(
        chatRoomId = chatRoomId,
        dkgSessionId = "ceremony-1",
        thresholdPublicKey = "02" + "ab".repeat(32),
        derivationPath = "m/9420/0/0",
        announcedBy = user,
        announcedAt = announcedAt,
        parentChatRoomId = parentChatRoomId,
        birthCertificateJson = birthCertificateJson,
    )

    private fun session(
        id: String,
        chatRoomId: String = parentRoom,
        parentChatRoomId: String? = null
    ) = DkgSession(
        id = id,
        chatRoomId = chatRoomId,
        coordinatorPublicKey = user,
        userPublicKey = user,
        threshold = 2,
        participantCount = 3,
        stage = DkgRitualStage.COLLECTING_HOST_KEYS,
        hostPublicKey = user,
        round1Random = "aa".repeat(32),
        round2AuxRandom = "bb".repeat(32),
        parentChatRoomId = parentChatRoomId,
    )
}
