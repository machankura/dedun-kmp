package com.machankura.dedun.compose.managers

import androidx.room3.Room
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.MarmotInnerEvent
import com.machankura.dedun.compose.managers.GroupFixtures.keyMaterial
import com.machankura.dedun.compose.managers.GroupFixtures.roomOf
import com.machankura.dedun.compose.managers.GroupFixtures.seedProfile
import com.machankura.dedun.compose.managers.GroupFixtures.signingRoom
import com.machankura.dedun.compose.nostr.wallet.GroupPayoutDestinationEvent
import com.machankura.dedun.compose.text.LightningAddress
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.secp256k1.Hex
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Each member's newest payout destination in a room, read from its inner events, and the line
 * each one makes in the transcript. `docs/group-payout-destination.md`, Phase 2.
 */
class GroupPayoutDestinationsJvmTest {
    private val db: DedunDatabase = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())

    @AfterTest
    fun closeDb() = db.close()

    private val chain = Chain.Regtest
    private val now = 1_700_001_000L

    private fun privateKey(seed: Long): PrivateKey = PrivateKey(ByteVector32(Hex.decode(seed.toString(16).padStart(64, '0'))))

    private val group = keyMaterial("6a0b")
    private val room = roomOf(group)
    private val alice = privateKey(0xa11ce)
    private val alicePublicKey = alice.xOnlyPublicKey().value.toHex()
    private val bob = privateKey(0xb0b)
    private val bobPublicKey = bob.xOnlyPublicKey().value.toHex()

    private suspend fun aRoomWithAliceAndBob() {
        seedProfile(db, alicePublicKey, name = "alice")
        signingRoom(db, group, members = listOf(alicePublicKey, bobPublicKey), holdsShare = false)
    }

    /** [statement] put in the room by its author, as an inner event carrying it whole. */
    private suspend fun file(statement: Event, carrier: String = statement.pubKey, inRoom: String = room) {
        val tags = arrayOf(arrayOf("h", inRoom))
        val id = EventHasher.hashId(carrier, statement.createdAt, GroupPayoutDestinationEvent.KIND, tags, statement.toJson())
        db.marmotInnerEventDao().upsert(
            MarmotInnerEvent(
                id = id,
                publicKey = carrier,
                kind = GroupPayoutDestinationEvent.KIND,
                createdAt = Instant.fromEpochSeconds(statement.createdAt),
                tags = tags,
                content = statement.toJson(),
                chatRoomId = inRoom,
            )
        )
    }

    private fun destination(member: PrivateKey, domain: String, createdAt: Long, chain: Chain = this.chain) =
        GroupPayoutDestinationEvent.build(member, room, chain, LightningAddress("me", domain), createdAt = createdAt)

    @Test
    fun `the newest destination of each member is the one read`(): Unit = runBlocking {
        aRoomWithAliceAndBob()
        file(destination(alice, "old.example", 1_700_000_000))
        file(destination(alice, "new.example", 1_700_000_100))
        file(destination(bob, "bob.example", 1_700_000_050))

        val read = GroupPayoutDestinations.destinationsIn(db, room, chain, now)
        assertEquals("new.example", read[alicePublicKey]?.lightningAddress?.domain)
        assertEquals("bob.example", read[bobPublicKey]?.lightningAddress?.domain)
    }

    @Test
    fun `a removal hides an older destination`(): Unit = runBlocking {
        aRoomWithAliceAndBob()
        file(destination(alice, "old.example", 1_700_000_000))
        file(GroupPayoutDestinationEvent.buildRemoved(alice, room, chain, createdAt = 1_700_000_100))

        assertNull(GroupPayoutDestinations.destinationOf(db, room, chain, alicePublicKey, now))
        assertTrue(GroupPayoutDestinations.statementsIn(db, room, chain, now).containsKey(alicePublicKey))
    }

    @Test
    fun `a member no longer in the room is skipped, and counts again when added back`(): Unit = runBlocking {
        aRoomWithAliceAndBob()
        file(destination(alice, "alice.example", 1_700_000_000))
        val aliceRow = db.participantDao().findParticipantsByChatRoomId(room).single { it.participantPublicKey == alicePublicKey }

        db.participantDao().delete(listOf(aliceRow))
        assertNull(GroupPayoutDestinations.destinationOf(db, room, chain, alicePublicKey, now))

        db.participantDao().upsert(listOf(aliceRow.copy(id = 0)))
        assertEquals("alice.example", GroupPayoutDestinations.destinationOf(db, room, chain, alicePublicKey, now)?.lightningAddress?.domain)
    }

    @Test
    fun `another chain's destination is not read`(): Unit = runBlocking {
        aRoomWithAliceAndBob()
        file(destination(alice, "signet.example", 1_700_000_000, chain = Chain.Signet))

        assertNull(GroupPayoutDestinations.destinationOf(db, room, chain, alicePublicKey, now))
        assertEquals("signet.example", GroupPayoutDestinations.destinationOf(db, room, Chain.Signet, alicePublicKey, now)?.lightningAddress?.domain)
    }

    @Test
    fun `a destination put in the room by somebody else is not read`(): Unit = runBlocking {
        aRoomWithAliceAndBob()
        file(destination(alice, "alice.example", 1_700_000_000), carrier = bobPublicKey)

        assertNull(GroupPayoutDestinations.destinationOf(db, room, chain, alicePublicKey, now))
    }

    private suspend fun lineFor(statement: Event, carrier: String = statement.pubKey, groupId: String = room): ChatMessage? {
        val tags = arrayOf(arrayOf("h", groupId))
        val inner = Event("ab".repeat(32), carrier, statement.createdAt, GroupPayoutDestinationEvent.KIND, tags, statement.toJson(), "")
        return ChatMessage.applyInnerEvent(
            database = db,
            groupId = groupId,
            event = inner,
            marmotGroupEventId = null,
            marmotInnerEventId = inner.id,
            senderPublicKey = carrier,
            isUserMessage = false,
            createdAt = Instant.fromEpochSeconds(statement.createdAt),
        )
    }

    @Test
    fun `setting and removing a destination each make a line that names nobody's address`(): Unit = runBlocking {
        aRoomWithAliceAndBob()
        val set = lineFor(destination(alice, "alice.example", 1_700_000_000))
        assertEquals(ChatMessage.TYPE_PAYOUT_DESTINATION_SET, set?.messageType)
        assertEquals("@alice set where this group pays them", set?.content)
        assertTrue(ChatMessage.TYPE_PAYOUT_DESTINATION_SET in ChatMessage.GROUP_IDENTITY_TYPES)

        val removed = lineFor(GroupPayoutDestinationEvent.buildRemoved(alice, room, chain, createdAt = 1_700_000_100))
        assertEquals(ChatMessage.TYPE_PAYOUT_DESTINATION_REMOVED, removed?.messageType)
        assertEquals("@alice removed where this group pays them", removed?.content)
    }

    @Test
    fun `a statement about another room, or carried by somebody else, makes no line`(): Unit = runBlocking {
        aRoomWithAliceAndBob()
        assertNull(lineFor(destination(alice, "alice.example", 1_700_000_000), carrier = bobPublicKey))
        assertNull(lineFor(destination(alice, "alice.example", 1_700_000_000), groupId = "ff".repeat(32)))
    }
}
