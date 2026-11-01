package com.machankura.dedun.compose.managers

import androidx.room3.Room
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.database.repository.DatabaseChatRepository
import com.machankura.dedun.compose.extensions.toHex
import com.machankura.dedun.compose.nostr.MarmotProfile
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.time.Clock

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
        assertEquals("#Ekklesia", room.chatRoom.subject)
        assertEquals("#Ekklesia", data.name)
    }

    private suspend fun seedProfile(publicKey: String) {
        val nostrEventId = publicKey.take(63) + "f"
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
