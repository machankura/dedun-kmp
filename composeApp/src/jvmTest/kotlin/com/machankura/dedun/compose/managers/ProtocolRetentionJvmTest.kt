package com.machankura.dedun.compose.managers

import androidx.room3.Room
import com.vitorpamplona.quartz.marmot.mip02Welcome.WelcomeEvent
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.DkgSession
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.GiftWrapPayload
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.types.DkgRitualStage
import com.machankura.dedun.compose.database.model.types.FrostSigningStage
import com.machankura.dedun.compose.database.repository.DisappearingSettingsStore
import com.machankura.dedun.compose.nostr.DisappearingMessages
import com.machankura.dedun.compose.nostr.DisappearingMessages.DAY
import com.machankura.dedun.compose.nostr.dkg.DkgRitualEvents
import com.machankura.dedun.compose.nostr.frost.FrostSigningEvents

/**
 * docs/disappearing-messages.md, Phase 5: a ceremony's rounds outlive their expiry exactly as long
 * as the session they belong to is still open, and no longer.
 */
class ProtocolRetentionJvmTest {

    private val db: DedunDatabase = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())

    @AfterTest
    fun closeDb() = db.close()

    private val user = KeyPair().pubKey.toHexKey()
    private val alice = KeyPair().pubKey.toHexKey()
    private val dkgId = "d".repeat(64)
    private val signingId = "5".repeat(64)

    private suspend fun seedProfile(publicKey: String) {
        val nostrEventId = publicKey.take(63) + "f"
        db.nostrEventDao().upsert(NostrEvent(id = nostrEventId, pubKey = publicKey, kind = 0, tags = emptyArray(), content = "{}", sig = "0".repeat(128)))
        db.profileDao().upsert(Profile(publicKey = publicKey, userName = "member", nostrEventId = nostrEventId))
    }

    private suspend fun room(): LocalChatRoom {
        seedProfile(user)
        seedProfile(alice)
        return assertNotNull(db.nostrNip17Dao().createNip17ChatRoom(user, listOf(alice)))
    }

    private fun dkg(room: LocalChatRoom, stage: DkgRitualStage) = DkgSession(
        id = dkgId,
        chatRoomId = room.chatRoom.id,
        coordinatorPublicKey = user,
        userPublicKey = user,
        threshold = 2,
        participantCount = 2,
        stage = stage,
        hostPublicKey = user,
        round1Random = "aa".repeat(32),
        round2AuxRandom = "bb".repeat(32),
    )

    private fun signing(room: LocalChatRoom, stage: FrostSigningStage) = FrostSigningSession(
        id = signingId,
        chatRoomId = room.chatRoom.id,
        coordinatorPublicKey = user,
        userPublicKey = user,
        dkgSessionId = dkgId,
        threshold = 2,
        participantCount = 2,
        signerId = 0,
        stage = stage,
    )

    /** A round queued two weeks ago, so it is past even the ceremony floor. */
    private suspend fun expiredRound(room: LocalChatRoom, kind: Int, sessionTags: Array<Array<String>>): GiftWrapPayload {
        val createdAt = Clock.System.now().epochSeconds - 14 * DAY
        val tags = DisappearingSettingsStore(db).stamp(room.chatRoom.id, user, kind, createdAt, arrayOf(arrayOf("p", alice)) + sessionTags)
        val payload = GiftWrapPayload(
            id = EventHasher.hashId(user, createdAt, kind, tags, "round"),
            publicKey = user, chatRoomId = room.chatRoom.id, kind = kind,
            tags = tags, content = "round", createdAt = Instant.fromEpochSeconds(createdAt),
        )
        db.giftWrapPayloadDao().upsert(payload)
        return payload
    }

    @Test
    fun `a key ceremony's round stays while the ceremony runs, and goes once it is complete`(): Unit = runBlocking {
        val room = room()
        db.dkgSessionDao().upsert(dkg(room, DkgRitualStage.COLLECTING_ROUND_1))
        val round = expiredRound(room, DkgRitualEvents.ROUND_1, DkgRitualEvents.assembleTags(sessionId = dkgId))

        assertFalse(ProtocolRetention.isReleased(db, round))
        assertEquals(0, DisappearingMessageSweeper.sweep(db, ProtocolRetention).payloads)
        assertNotNull(db.giftWrapPayloadDao().getById(round.id))

        db.dkgSessionDao().upsert(dkg(room, DkgRitualStage.COMPLETE))
        assertEquals(1, DisappearingMessageSweeper.sweep(db, ProtocolRetention).payloads)
        assertNull(db.giftWrapPayloadDao().getById(round.id))
        assertEquals(DkgRitualStage.COMPLETE, assertNotNull(db.dkgSessionDao().getSessionById(dkgId)).stage, "the ceremony's result went with its rounds")
    }

    @Test
    fun `a signing round stays while signing runs, and goes once it has failed`(): Unit = runBlocking {
        val room = room()
        db.dkgSessionDao().upsert(dkg(room, DkgRitualStage.COMPLETE))
        db.frostSigningSessionDao().upsert(signing(room, FrostSigningStage.COLLECTING_NONCES))
        val nonce = expiredRound(room, FrostSigningEvents.NONCE, FrostSigningEvents.assembleTags(sessionId = signingId))

        assertFalse(ProtocolRetention.isReleased(db, nonce))
        db.frostSigningSessionDao().upsert(signing(room, FrostSigningStage.FAILED))
        assertTrue(ProtocolRetention.isReleased(db, nonce))
    }

    @Test
    fun `a round no session here will read is released at its expiry`(): Unit = runBlocking {
        val room = room()
        val stray = expiredRound(room, FrostSigningEvents.NONCE, FrostSigningEvents.assembleTags(sessionId = signingId))
        assertTrue(ProtocolRetention.isReleased(db, stray))
    }

    @Test
    fun `a Welcome and what a person wrote are released at their expiry`(): Unit = runBlocking {
        val room = room()
        assertTrue(ProtocolRetention.isReleased(db, expiredRound(room, WelcomeEvent.KIND, emptyArray())))
        assertTrue(ProtocolRetention.isReleased(db, expiredRound(room, 14, emptyArray())))
        assertEquals(DisappearingMessages.ExpiryClass.Written, DisappearingMessages.classOf(14))
    }
}
