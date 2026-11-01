package com.machankura.dedun.compose.managers

import androidx.room3.Room
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.DkgParticipantMessage
import com.machankura.dedun.compose.database.model.DkgSession
import com.machankura.dedun.compose.database.model.MarmotInnerEvent
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.Participant
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.types.DkgRitualStage
import com.machankura.dedun.compose.nostr.chronicle.ChronicleEvent
import com.machankura.dedun.compose.nostr.dkg.DkgRitualEvents
import com.machankura.dedun.compose.nostr.frost.FrostSigningEvents
import com.machankura.dedun.compose.nostr.wallet.DepositAttributionEvent
import com.machankura.dedun.compose.nostr.wallet.DepositorStatementEvent
import com.machankura.dedun.compose.nostr.wallet.GroupPayoutDestination
import com.machankura.dedun.compose.nostr.wallet.GroupPayoutDestinationEvent
import com.machankura.dedun.compose.text.LightningAddress
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.crypto.frost.Frost
import fr.acinq.bitcoin.crypto.frost.KeyMaterial
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Becoming a depositor -- `docs/member-deposits.md`, Phase 3: a depositor statement proposed and
 * signed between two devices through the real signing manager, with the wire held by hand as
 * `GroupSpendSigningJvmTest` holds it, and each check a signer makes before it signs one.
 */
class DepositorsJvmTest {
    private val participants = 3
    private val threshold = 2
    private val keyMaterial: KeyMaterial = Frost.trustedDealerKeygen(
        thresholdSecretKey = PrivateKey(ByteVector32("1c0ffee0000000000000000000000000000000000000000000000000000000a2")),
        nParticipants = participants,
        threshold = threshold,
    )
    private val thresholdPublicKey = keyMaterial.thresholdPublicKey.value.toHex()
    private val ceremonyId = "ceremony".padEnd(64, '0')
    private val roomId = SharedKeyDerivation.marmotGroupId(thresholdPublicKey)
    private val chain = Chain.Regtest

    private fun key(seed: String) = PrivateKey(ByteVector32(fr.acinq.secp256k1.Hex.decode(seed.padStart(64, '0'))))
    private val aliceKey = key("a11ce")
    private val bobKey = key("b0b")
    private val alice = aliceKey.xOnlyPublicKey().value.toHex()
    private val bob = bobKey.xOnlyPublicKey().value.toHex()
    private val carol = "c".repeat(64)
    private val members = listOf(alice, bob, carol)
    private val hostKeys = listOf("2a", "2b", "2c").map { it.padEnd(66, '0') }

    private val devices = mutableListOf<Device>()

    @AfterTest
    fun closeDatabases() = devices.forEach { it.db.close() }

    private inner class Device(val publicKey: HexKey, val db: DedunDatabase, val room: LocalChatRoom) {
        val received = mutableSetOf<String>()
    }

    private suspend fun device(publicKey: HexKey, signerIndex: Int): Device {
        val db = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())
        members.forEach { member ->
            val nostrEventId = "e$member".take(64)
            db.nostrEventDao().upsert(NostrEvent(id = nostrEventId, pubKey = member, kind = 0, tags = emptyArray(), content = "{}", sig = "0".repeat(128)))
            db.profileDao().upsert(Profile(publicKey = member, nostrEventId = nostrEventId))
        }
        val chatRoom = ChatRoom(id = roomId, userPublicKey = publicKey, subject = "Savings", description = SharedKeyDerivation.describe("Savings."), mlsGroupState = "00")
        db.chatRoomDao().upsert(chatRoom)
        db.participantDao().upsert(members.map { Participant(participantPublicKey = it, chatRoomId = roomId, relayHint = null) })
        db.dkgSessionDao().upsert(
            DkgSession(
                id = ceremonyId, chatRoomId = roomId, coordinatorPublicKey = members.first(), userPublicKey = publicKey,
                threshold = threshold, participantCount = participants, stage = DkgRitualStage.COMPLETE,
                hostPublicKey = hostKeys[signerIndex], round1Random = "1".repeat(64), round2AuxRandom = "2".repeat(64),
                thresholdPublicKey = thresholdPublicKey,
                secretShare = keyMaterial.secretShares[signerIndex].value.toHex(),
                publicShares = keyMaterial.publicShares.joinToString(",") { it.value.toHex() },
            )
        )
        members.forEachIndexed { index, member ->
            db.dkgSessionDao().upsert(DkgParticipantMessage(sessionId = ceremonyId, participantPublicKey = member, kind = DkgRitualEvents.HOST_KEY, payload = hostKeys[index]))
        }
        val localChatRoom = db.chatRoomDao().findChatRoomById(roomId) ?: LocalChatRoom(chatRoom = chatRoom)
        return Device(publicKey, db, localChatRoom).also { devices += it }
    }

    private suspend fun pump(vararg between: Device) {
        var moved = true
        while (moved) {
            moved = false
            for (from in between) for (to in between) {
                if (from === to) continue
                val outbox = from.db.marmotInnerEventDao().getByChatRoomAndKinds(roomId, FrostSigningEvents.ALL.toList())
                    .filter { it.publicKey == from.publicKey }
                    .sortedWith(compareBy({ it.createdAt }, { it.kind }))
                outbox.forEach { queued ->
                    if (!to.received.add(queued.id)) return@forEach
                    moved = true
                    to.db.marmotInnerEventDao().upsert(queued.copy(marmotGroupEventId = null))
                    val event = Event(queued.id, queued.publicKey, queued.createdAt.epochSeconds, queued.kind, queued.tags, queued.content, "")
                    FrostSigningManager.processSigningPayload(to.db, to.room, event, to.publicKey)
                }
            }
        }
    }

    /** [member]'s destination, put in the room on every device as it would arrive there. */
    private suspend fun setDestination(member: PrivateKey, createdAt: Long, vararg on: Device): GroupPayoutDestination {
        val signed = GroupPayoutDestinationEvent.build(member, roomId, chain, LightningAddress("me", "example.com"), createdAt = createdAt)
        file(signed, *on)
        return GroupPayoutDestinationEvent.verified(signed) as GroupPayoutDestination
    }

    private suspend fun file(signed: Event, vararg on: Device) {
        val tags = arrayOf(arrayOf("h", roomId))
        val id = EventHasher.hashId(signed.pubKey, signed.createdAt, GroupPayoutDestinationEvent.KIND, tags, signed.toJson())
        on.forEach {
            it.db.marmotInnerEventDao().upsert(
                MarmotInnerEvent(id = id, publicKey = signed.pubKey, kind = GroupPayoutDestinationEvent.KIND, createdAt = Instant.fromEpochSeconds(signed.createdAt), tags = tags, content = signed.toJson(), chatRoomId = roomId)
            )
        }
    }

    @Test
    fun `a member with a destination is opened by a quorum, on a branch of their own`(): Unit = runBlocking {
        val proposer = device(alice, signerIndex = 0)
        val other = device(bob, signerIndex = 1)
        setDestination(aliceKey, 1_700_000_000, proposer, other)

        val session = Depositors.propose(proposer.db, proposer.room, alice, alice, chain)
        pump(proposer, other)
        FrostSigningManager.approve(other.db, other.room, session.id)
        pump(proposer, other)

        listOf(proposer, other).forEach { device ->
            val depositor = assertNotNull(Depositors.statementOf(device.db, roomId, chain, alice), "signed on every device")
            assertEquals(DepositBranch.of(roomId, alice), depositor.branch)
        }
        assertEquals(Depositors.Refusal.ALREADY_OPEN, Depositors.refusal(proposer.db, roomId, chain, alice))
    }

    @Test
    fun `a member with no destination, or nobody in the room, can't be proposed`(): Unit = runBlocking {
        val proposer = device(alice, signerIndex = 0)
        assertEquals(Depositors.Refusal.NO_DESTINATION, Depositors.refusal(proposer.db, roomId, chain, bob))
        assertEquals(Depositors.Refusal.NOT_A_MEMBER, Depositors.refusal(proposer.db, roomId, chain, "d".repeat(64)))
        assertFailsWith<IllegalStateException> { Depositors.propose(proposer.db, proposer.room, alice, bob, chain) }
    }

    /** A statement for [destination], unsigned: what a signer reads before the group signs. */
    private fun proposal(destination: GroupPayoutDestination, salt: Int = 0, createdAt: Long = 1_700_000_100): Event {
        val template = DepositorStatementEvent.template(roomId, destination, salt, createdAt)
        val id = EventHasher.hashId(roomId, createdAt, template.kind, template.tags, template.content)
        return Event(id, roomId, createdAt, template.kind, template.tags, template.content, "")
    }

    @Test
    fun `a signer refuses a statement for somebody not in the group`(): Unit = runBlocking {
        val signer = device(bob, signerIndex = 1)
        signer.db.participantDao().delete(signer.db.participantDao().findParticipantsByChatRoomId(roomId).filter { it.participantPublicKey == alice })
        val destination = setDestination(aliceKey, 1_700_000_000, signer)
        assertNotNull(Depositors.refuse(signer.db, roomId, proposal(destination)))
    }

    @Test
    fun `a signer refuses a statement carrying a destination older than the member's newest, or since removed`(): Unit = runBlocking {
        val signer = device(bob, signerIndex = 1)
        val older = setDestination(aliceKey, 1_700_000_000, signer)
        assertNull(Depositors.refuse(signer.db, roomId, proposal(older)))

        setDestination(aliceKey, 1_700_000_050, signer)
        assertNotNull(Depositors.refuse(signer.db, roomId, proposal(older)))

        file(GroupPayoutDestinationEvent.buildRemoved(aliceKey, roomId, chain, createdAt = 1_700_000_090), signer)
        val newest = GroupPayoutDestinationEvent.verified(
            GroupPayoutDestinationEvent.build(aliceKey, roomId, chain, LightningAddress("me", "example.com"), createdAt = 1_700_000_050)
        ) as GroupPayoutDestination
        assertNotNull(Depositors.refuse(signer.db, roomId, proposal(newest)))
    }

    @Test
    fun `a signer refuses a second statement, and a branch another member has`(): Unit = runBlocking {
        val proposer = device(alice, signerIndex = 0)
        val other = device(bob, signerIndex = 1)
        val alices = setDestination(aliceKey, 1_700_000_000, proposer, other)
        val bobs = setDestination(bobKey, 1_700_000_000, proposer, other)
        val session = Depositors.propose(proposer.db, proposer.room, alice, alice, chain)
        pump(proposer, other)
        FrostSigningManager.approve(other.db, other.room, session.id)
        pump(proposer, other)

        assertNotNull(Depositors.refuse(other.db, roomId, proposal(alices, salt = 1)), "a second statement")
        assertNull(Depositors.refuse(other.db, roomId, proposal(bobs)))
        assertNotEquals(DepositBranch.of(roomId, alice), DepositBranch.of(roomId, bob))
    }

    @Test
    fun `the chronicle carries both kinds, the statement first`() {
        assertTrue(ChronicleEvent.isChroniclable(DepositorStatementEvent.KIND))
        assertTrue(ChronicleEvent.isChroniclable(DepositAttributionEvent.KIND))
        assertTrue(ChronicleEvent.KINDS.applyRank(DepositorStatementEvent.KIND)!! < ChronicleEvent.KINDS.applyRank(DepositAttributionEvent.KIND)!!)
    }
}
