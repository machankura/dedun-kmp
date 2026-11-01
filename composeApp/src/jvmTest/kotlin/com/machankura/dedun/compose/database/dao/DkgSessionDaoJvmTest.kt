package com.machankura.dedun.compose.database.dao

import androidx.room3.Room
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.DkgParticipantMessage
import com.machankura.dedun.compose.database.model.DkgSession
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.database.model.types.DkgRitualStage
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * The state a DKG ritual is resumed from. Two properties here decide whether a ceremony can
 * finish, and neither is visible to the compiler.
 *
 * The first is that a participant gets one message per round, enforced by the composite key
 * (sessionId, participantPublicKey, kind) rather than by any code that writes to it. Rounds
 * advance on `countMessagesByKind` reaching the participant count, so if a redelivered message
 * added a second row instead of replacing the first, the count would reach the threshold with
 * fewer real participants than the ritual requires -- and the ritual would proceed on a set it
 * never actually assembled.
 *
 * The second is that a key-holding session needs *both* the threshold public key and the secret
 * share. A ceremony that stored one and not the other is a failed ceremony, and offering it as
 * a signing key would mean attempting to sign with half a result.
 */
class DkgSessionDaoJvmTest {

    private val db: DedunDatabase = getRoomDatabase(
        Room.inMemoryDatabaseBuilder<DedunDatabase>()
    )

    @AfterTest
    fun closeDb() = db.close()

    private val user = KeyPair().pubKey.toHexKey()
    private val alice = KeyPair().pubKey.toHexKey()
    private val bob = KeyPair().pubKey.toHexKey()
    private val roomOne = "11".repeat(32)
    private val roomTwo = "22".repeat(32)

    private val hostKeyKind = 1
    private val round1Kind = 2

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

    private suspend fun session(
        id: String,
        chatRoomId: String = roomOne,
        createdAt: Instant = Instant.fromEpochSeconds(1_000),
        thresholdPublicKey: String? = null,
        secretShare: String? = null,
        stage: DkgRitualStage = DkgRitualStage.COLLECTING_HOST_KEYS,
    ): DkgSession = DkgSession(
        id = id,
        chatRoomId = chatRoomId,
        coordinatorPublicKey = user,
        userPublicKey = user,
        threshold = 2,
        participantCount = 3,
        stage = stage,
        hostPublicKey = user,
        round1Random = "aa".repeat(32),
        round2AuxRandom = "bb".repeat(32),
        thresholdPublicKey = thresholdPublicKey,
        secretShare = secretShare,
        createdAt = createdAt,
    ).also { db.dkgSessionDao().upsert(it) }

    private suspend fun message(
        sessionId: String,
        participant: String,
        kind: Int = hostKeyKind,
        payload: String = "aa".repeat(32),
        createdAt: Instant = Instant.fromEpochSeconds(1_000),
    ) = db.dkgSessionDao().upsert(
        DkgParticipantMessage(
            sessionId = sessionId,
            participantPublicKey = participant,
            kind = kind,
            payload = payload,
            createdAt = createdAt,
        )
    )

    /**
     * "A group may have abandoned earlier attempts; the live one is the most recent." A resume
     * that picked up an abandoned ritual would wait forever on participants who have moved on
     * to the newer one.
     */
    @Test
    fun `the live ritual for a room is the newest one`() = runBlocking {
        seedRooms()
        session("abandoned", createdAt = Instant.fromEpochSeconds(1_000))
        session("live", createdAt = Instant.fromEpochSeconds(2_000))

        assertEquals("live", db.dkgSessionDao().getLatestSessionForChatRoom(roomOne)?.id)
    }

    @Test
    fun `rituals in another room are not offered as this rooms`() = runBlocking {
        seedRooms()
        session("theirs", chatRoomId = roomTwo, createdAt = Instant.fromEpochSeconds(2_000))
        session("mine", chatRoomId = roomOne, createdAt = Instant.fromEpochSeconds(1_000))

        assertEquals("mine", db.dkgSessionDao().getLatestSessionForChatRoom(roomOne)?.id)
        assertNull(db.dkgSessionDao().getLatestSessionForChatRoom("33".repeat(32)))
    }

    /**
     * Half a ceremony is not a key. Both columns have to be present, because a session holding
     * only a threshold public key never derived a share to sign with, and one holding only a
     * share has no group key to sign against.
     */
    @Test
    fun `only a ceremony that produced both halves counts as key holding`() = runBlocking {
        seedRooms()
        session("complete", thresholdPublicKey = "dd".repeat(32), secretShare = "ee".repeat(32))
        session("keyOnly", thresholdPublicKey = "dd".repeat(32), secretShare = null)
        session("shareOnly", thresholdPublicKey = null, secretShare = "ee".repeat(32))
        session("neither")

        val holding = db.dkgSessionDao().getKeyHoldingSessions().map { it.id }

        assertEquals(listOf("complete"), holding)
    }

    /** Newest first, so the most recent ceremony's key is the one reached for. */
    @Test
    fun `key holding sessions come back newest first`() = runBlocking {
        seedRooms()
        session("older", createdAt = Instant.fromEpochSeconds(1_000), thresholdPublicKey = "dd".repeat(32), secretShare = "ee".repeat(32))
        session("newer", createdAt = Instant.fromEpochSeconds(2_000), thresholdPublicKey = "dd".repeat(32), secretShare = "ee".repeat(32))

        assertEquals(listOf("newer", "older"), db.dkgSessionDao().getKeyHoldingSessions().map { it.id })
    }

    /**
     * The property the round counter depends on. A relay redelivers, and a participant may
     * resend; either way the composite key means the row is replaced, not added. If it were
     * added, `countMessagesByKind` would reach the participant count while one member had
     * still never been heard from.
     */
    @Test
    fun `a resent round message replaces the participants earlier one`() = runBlocking {
        seedRooms()
        session("s1")
        message("s1", alice, payload = "aa".repeat(32))

        message("s1", alice, payload = "bb".repeat(32))

        assertEquals(1, db.dkgSessionDao().countMessagesByKind("s1", hostKeyKind))
        assertEquals(
            "bb".repeat(32),
            db.dkgSessionDao().getMessage("s1", hostKeyKind, alice)?.payload,
            "the resent payload should have replaced the earlier one",
        )
    }

    /** One participant can hold a message in each round without either replacing the other. */
    @Test
    fun `the same participant holds one message per round`() = runBlocking {
        seedRooms()
        session("s1")
        message("s1", alice, kind = hostKeyKind, payload = "aa".repeat(32))
        message("s1", alice, kind = round1Kind, payload = "cc".repeat(32))

        assertEquals(1, db.dkgSessionDao().countMessagesByKind("s1", hostKeyKind))
        assertEquals(1, db.dkgSessionDao().countMessagesByKind("s1", round1Kind))
        assertEquals(
            "aa".repeat(32),
            db.dkgSessionDao().getMessage("s1", hostKeyKind, alice)?.payload,
            "the round-1 message overwrote the host key message",
        )
    }

    /**
     * Ordered by participant public key, not by arrival. Every device has to assemble a round
     * in the same order to compute the same thing, and arrival order differs per device.
     */
    @Test
    fun `a rounds messages are ordered by participant rather than by arrival`() = runBlocking {
        seedRooms()
        session("s1")
        val ordered = listOf(user, alice, bob).sorted()
        // Written in an order deliberately unlike the sorted one.
        message("s1", ordered[2], createdAt = Instant.fromEpochSeconds(1_000))
        message("s1", ordered[0], createdAt = Instant.fromEpochSeconds(2_000))
        message("s1", ordered[1], createdAt = Instant.fromEpochSeconds(3_000))

        val found = db.dkgSessionDao().getMessagesByKind("s1", hostKeyKind).map { it.participantPublicKey }

        assertEquals(ordered, found, "a round must assemble in canonical participant order")
    }

    /** Rounds and sessions are counted apart, which is what makes the counter a round gate. */
    @Test
    fun `messages are counted per session and per round`() = runBlocking {
        seedRooms()
        session("s1")
        session("s2")
        message("s1", alice, kind = hostKeyKind)
        message("s1", bob, kind = hostKeyKind)
        message("s1", alice, kind = round1Kind)
        message("s2", alice, kind = hostKeyKind)

        assertEquals(2, db.dkgSessionDao().countMessagesByKind("s1", hostKeyKind))
        assertEquals(1, db.dkgSessionDao().countMessagesByKind("s1", round1Kind))
        assertEquals(1, db.dkgSessionDao().countMessagesByKind("s2", hostKeyKind))
        assertNull(db.dkgSessionDao().getMessage("s2", round1Kind, alice))
    }
}
