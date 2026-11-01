package com.machankura.dedun.compose.managers

import androidx.room3.Room
import com.vitorpamplona.quartz.marmot.GroupEventResult
import com.vitorpamplona.quartz.marmot.mip02Welcome.WelcomeEvent
import com.vitorpamplona.quartz.marmot.mip03GroupMessages.GroupEvent
import com.vitorpamplona.quartz.marmot.mip03GroupMessages.GroupEventEncryption
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.MarmotKeyPackage
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.database.repository.DatabaseChatRepository
import com.machankura.dedun.compose.database.repository.DatabaseMarmotRepository
import com.machankura.dedun.compose.database.repository.DatabaseNostrRepository
import com.machankura.dedun.compose.nostr.MarmotProfile
import kotlin.io.encoding.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Leaving, removing and disbanding a current-profile room, with quartz's own
 * managers standing in for the other member. docs/marmot-v2.md, Phase 7.
 *
 * The app administers the room in every case: it creates the room, invites the
 * stand-in (the founding Add, installed at once), and the stand-in joins from the
 * Welcome bytes the app queued. Every governance commit then goes through the
 * publish gate, and the relay's OK is played by hand.
 */
class GroupLifecycleJvmTest {

    private val db: DedunDatabase = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())
    private val scope = CoroutineScope(Dispatchers.Default)
    private val chatRepository = DatabaseChatRepository(db, scope)
    private val nostrRepository = DatabaseNostrRepository(db, scope)

    @AfterTest
    fun closeDb() = db.close()

    private val keyPair = KeyPair()
    private val user = keyPair.pubKey.toHexKey()
    private val roomId = SharedKeyDerivation.marmotGroupId("02" + KeyPair().pubKey.toHexKey())
    private val relay = "wss://relay.example"

    private val member = MarmotStandIn()

    private suspend fun seedProfile(publicKey: String) {
        val eventId = publicKey.take(32).padEnd(64, 'f')
        db.nostrEventDao().upsert(NostrEvent(id = eventId, pubKey = publicKey, kind = 0, tags = emptyArray(), content = "{}", sig = "0".repeat(128)))
        db.profileDao().upsert(Profile(publicKey = publicKey, userName = publicKey.take(8), nostrEventId = eventId))
    }

    private suspend fun store(event: Event) = db.nostrDao().storeNostrEvent(
        nostrEvent = NostrEvent(
            id = event.id, pubKey = event.pubKey, kind = event.kind, tags = event.tags,
            content = event.content, sig = event.sig, createdAt = Instant.fromEpochSeconds(event.createdAt),
        ),
        relayURL = relay, synchronizationRelayURLs = listOf(relay), level = 0, activeKeyPair = keyPair,
    )

    private suspend fun room() = db.chatRoomDao().findChatRoomById(roomId)!!
    private suspend fun transcript() = db.chatMessageDao().getChatMessagesByChatRoomId(roomId).map { it.chatMessage }

    /** The app's room with the stand-in in it, both sides at epoch 1. */
    private suspend fun roomWithMember(): String {
        seedProfile(user)
        seedProfile(member.hex)
        assertIs<MarmotGroupCreation.Outcome.Created>(
            MarmotGroupCreation.create(
                database = db, chatRepository = chatRepository, groupId = roomId, name = "#room",
                purpose = "Admins.", adminPublicKeys = setOf(user), userPublicKey = user, keyPair = keyPair,
                profile = MarmotProfile.CURRENT,
            )
        )
        member.makeBundle()
        val memberPackage = MarmotKeyPackage(
            id = member.hex, publicKey = member.hex,
            tlsEncodedMarmotKeyPackage = member.bundle.keyPackage.toTlsBytes(), profile = MarmotProfile.CURRENT.wireName,
        )
        chatRepository.inviteMember(room(), member.hex, memberPackage)

        // The Welcome the app queued, read by the stand-in directly.
        val welcome = db.giftWrapPayloadDao().getAllGiftWrapUnsigned().single { it.kind == WelcomeEvent.KIND }
        val welcomeEvent = WelcomeEvent(id = welcome.id, pubKey = user, createdAt = welcome.createdAt.epochSeconds, tags = welcome.tags, content = welcome.content, sig = "")
        val (_, routingId) = member.manager.processWelcome(Base64.decode(welcomeEvent.welcomeBase64()), member.bundle)
        member.inbound.trackGroup(routingId)
        assertEquals(routingId, db.marmotRoutingDao().activeRoutingIdFor(roomId))
        assertEquals(1L, member.manager.getGroup(routingId)!!.epoch)
        return routingId
    }

    /** The relay's OK on the newest commit this device put on the wire. */
    private suspend fun okTheLatestCommit(): GroupEvent {
        val event = db.nostrEventDao().getMLSGroupEvents(since = Instant.fromEpochSeconds(0), limit = 20)
            .filter { it.kind == GroupEvent.KIND }
            .last { db.marmotCommitResultDao().getMarmotCommitRequestById(it.id) != null }
        val request = db.broadcastNostrEventRequestDao().getFirstBroadcastNostrEventRequestByNostrEventId(event.id)!!
        nostrRepository.broadcastProcessed(request, "published")
        return event.toGroupEvent(user)!!
    }

    @Test
    fun `an admin commits a member's leave on receipt, and the room says they left`() = runBlocking {
        val routingId = roomWithMember()

        // The stand-in leaves: a standalone SelfRemove on the wire.
        val (framedProposal, exporterSecret) = member.manager.leaveGroup(routingId)
        val proposalEvent = NostrSignerInternal(KeyPair()).sign(
            GroupEvent.build(encryptedContentBase64 = GroupEventEncryption.encrypt(framedProposal, exporterSecret), nostrGroupId = routingId)
        )
        store(proposalEvent)

        // Staged, and committed by the admin's device without anyone pressing anything.
        assertEquals(1, db.marmotPublishObligationDao().getAll().size, "the admin did not commit the proposal")
        assertTrue(room().localParticipants.any { it.participant.participantPublicKey == member.hex }, "the row went before the commit was accepted")

        okTheLatestCommit()

        val group = db.marmotEngine.groupManager.getGroup(routingId)!!
        assertEquals(2L, group.epoch)
        assertEquals(setOf(user), group.currentMemberIdentities())
        assertFalse(room().localParticipants.any { it.participant.participantPublicKey == member.hex })
        assertTrue(transcript().any { it.messageType == ChatMessage.TYPE_MEMBER_LEFT && it.content.endsWith("left the group") })
    }

    @Test
    fun `an admin removes a member, and every device sees them gone once the commit lands`() = runBlocking {
        val routingId = roomWithMember()

        chatRepository.removeMember(room(), member.hex)
        assertEquals(1L, db.marmotEngine.groupManager.getGroup(routingId)!!.epoch, "applied before any relay accepted it")

        val commitEvent = okTheLatestCommit()
        assertEquals(setOf(user), db.marmotEngine.groupManager.getGroup(routingId)!!.currentMemberIdentities())
        assertFalse(room().localParticipants.any { it.participant.participantPublicKey == member.hex })
        assertTrue(transcript().any { it.messageType == ChatMessage.TYPE_MEMBER_LEFT })

        // The other side applies the same commit and is out.
        assertIs<GroupEventResult.CommitProcessed>(member.inbound.processGroupEvent(commitEvent))
        assertFalse(member.manager.isMember(routingId))
    }

    @Test
    fun `disbanding ends the group for everyone, and nothing goes in afterwards`() = runBlocking {
        val routingId = roomWithMember()

        chatRepository.disbandChatRoom(room())
        val commitEvent = okTheLatestCommit()

        val group = db.marmotEngine.groupManager.getGroup(routingId)!!
        assertTrue(group.currentGroupState().isDisbanded, "the disband did not land")
        assertNotNull(room().chatRoom.leftGroupAt, "a disbanded room is not closed")
        assertTrue(transcript().any { it.messageType == ChatMessage.TYPE_GROUP_DISBANDED })
        assertNull(db.marmotPublishObligationDao().getAll().firstOrNull())

        // The member applies it, is removed by it, and is refused afterwards.
        assertIs<GroupEventResult.CommitProcessed>(member.inbound.processGroupEvent(commitEvent))
        assertTrue(member.manager.getGroup(routingId)!!.currentGroupState().isDisbanded)
        assertFalse(member.manager.isMember(routingId))
    }

    @Test
    fun `an admin cannot leave, and is told why`() = runBlocking<Unit> {
        roomWithMember()

        val failure = runCatching { chatRepository.leaveChatRoom(room()) {} }.exceptionOrNull()

        assertIs<IllegalStateException>(failure)
        assertTrue(failure.message!!.contains("admin"))
        assertNull(room().chatRoom.leftGroupAt, "the room was closed although nothing left it")
    }

    /**
     * The member's side: the app in a room the stand-in administers. Its leave puts a
     * SelfRemove on the wire, drops its state and closes the room here; the admin's
     * device stages it.
     */
    @Test
    fun `leaving a current-profile room sends the proposal and closes the room here`() = runBlocking {
        val admin = MarmotStandIn()
        MarmotStandIn.seedProfile(db, user)
        val routingId = admin.createGroup("theirs")
        admin.inviteApp(db, DatabaseMarmotRepository(db, scope), keyPair, routingId)
        val theirRoom = db.chatRoomDao().findChatRoomById(routingId)!!

        chatRepository.leaveChatRoom(theirRoom) {}

        val closed = db.chatRoomDao().findChatRoomById(routingId)!!
        assertNotNull(closed.chatRoom.leftGroupAt)
        assertNull(closed.chatRoom.mlsGroupState, "the state was not dropped")
        assertTrue(
            db.chatMessageDao().getChatMessagesByChatRoomId(routingId)
                .any { it.chatMessage.messageType == ChatMessage.TYPE_MEMBER_LEFT && it.chatMessage.content == "You left the group" }
        )

        // A SelfRemove the admin's side stages.
        val proposalEvent = db.nostrEventDao().getMLSGroupEvents(since = Instant.fromEpochSeconds(0), limit = 20)
            .filter { it.kind == GroupEvent.KIND }
            .last()
            .toGroupEvent(user)!!
        assertNotNull(db.broadcastNostrEventRequestDao().getFirstBroadcastNostrEventRequestByNostrEventId(proposalEvent.id))
        assertIs<GroupEventResult.ProposalStaged>(admin.inbound.processGroupEvent(proposalEvent))
        assertTrue(admin.manager.hasPendingProposals(routingId))
    }
}
