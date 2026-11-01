package com.machankura.dedun.compose.database.dao

import androidx.room3.Room
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.FrostSigningItem
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.GroupTransaction
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.database.model.types.FrostSigningSubject
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * A spend from the group's wallet, as the database holds it: a signing session
 * whose subject is a transaction, one item per input, and the transaction row
 * beside it.
 *
 * The write-once property is the one worth pinning. The PSBT is what every
 * item's sighash was computed from, so a second write at acceptance -- a
 * redelivered proposal, or a different one under the same session id -- must
 * leave the first in place.
 */
class GroupTransactionDaoJvmTest {
    private val db: DedunDatabase = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())

    @AfterTest
    fun closeDb() = db.close()

    private val user = KeyPair().pubKey.toHexKey()
    private val room = "11".repeat(32)

    private suspend fun seedRoom() {
        val nostrEventId = "c".repeat(64)
        db.nostrEventDao().upsert(NostrEvent(id = nostrEventId, pubKey = user, kind = 0, tags = emptyArray(), content = "{}", sig = "0".repeat(128)))
        db.profileDao().upsert(Profile(publicKey = user, userName = "user", nostrEventId = nostrEventId))
        db.chatRoomDao().upsert(ChatRoom(id = room, userPublicKey = user, subject = null, description = null, mlsGroupState = null))
    }

    private suspend fun spend(id: String, inputs: Int = 2): FrostSigningSession {
        val session = FrostSigningSession(
            id = id,
            chatRoomId = room,
            coordinatorPublicKey = user,
            userPublicKey = user,
            dkgSessionId = "dkg-1",
            threshold = 2,
            participantCount = 3,
            signerId = 0,
            subject = FrostSigningSubject.TRANSACTION,
            createdAt = Instant.fromEpochSeconds(1_000),
        )
        db.frostSigningSessionDao().upsert(session)
        db.frostSigningSessionDao().upsertItems(
            (0 until inputs).map { input ->
                FrostSigningItem(
                    sessionId = id,
                    itemIndex = input,
                    unsignedEventJson = """{"change":0,"index":$input}""",
                    eventId = input.toString().repeat(64).take(64),
                    nonceRandom = "${input + 1}".repeat(64).take(64),
                    inputIndex = input,
                )
            }
        )
        return session
    }

    private fun transaction(sessionId: String, psbt: String = "cHNidP8=", txid: String = "ab".repeat(32)) = GroupTransaction(
        frostSigningSessionId = sessionId,
        chatRoomId = room,
        chain = "regtest",
        account = 0,
        psbt = psbt,
        txid = txid,
        createdAt = Instant.fromEpochSeconds(1_000),
    )

    @Test
    fun `a transaction session reads back as one, with an item per input`() = runBlocking {
        seedRoom()
        spend("spend-1")

        val session = assertNotNull(db.frostSigningSessionDao().getSessionById("spend-1"))
        assertEquals(FrostSigningSubject.TRANSACTION, session.subject)

        val items = db.frostSigningSessionDao().getItems("spend-1")
        assertEquals(listOf(0, 1), items.map { it.inputIndex })
        assertEquals("""{"change":0,"index":1}""", items[1].payload)
        assertEquals(items[1].eventId, items[1].message)
    }

    @Test
    fun `an event session written without a subject is an event session`() = runBlocking {
        seedRoom()
        db.frostSigningSessionDao().upsert(
            FrostSigningSession(
                id = "events-1", chatRoomId = room, coordinatorPublicKey = user, userPublicKey = user,
                dkgSessionId = "dkg-1", threshold = 2, participantCount = 3, signerId = 0,
            )
        )

        assertEquals(FrostSigningSubject.EVENTS, db.frostSigningSessionDao().getSessionById("events-1")?.subject)
    }

    @Test
    fun `the proposed psbt is written once`() = runBlocking {
        seedRoom()
        spend("spend-1")

        db.groupTransactionDao().insertIfAbsent(transaction("spend-1", psbt = "first"))
        db.groupTransactionDao().insertIfAbsent(transaction("spend-1", psbt = "second"))

        assertEquals("first", db.groupTransactionDao().getBySessionId("spend-1")?.psbt)
    }

    @Test
    fun `later writes fill the row in`() = runBlocking {
        seedRoom()
        spend("spend-1")
        db.groupTransactionDao().insertIfAbsent(transaction("spend-1"))

        val signed = db.groupTransactionDao().getBySessionId("spend-1")!!
            .copy(signedTransaction = "02000000", broadcastAt = Instant.fromEpochSeconds(2_000))
        db.groupTransactionDao().upsert(signed)

        assertEquals(signed, db.groupTransactionDao().getBySessionId("spend-1"))
        assertEquals(listOf(signed), db.groupTransactionDao().getByChatRoomId(room))
    }

    @Test
    fun `a room's transactions go with it`() = runBlocking {
        seedRoom()
        spend("spend-1")
        db.groupTransactionDao().insertIfAbsent(transaction("spend-1"))

        // @Delete matches on the primary key, so the row as it was seeded is enough.
        db.chatRoomDao().delete(ChatRoom(id = room, userPublicKey = user, subject = null, description = null, mlsGroupState = null))

        assertNull(db.groupTransactionDao().getBySessionId("spend-1"))
        assertEquals(emptyList(), db.frostSigningSessionDao().getItems("spend-1"))
    }
}
