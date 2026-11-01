package com.machankura.dedun.compose.database.dao

import androidx.room3.Room
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.MarmotKeyPackage
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.extensions.toHex
import com.machankura.dedun.compose.nostr.Relays
import com.vitorpamplona.quartz.marmot.mip01Groups.MarmotGroupData
import com.vitorpamplona.quartz.marmot.mls.group.MlsGroup
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.machankura.dedun.compose.exceptions.MarmotMissingChatGroupException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The membership guards on [MarmotOutboundDao].
 *
 * Both entry points that change a group's membership begin by restoring the MLS state off the
 * ChatRoom row, and a room restored from an inbound gift wrap has none -- there is nothing to
 * add a member to. The comment on `inviteMemberToChatRoom` says the point of the throw is to
 * "say so instead of silently doing nothing and letting the caller report success", which is a
 * claim about behaviour and therefore testable: a guard that returned quietly would still
 * compile, still look like it worked, and leave a room whose members believe someone was
 * invited.
 *
 * Past the guard, the tests build a real MLS group and a real peer key package with
 * [marmotKeyPackageFor], so the commit, the epoch advance and its persistence are exercised
 * rather than described. Nothing there needs a relay: a key package is three local key
 * generations and two signatures.
 */
class MarmotOutboundDaoJvmTest {

    private val db: DedunDatabase = getRoomDatabase(
        Room.inMemoryDatabaseBuilder<DedunDatabase>()
    )

    @AfterTest
    fun closeDb() = db.close()

    private val user = KeyPair().pubKey.toHexKey()
    private val peer = KeyPair().pubKey.toHexKey()
    private val secondPeer = KeyPair().pubKey.toHexKey()
    private val roomId = "b".repeat(64)

    /**
     * A member the database knows by name. Every invitee needs one --
     * Participant.participantPublicKey is a foreign key onto Profile -- and the membership
     * lines read the name back out of it.
     */
    private suspend fun seedProfile(publicKey: String, name: String, nostrEventId: String) {
        db.nostrEventDao().upsert(
            NostrEvent(
                id = nostrEventId,
                pubKey = publicKey,
                kind = 0,
                tags = emptyArray(),
                content = "{}",
                sig = "0".repeat(128),
            )
        )
        db.profileDao().upsert(
            Profile(publicKey = publicKey, userName = name, nostrEventId = nostrEventId)
        )
    }

    /**
     * A room with `mlsGroupState = null` -- exactly the shape a room restored from an inbound
     * gift wrap has, which is the case the guard exists for.
     */
    private suspend fun seedStatelessRoom(): LocalChatRoom {
        seedProfile(user, "user", "c".repeat(64))
        val chatRoom = ChatRoom(
            id = roomId,
            userPublicKey = user,
            subject = "a restored room",
            description = null,
            mlsGroupState = null,
        )
        db.chatRoomDao().upsert(chatRoom)
        return LocalChatRoom(chatRoom = chatRoom)
    }

    /**
     * Never decoded: the guard throws before the tests using it reach the MLS layer, so the
     * bytes only have to exist. The tests past the guard use [marmotKeyPackageFor] instead.
     */
    private fun keyPackage() = MarmotKeyPackage(
        id = "d".repeat(64),
        publicKey = peer,
        tlsEncodedMarmotKeyPackage = ByteArray(0),
    )

    /**
     * A room holding real MLS state, as a room this device created would.
     *
     * The peer gets a Profile row because Participant.participantPublicKey is a foreign key
     * onto it, and a successful invite writes a Participant. The guard tests above never reach
     * that write, which is why only they can get away without one.
     */
    private suspend fun seedMlsRoom(): LocalChatRoom {
        val stateless = seedStatelessRoom()
        seedProfile(peer, "peer", "e".repeat(64))
        val mlsGroup = MlsGroup.create(
            identity = user.hexToByteArray(),
            initialExtensions = listOf(
                MarmotGroupData.bootstrap(
                    nostrGroupId = roomId,
                    creatorPubKey = user,
                    outboxRelays = Relays.DefaultDMRelayList.map { it.url },
                ).toExtension()
            ),
        )
        val chatRoom = stateless.chatRoom.copy(
            mlsGroupState = mlsGroup.saveState().encodeTls().toHex()
        )
        db.chatRoomDao().upsert(chatRoom)
        return LocalChatRoom(chatRoom = chatRoom)
    }

    @Test
    fun `inviting into a room with no mls state is refused rather than ignored`() = runBlocking<Unit> {
        val localChatRoom = seedStatelessRoom()

        assertFailsWith<MarmotMissingChatGroupException> {
            db.marmotOutboundDao().inviteMemberToChatRoom(
                localChatRoom = localChatRoom,
                peerPublicKey = peer,
                peerKeyPackage = keyPackage(),
            )
        }
    }

    /**
     * The guard has to come before the Participant write, not after. `sealGiftWrapPayload`
     * walks a room's participants to decide who to wrap a Welcome for, so a participant row
     * left behind by a failed invite would make the room look like it has a member that no MLS
     * group knows about.
     */
    @Test
    fun `a refused invite leaves no participant behind`() = runBlocking {
        val localChatRoom = seedStatelessRoom()

        runCatching {
            db.marmotOutboundDao().inviteMemberToChatRoom(
                localChatRoom = localChatRoom,
                peerPublicKey = peer,
                peerKeyPackage = keyPackage(),
            )
        }

        val participants = db.participantDao().findParticipantsByChatRoomId(roomId)
        assertTrue(
            participants.none { it.participantPublicKey == peer },
            "the invitee was persisted despite the invite being refused",
        )
    }

    @Test
    fun `adding members to a room with no mls state is refused`() = runBlocking<Unit> {
        val localChatRoom = seedStatelessRoom()

        assertFailsWith<MarmotMissingChatGroupException> {
            db.marmotOutboundDao().addMembersToChatRoom(
                localChatRoom = localChatRoom,
                peers = listOf(peer to keyPackage()),
            )
        }
    }

    /**
     * The empty-batch guard returns before the MLS state is even looked at, so it must not
     * throw on the same stateless room the two tests above reject. Adding nobody is not a
     * failure to add somebody.
     */
    @Test
    fun `adding no members is not an error even without mls state`() = runBlocking {
        val localChatRoom = seedStatelessRoom()

        val failed = db.marmotOutboundDao().addMembersToChatRoom(
            localChatRoom = localChatRoom,
            peers = emptyList(),
        )

        assertEquals(emptyList(), failed)
    }

    /**
     * Past the guard. The invitee is persisted before the Welcome is sealed, because
     * sealGiftWrapPayload walks the room's participants to decide who to wrap for -- without
     * the row the Welcome produced no gift wraps at all and sat unsealed forever.
     */
    @Test
    fun `inviting a member into a real group persists the invitee`() = runBlocking {
        val localChatRoom = seedMlsRoom()

        db.marmotOutboundDao().inviteMemberToChatRoom(
            localChatRoom = localChatRoom,
            peerPublicKey = peer,
            peerKeyPackage = marmotKeyPackageFor(peer),
        )

        assertTrue(
            db.participantDao().findParticipantsByChatRoomId(roomId)
                .any { it.participantPublicKey == peer },
            "the invitee was not persisted",
        )
    }

    /**
     * The epoch advance has to reach the database. `addMember` moves the in-memory group to
     * the next epoch; without saving it back the creator keeps encrypting under the old one --
     * which the new member cannot decrypt -- and the next invite re-derives from stale state
     * and produces a conflicting commit.
     */
    @Test
    fun `inviting a member persists the advanced group state`() = runBlocking {
        val localChatRoom = seedMlsRoom()
        val stateBefore = localChatRoom.chatRoom.mlsGroupState

        db.marmotOutboundDao().inviteMemberToChatRoom(
            localChatRoom = localChatRoom,
            peerPublicKey = peer,
            peerKeyPackage = marmotKeyPackageFor(peer),
        )

        val stateAfter = assertNotNull(db.chatRoomDao().findChatRoomById(roomId)).chatRoom.mlsGroupState
        assertNotNull(stateAfter)
        assertTrue(stateAfter != stateBefore, "the advanced epoch was never written back")
        val group = assertNotNull(
            db.chatRoomDao().findChatRoomById(roomId)!!.chatRoom.toMlsGroup(),
            "the persisted state no longer restores",
        )
        assertEquals(2, group.members().size.toInt(), "the invitee is not in the restored group")
    }

    /**
     * The epoch the group is leaving is retained on the way past, so messages already sent
     * under it stay readable. Asserted here rather than only in the retention-window tests,
     * because this is the call that actually writes one.
     */
    @Test
    fun `inviting a member retains the epoch being left behind`() = runBlocking {
        val localChatRoom = seedMlsRoom()

        db.marmotOutboundDao().inviteMemberToChatRoom(
            localChatRoom = localChatRoom,
            peerPublicKey = peer,
            peerKeyPackage = marmotKeyPackageFor(peer),
        )

        val retained = db.marmotRetainedEpochSecretDao()
            .getMarmotRetainedEpochSecretForChatRoomId(roomId)
        assertEquals(1, retained.size, "the pre-commit epoch was not retained")
        assertEquals(0L, retained.single().epoch, "a freshly created group is at epoch 0")
    }

    // ---- What the room is told about it -----------------------------------

    private suspend fun transcript() = db.chatMessageDao().getChatMessagesByChatRoomId(roomId)
        .map { it.chatMessage }

    /**
     * The invite has to reach the transcript, and as a membership line rather than a chat
     * bubble. It used to be written by `deliveryWelcome` -- which meant it depended on the
     * invitee's key package and profile both being found, and neither is here, so the room
     * was told nothing at all about an invite that succeeded.
     */
    @Test
    fun `inviting a member puts a line in the room`() = runBlocking {
        val localChatRoom = seedMlsRoom()

        db.marmotOutboundDao().inviteMemberToChatRoom(
            localChatRoom = localChatRoom,
            peerPublicKey = peer,
            peerKeyPackage = marmotKeyPackageFor(peer),
        )

        val line = transcript().singleOrNull { it.messageType == ChatMessage.TYPE_MEMBER_INVITED }
        assertNotNull(line, "the invite left no line in the room")
        assertTrue(line.content.contains("peer"), "the line does not name the invitee: ${line.content}")
    }

    /**
     * The case the line exists for. A group that already has members defers its Welcome until
     * a relay acknowledges the commit -- see docs/marmot-membership.md -- so an invite made
     * here is on nothing but a promise, and until the line moved to invite time the room said
     * nothing whatsoever in the meantime and nothing ever if the ack never came.
     */
    @Test
    fun `an invite into an established group is in the room before its welcome goes out`() = runBlocking {
        db.marmotOutboundDao().inviteMemberToChatRoom(
            localChatRoom = seedMlsRoom(),
            peerPublicKey = peer,
            peerKeyPackage = marmotKeyPackageFor(peer),
        )
        seedProfile(secondPeer, "second", "f".repeat(64))

        // Re-read: the first invite advanced the epoch and persisted new state.
        val established = assertNotNull(db.chatRoomDao().findChatRoomById(roomId))
        db.marmotOutboundDao().inviteMemberToChatRoom(
            localChatRoom = established,
            peerPublicKey = secondPeer,
            peerKeyPackage = marmotKeyPackageFor(secondPeer),
        )

        val invited = transcript().filter { it.messageType == ChatMessage.TYPE_MEMBER_INVITED }
        assertEquals(2, invited.size, "the deferred invite left no line in the room")
        assertTrue(
            invited.any { it.content.contains("second") },
            "the second invitee is not named: ${invited.map { it.content }}",
        )

        // Nothing has acknowledged the commit, so the Welcome has not gone out and nothing
        // may claim it has. That absence is what makes a stuck invite visible.
        assertTrue(
            transcript().none { it.messageType == ChatMessage.TYPE_MEMBER_INVITE_SENT },
            "a welcome was reported sent before any relay acknowledged the commit",
        )
    }

    /**
     * Membership lines are not somebody's words, and the transcript decides that from the
     * type alone. One missing from [ChatMessage.MEMBERSHIP_TYPES] renders as a chat bubble,
     * looking exactly like the inviter having said "Invited peer to the group".
     */
    @Test
    fun `the invite line is a membership line and not a bubble`() = runBlocking {
        db.marmotOutboundDao().inviteMemberToChatRoom(
            localChatRoom = seedMlsRoom(),
            peerPublicKey = peer,
            peerKeyPackage = marmotKeyPackageFor(peer),
        )

        assertTrue(
            transcript().all { it.messageType in ChatMessage.MEMBERSHIP_TYPES },
            "an invite wrote something the transcript would render as a bubble: " +
                transcript().map { it.messageType },
        )
    }

    /**
     * A refused invite must not leave the room claiming one was made. The line is written
     * inside the caller's transaction precisely so that it goes down with everything else the
     * refused invite touched -- what tells the user instead is
     * `DatabaseChatRepository.inviteMember`, from outside it.
     */
    @Test
    fun `a refused invite leaves no claim that one was made`() = runBlocking {
        runCatching {
            db.marmotOutboundDao().inviteMemberToChatRoom(
                localChatRoom = seedStatelessRoom(),
                peerPublicKey = peer,
                peerKeyPackage = keyPackage(),
            )
        }

        assertTrue(
            transcript().none { it.messageType == ChatMessage.TYPE_MEMBER_INVITED },
            "the room was told about an invite that was refused",
        )
    }
}
