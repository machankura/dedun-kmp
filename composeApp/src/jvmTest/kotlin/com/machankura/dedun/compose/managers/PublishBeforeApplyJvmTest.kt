package com.machankura.dedun.compose.managers

import androidx.room3.Room
import com.vitorpamplona.quartz.marmot.appComponents.CurrentProfileGroupFactory
import com.vitorpamplona.quartz.marmot.mip03GroupMessages.GroupEvent
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.BroadcastNostrEventRequest
import com.machankura.dedun.compose.database.model.BroadcastStatus
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.MarmotKeyPackage
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.database.repository.DatabaseChatRepository
import com.machankura.dedun.compose.database.repository.DatabaseNostrRepository
import com.machankura.dedun.compose.exceptions.MarmotCommitPendingException
import com.machankura.dedun.compose.nostr.MarmotProfile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Publish before apply, for a current-profile room. docs/marmot-v2.md, Phase 6.
 *
 * The founding Add -- a group that is only its creator -- is the one exception and
 * installs at once. Every commit after it is prepared on a clone, recorded durably,
 * broadcast, and installed only when a relay accepts it; meanwhile a second commit
 * is refused rather than forked, and a restart resumes the obligation from disk.
 */
class PublishBeforeApplyJvmTest {

    private val db: DedunDatabase = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())
    private val scope = CoroutineScope(Dispatchers.Default)
    private val chatRepository = DatabaseChatRepository(db, scope)
    private val nostrRepository = DatabaseNostrRepository(db, scope)

    @AfterTest
    fun closeDb() = db.close()

    private val keyPair = KeyPair()
    private val user = keyPair.pubKey.toHexKey()
    private val roomId = SharedKeyDerivation.marmotGroupId("02" + KeyPair().pubKey.toHexKey())

    private suspend fun seedProfile(publicKey: String) {
        val eventId = publicKey.take(32).padEnd(64, 'f')
        db.nostrEventDao().upsert(NostrEvent(id = eventId, pubKey = publicKey, kind = 0, tags = emptyArray(), content = "{}", sig = "0".repeat(128)))
        db.profileDao().upsert(Profile(publicKey = publicKey, userName = publicKey.take(8), nostrEventId = eventId))
    }

    /** A peer's current-profile package, as the ingest would have stored it. */
    private suspend fun peerPackage(peer: KeyPair): MarmotKeyPackage {
        seedProfile(peer.pubKey.toHexKey())
        val keyPackage = CurrentProfileGroupFactory.createKeyPackage(NostrSignerInternal(peer)).keyPackage
        return MarmotKeyPackage(
            id = peer.pubKey.toHexKey(), publicKey = peer.pubKey.toHexKey(),
            tlsEncodedMarmotKeyPackage = keyPackage.toTlsBytes(), profile = MarmotProfile.CURRENT.wireName,
        )
    }

    /** A current-profile room whose founding Add has landed: two members at epoch 1. */
    private suspend fun establishedRoom(): String {
        seedProfile(user)
        val outcome = MarmotGroupCreation.create(
            database = db, chatRepository = chatRepository, groupId = roomId, name = "#room",
            purpose = "Admins.", adminPublicKeys = setOf(user), userPublicKey = user, keyPair = keyPair,
            profile = MarmotProfile.CURRENT,
        )
        assertIs<MarmotGroupCreation.Outcome.Created>(outcome)
        val first = KeyPair()
        chatRepository.inviteMember(db.chatRoomDao().findChatRoomById(roomId)!!, first.pubKey.toHexKey(), peerPackage(first))
        assertEquals(1L, epochOnDisk(), "the founding Add installs at once")
        return db.marmotRoutingDao().activeRoutingIdFor(roomId)
    }

    private suspend fun epochOnDisk(): Long = db.chatRoomDao().findChatRoomById(roomId)!!.chatRoom.toMlsGroup()!!.epoch

    private suspend fun commitEventFor(peer: KeyPair) =
        db.marmotCommitResultDao().let { dao ->
            db.nostrEventDao().getMLSGroupEvents(since = kotlin.time.Instant.fromEpochSeconds(0), limit = 10)
                .filter { it.kind == GroupEvent.KIND }
                .mapNotNull { event -> dao.getMarmotCommitRequestById(event.id)?.let { event to it } }
                .single { (_, result) -> result.peerKeyPackageEventId == peer.pubKey.toHexKey() }
        }

    @Test
    fun `a commit into an established room waits for a relay before it is applied`() = runBlocking {
        val routingId = establishedRoom()
        val second = KeyPair()

        chatRepository.inviteMember(db.chatRoomDao().findChatRoomById(roomId)!!, second.pubKey.toHexKey(), peerPackage(second))

        // Prepared, recorded, broadcast -- and not applied.
        assertEquals(1L, epochOnDisk(), "the commit was applied before any relay accepted it")
        val (commitEvent, result) = commitEventFor(second)
        val obligationId = assertNotNull(result.obligationId, "the commit carries no obligation")
        assertEquals(1, db.marmotPublishObligationDao().getAll().size)
        assertTrue(db.marmotEngine.gate.pendingFor(routingId).any { it.obligationId == obligationId })
        assertNotNull(db.broadcastNostrEventRequestDao().getFirstBroadcastNostrEventRequestByNostrEventId(commitEvent.id), "nothing queued to broadcast")
        // One Welcome so far: the founding member's. The second's waits for the OK.
        assertEquals(1, db.giftWrapPayloadDao().getAllGiftWrapUnsigned().count { it.chatRoomId == roomId && it.kind == 444 }, "the Welcome went out before the commit was accepted")

        // A second change while the first is unacknowledged is refused, not forked.
        val third = KeyPair()
        val thirdPackage = peerPackage(third)
        assertFailsWith<MarmotCommitPendingException> {
            chatRepository.inviteMember(db.chatRoomDao().findChatRoomById(roomId)!!, third.pubKey.toHexKey(), thirdPackage)
        }

        // The OK: installed, forgotten, and the Welcome queued after it.
        val request = db.broadcastNostrEventRequestDao().getFirstBroadcastNostrEventRequestByNostrEventId(commitEvent.id)!!
        nostrRepository.broadcastProcessed(request, "published")

        assertEquals(2L, epochOnDisk(), "the OK did not install the pending state")
        assertTrue(db.marmotEngine.groupManager.getGroup(routingId)!!.currentMemberIdentities().contains(second.pubKey.toHexKey()))
        assertEquals(0, db.marmotPublishObligationDao().getAll().size)
        assertTrue(db.marmotEngine.gate.canPrepareLocalCommit(routingId))
        assertEquals(2, db.giftWrapPayloadDao().getAllGiftWrapUnsigned().count { it.chatRoomId == roomId && it.kind == 444 }, "no Welcome was queued for the second member")

        // A second OK from another relay changes nothing.
        nostrRepository.broadcastProcessed(request, "published")
        assertEquals(2L, epochOnDisk())
    }

    @Test
    fun `an obligation survives a restart and a late OK installs it`() = runBlocking {
        val routingId = establishedRoom()
        val second = KeyPair()
        chatRepository.inviteMember(db.chatRoomDao().findChatRoomById(roomId)!!, second.pubKey.toHexKey(), peerPackage(second))
        val (_, result) = commitEventFor(second)
        val obligationId = assertNotNull(result.obligationId)

        // A new engine over the same database: what a relaunch builds.
        val rebuilt = MarmotEngine(db)
        rebuilt.ensureRestored()
        assertEquals(1L, epochOnDisk())
        assertTrue(rebuilt.gate.pendingFor(routingId).any { it.obligationId == obligationId }, "the obligation was not restored")
        assertTrue(!rebuilt.gate.canPrepareLocalCommit(routingId))

        rebuilt.confirmPublished(obligationId)

        assertEquals(2L, epochOnDisk())
        assertNull(db.marmotPublishObligationDao().getAll().firstOrNull())
        assertTrue(rebuilt.gate.canPrepareLocalCommit(routingId))
    }

    @Test
    fun `a commit every relay refuses is discarded, and the room can invite again`() = runBlocking {
        val routingId = establishedRoom()
        val second = KeyPair()
        chatRepository.inviteMember(db.chatRoomDao().findChatRoomById(roomId)!!, second.pubKey.toHexKey(), peerPackage(second))
        val (commitEvent, _) = commitEventFor(second)
        // The default scope is one relay; a second row widens it to two, as a room
        // whose relay list names two would have.
        db.broadcastNostrEventRequestDao().insert(listOf(BroadcastNostrEventRequest(nostrEventId = commitEvent.id, relayURL = "wss://second.example")))
        val requests = db.broadcastNostrEventRequestDao().getBroadcastNostrEventRequestsByNostrEventId(commitEvent.id)
        assertEquals(2, requests.size)

        // One relay's refusal decides nothing: the other may still take it.
        nostrRepository.broadcastProcessed(requests[0], BroadcastStatus.REJECTED, reason = "blocked: no")
        assertEquals(1, db.marmotPublishObligationDao().getAll().size)
        assertTrue(!db.marmotEngine.gate.canPrepareLocalCommit(routingId))

        // Nor does a timeout on the other: that is retried with the same bytes.
        nostrRepository.broadcastProcessed(requests[1], BroadcastStatus.FAILED)
        assertEquals(1, db.marmotPublishObligationDao().getAll().size)

        // The other relay refusing too is the end of it: the pending state is
        // dropped, the room is where it was, and it says why.
        nostrRepository.broadcastProcessed(requests[1], BroadcastStatus.REJECTED, reason = "blocked: no")
        assertEquals(0, db.marmotPublishObligationDao().getAll().size)
        assertEquals(1L, epochOnDisk())
        assertTrue(db.marmotEngine.gate.canPrepareLocalCommit(routingId))
        assertTrue(second.pubKey.toHexKey() !in db.marmotEngine.groupManager.getGroup(routingId)!!.currentMemberIdentities())
        val line = db.chatMessageDao().getChatMessagesByChatRoomId(roomId).map { it.chatMessage }
            .last { it.messageType == ChatMessage.TYPE_MEMBER_INVITE_FAILED }
        assertTrue("blocked: no" in line.content, line.content)

        // And the room is free: the same peer can be invited again.
        chatRepository.inviteMember(db.chatRoomDao().findChatRoomById(roomId)!!, second.pubKey.toHexKey(), peerPackage(second))
        assertEquals(1, db.marmotPublishObligationDao().getAll().size)
    }
}
