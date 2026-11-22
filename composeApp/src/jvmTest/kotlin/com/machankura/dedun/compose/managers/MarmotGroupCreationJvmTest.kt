package com.machankura.dedun.compose.managers

import androidx.room3.Room
import com.vitorpamplona.marmot.groups.currentAdminIdentities
import com.vitorpamplona.marmot.groups.currentGroupState
import com.vitorpamplona.marmot.groups.currentMarmotData
import com.vitorpamplona.marmot.groups.currentNostrGroupId
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.vitorpamplona.marmot.appComponents.CurrentProfileGroupFactory
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.DkgSession
import com.machankura.dedun.compose.database.model.MarmotKeyPackage
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.types.DkgRitualStage
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.Participant
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.database.repository.DatabaseChatRepository
import com.machankura.dedun.compose.extensions.toHex
import com.machankura.dedun.compose.nostr.MarmotProfile

/**
 * What the device that *creates* a room knows about it afterwards.
 *
 * `Participant.adminAt` is written in exactly one place --
 * `MarmotInboundManager.processGroupMembershipChanges`, on an arriving commit --
 * while the rows a creator makes come from `getOrCreateChatRoom` and
 * `addMembers`, neither of which sets it. So a room whose epoch-0 context names
 * its admins had none of them flagged on the one device certain to be one, until
 * some *other* member sent a commit.
 *
 * It read as a permissions bug: "only an admin of this group can make a
 * subgroup", said to the admin who had just made the group.
 *
 * The guard now reads `MarmotGroupData` instead, which is right on both sides
 * from epoch 0 -- but the column is still what the UI labels members with, so it
 * is caught up here too.
 */
class MarmotGroupCreationJvmTest {

    private val db: DedunDatabase = getRoomDatabase(
        Room.inMemoryDatabaseBuilder<DedunDatabase>()
    )

    @AfterTest
    fun closeDb() = db.close()

    private val keyPair = KeyPair()
    private val user = keyPair.pubKey.toHexKey()

    private val repository = DatabaseChatRepository(db, CoroutineScope(Dispatchers.IO))

    private val groupId = SharedKeyDerivation.marmotGroupId(
        "02" + "ab".repeat(32)
    )

    @Test
    fun `the creator is flagged an admin of the room they just created`(): Unit = runBlocking {
        seedProfile(user)

        val outcome = MarmotGroupCreation.create(
            database = db,
            chatRepository = repository,
            groupId = groupId,
            name = "#Ekklesia",
            purpose = "Admins of Ekklesia.",
            // A group of one, so nothing has to be invited and no key package has
            // to be found. The flag is the whole of what is being tested.
            adminPublicKeys = setOf(user),
            userPublicKey = user,
            keyPair = keyPair,
        )

        assertIs<MarmotGroupCreation.Outcome.Created>(outcome)

        val room = assertNotNull(db.chatRoomDao().findChatRoomById(groupId))
        val creator = assertNotNull(
            room.localParticipants.firstOrNull { it.participant.participantPublicKey == user }
        )

        assertNotNull(
            creator.participant.adminAt,
            "the member who created the room is one of its admins in its own epoch-0 " +
                "context, and nothing else will ever tell this device so",
        )
    }

    @Test
    fun `the room's own group data names the admins it was created with`(): Unit = runBlocking {
        seedProfile(user)

        MarmotGroupCreation.create(
            database = db,
            chatRepository = repository,
            groupId = groupId,
            name = "#Ekklesia",
            purpose = "Admins of Ekklesia.",
            adminPublicKeys = setOf(user),
            userPublicKey = user,
            keyPair = keyPair,
        )

        val room = assertNotNull(db.chatRoomDao().findChatRoomById(groupId))
        val data = assertNotNull(
            room.chatRoom.toMlsGroup()?.currentMarmotData(),
            "a room created here has to be able to read its own group data back",
        )

        // The source the guard reads, and the one that is right from epoch 0 on
        // every device. `nostrGroupId` has to be the 32-byte derived id: a
        // 33-byte value there produces an extension that reads back as null,
        // silently, taking the admin list with it.
        assertEquals(groupId, data.nostrGroupId)
        assertEquals(64, groupId.length)
        assertTrue(data.adminPubkeys.contains(user))
    }

    @Test
    fun `the room is named under the convention whatever the caller passed`(): Unit = runBlocking {
        seedProfile(user)

        MarmotGroupCreation.create(
            database = db,
            chatRepository = repository,
            groupId = groupId,
            // A bare name, as a caller reading a room's subject straight off the
            // database hands it over. The convention is this function's to apply:
            // it is where both flows that make a Marmot room meet, and the name
            // goes into the epoch-0 group context every member is welcomed with,
            // so a caller that forgot would mark the room on no device at all.
            name = "Ekklesia",
            purpose = "Admins of Ekklesia.",
            adminPublicKeys = setOf(user),
            userPublicKey = user,
            keyPair = keyPair,
        )

        val room = assertNotNull(db.chatRoomDao().findChatRoomById(groupId))
        val data = assertNotNull(room.chatRoom.toMlsGroup()?.currentMarmotData())

        // Both, and the same string in each: the row is what this device draws in
        // its room list, and the group data is what every other member reads.
        assertEquals("&Ekklesia", room.chatRoom.subject)
        assertEquals("&Ekklesia", data.name)
    }

    /**
     * One writer for the flags, from the group's policy, both ways: a row the
     * policy names is flagged, and a row it does not name loses a flag it had.
     * docs/robust-group-admins.md, Phase 2.
     */
    @Test
    fun `admin flags follow the group's policy, both ways`(): Unit = runBlocking {
        seedProfile(user)
        MarmotGroupCreation.create(
            database = db,
            chatRepository = repository,
            groupId = groupId,
            name = "#Ekklesia",
            purpose = "Admins of Ekklesia.",
            adminPublicKeys = setOf(user),
            userPublicKey = user,
            keyPair = keyPair,
        )

        val stranger = KeyPair().pubKey.toHexKey()
        seedProfile(stranger)
        val creatorRow = db.chatRoomDao().findChatRoomById(groupId)!!.localParticipants
            .single { it.participant.participantPublicKey == user }.participant
        db.participantDao().upsert(
            listOf(
                creatorRow.copy(adminAt = null),
                Participant(participantPublicKey = stranger, chatRoomId = groupId, relayHint = null, adminAt = Clock.System.now()),
            )
        )

        val group = assertNotNull(db.chatRoomDao().findChatRoomById(groupId)!!.chatRoom.toMlsGroup())
        assertEquals(2, AdminFlags.reconcile(db, groupId, group))

        val rows = db.chatRoomDao().findChatRoomById(groupId)!!.localParticipants.associate {
            it.participant.participantPublicKey to it.participant.adminAt
        }
        assertNotNull(rows[user], "the policy names the creator")
        assertNull(rows[stranger], "the policy does not name the stranger")
        assertEquals(0, AdminFlags.reconcile(db, groupId, group), "and a second pass changes nothing")
    }

    // ---- From a ceremony: docs/robust-group-admins.md, Phase 1 ----------------

    private val alice = KeyPair()
    private val bob = KeyPair()
    private val ceremonyRoomId = "ce1e".repeat(16)

    /** A finished ceremony over [user], alice and bob, as [owner]'s device holds it. */
    private suspend fun ceremonyOn(database: DedunDatabase, owner: KeyPair): Pair<DkgSession, LocalChatRoom> {
        val everyone = listOf(keyPair, alice, bob)
        everyone.forEach { seedProfile(it.pubKey.toHexKey(), database) }

        // Everybody but the device's own owner, as that device would have synced them.
        everyone.filter { it !== owner }.forEach { peer ->
            val bundle = CurrentProfileGroupFactory.createKeyPackage(NostrSignerInternal(peer))
            database.marmotKeyPackageDao().upsert(
                MarmotKeyPackage(
                    id = peer.pubKey.toHexKey().take(60) + "0000",
                    publicKey = peer.pubKey.toHexKey(),
                    tlsEncodedMarmotKeyPackage = bundle.keyPackage.toTlsBytes(),
                    profile = MarmotProfile.CURRENT.wireName,
                )
            )
        }

        database.chatRoomDao().upsert(
            ChatRoom(id = ceremonyRoomId, userPublicKey = owner.pubKey.toHexKey(), subject = "Ekklesia", description = null, mlsGroupState = null)
        )
        val session = DkgSession(
            id = "the-ceremony",
            chatRoomId = ceremonyRoomId,
            coordinatorPublicKey = user,
            userPublicKey = owner.pubKey.toHexKey(),
            threshold = 2,
            participantCount = 3,
            participantPublicKeys = DkgSession.formatParticipants(everyone.map { it.pubKey.toHexKey() }.toSet()),
            subject = "Ekklesia",
            stage = DkgRitualStage.COMPLETE,
            hostPublicKey = owner.pubKey.toHexKey(),
            round1Random = "aa".repeat(32),
            round2AuxRandom = "bb".repeat(32),
            thresholdPublicKey = "02" + "ab".repeat(32),
            secretShare = "cc".repeat(32),
        )
        database.dkgSessionDao().upsert(session)
        return session to assertNotNull(database.chatRoomDao().findChatRoomById(ceremonyRoomId))
    }

    @Test
    fun `the room's admins are the ceremony's participants, whoever creates it`(): Unit = runBlocking {
        val founders = setOf(user, alice.pubKey.toHexKey(), bob.pubKey.toHexKey())
        val alicesDb = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())

        try {
            // The coordinator's device, and a member's -- the two ways the room gets made.
            listOf(db to keyPair, alicesDb to alice).forEach { (database, owner) ->
                val (session, ceremonyRoom) = ceremonyOn(database, owner)

                val outcome = MarmotGroupCreation.createFor(
                    database = database,
                    chatRepository = DatabaseChatRepository(database, CoroutineScope(Dispatchers.IO)),
                    ceremony = session,
                    ceremonyRoom = ceremonyRoom,
                    keyPair = owner,
                    keyPackageLookupTimeout = 2_000L,
                )
                val created = assertIs<MarmotGroupCreation.Outcome.Created>(outcome)
                assertEquals(groupId, created.room.chatRoom.id, "the room is not the one the key derives")
                assertEquals("&Ekklesia", created.room.chatRoom.subject)

                val group = assertNotNull(created.room.chatRoom.toMlsGroup())
                assertEquals(founders, group.currentAdminIdentities(), "${owner.pubKey.toHexKey().take(8)}'s room")
            }
        } finally {
            alicesDb.close()
        }
    }

    @Test
    fun `a device that was not in the ceremony cannot create its room`(): Unit = runBlocking {
        val (session, ceremonyRoom) = ceremonyOn(db, keyPair)
        val outsider = session.copy(userPublicKey = KeyPair().pubKey.toHexKey())

        val outcome = MarmotGroupCreation.createFor(
            database = db,
            chatRepository = repository,
            ceremony = outsider,
            ceremonyRoom = ceremonyRoom,
            keyPair = KeyPair(),
        )

        assertIs<MarmotGroupCreation.Outcome.Failed>(outcome)
        assertNull(db.chatRoomDao().findChatRoomById(groupId), "a room was made anyway")
    }

    private suspend fun seedProfile(publicKey: String, database: DedunDatabase = db) {
        if (database.profileDao().getProfileByPublicKey(publicKey) != null) return
        val nostrEventId = publicKey.take(63) + "f"
        database.nostrEventDao().upsert(
            NostrEvent(
                id = nostrEventId,
                pubKey = publicKey,
                kind = 0,
                tags = emptyArray(),
                content = "{}",
                sig = "0".repeat(128),
            )
        )
        database.profileDao().upsert(
            Profile(publicKey = publicKey, userName = "member", nostrEventId = nostrEventId)
        )
    }

    // ---- The current profile ---------------------------------------------------

    /**
     * docs/marmot-v2.md, Phase 4. The room's id is still the derived key; the group's
     * routing address is minted apart from it and recorded; and the group's own
     * description names the room, so a joiner keys it as this device does.
     */
    @Test
    fun `a current-profile room keeps its derived id and gets a routing address of its own`(): Unit = runBlocking {
        seedProfile(user)

        val outcome = MarmotGroupCreation.create(
            database = db,
            chatRepository = repository,
            groupId = groupId,
            name = "#Ekklesia",
            purpose = "Admins of Ekklesia.",
            adminPublicKeys = setOf(user),
            userPublicKey = user,
            keyPair = keyPair,
            profile = MarmotProfile.CURRENT,
        )
        assertIs<MarmotGroupCreation.Outcome.Created>(outcome)

        val room = assertNotNull(db.chatRoomDao().findChatRoomById(groupId), "the room is not keyed on the derived id")
        val group = assertNotNull(room.chatRoom.toMlsGroup())
        assertTrue(group.currentGroupState().isCurrentProfile, "the group is not on the current profile")
        assertNull(group.currentMarmotData(), "a current-profile group must carry no 0xF2EE")

        // The address is not the identity.
        val routing = assertNotNull(db.marmotRoutingDao().findByChatRoomId(groupId).singleOrNull(), "no routing row")
        assertEquals(groupId, routing.chatRoomId)
        assertNotEquals(groupId, routing.routingId)
        assertEquals(routing.routingId, group.routingId())
        assertEquals(routing.routingId, group.currentNostrGroupId())
        assertEquals(listOf(routing.routingId), room.routingIds)

        // And the group says which room it is, so a joiner agrees with us.
        assertEquals(groupId, group.roomId())
        assertEquals(setOf(user), group.currentAdminIdentities())
        assertNotNull(room.localParticipants.firstOrNull { it.participant.participantPublicKey == user }?.participant?.adminAt)
    }

    @Test
    fun `a legacy room is addressed by its own id, unchanged`(): Unit = runBlocking {
        seedProfile(user)

        MarmotGroupCreation.create(
            database = db,
            chatRepository = repository,
            groupId = groupId,
            name = "#Ekklesia",
            purpose = "Admins of Ekklesia.",
            adminPublicKeys = setOf(user),
            userPublicKey = user,
            keyPair = keyPair,
            profile = MarmotProfile.LEGACY,
        )

        val room = assertNotNull(db.chatRoomDao().findChatRoomById(groupId))
        val group = assertNotNull(room.chatRoom.toMlsGroup())
        assertEquals(groupId, group.routingId())
        assertEquals(groupId, group.roomId())
        assertEquals(listOf(groupId), room.routingIds)
        assertEquals(groupId, room.routingId)
    }

    /**
     * The inbound side of the split: a kind:445 arrives under the routing address and
     * has to land in the room the address belongs to. Its content is nothing the
     * group can read, which files it as undecryptable -- what matters is which room.
     */
    @Test
    fun `a group event under the routing address is filed in the room`(): Unit = runBlocking {
        seedProfile(user)
        MarmotGroupCreation.create(
            database = db,
            chatRepository = repository,
            groupId = groupId,
            name = "#Ekklesia",
            purpose = "Admins of Ekklesia.",
            adminPublicKeys = setOf(user),
            userPublicKey = user,
            keyPair = keyPair,
            profile = MarmotProfile.CURRENT,
        )
        val routingId = db.marmotRoutingDao().activeRoutingIdFor(groupId)

        val eventId = "5".repeat(64)
        db.nostrDao().storeNostrEvent(
            nostrEvent = NostrEvent(
                id = eventId,
                pubKey = "6".repeat(64),
                kind = 445,
                tags = arrayOf(arrayOf("h", routingId)),
                content = "bm90IGEgZ3JvdXAgZXZlbnQ=",
                sig = "0".repeat(128),
                createdAt = Clock.System.now(),
            ),
            relayURL = "wss://relay.example",
            synchronizationRelayURLs = listOf("wss://relay.example"),
            level = 0,
            activeKeyPair = keyPair,
        )

        val filed = assertNotNull(db.marmotGroupEventDao().getMarmotGroupEventById(eventId), "the event was not filed")
        assertEquals(groupId, filed.chatRoomId, "filed under the address rather than the room")
    }
}
