package com.machankura.dedun.compose.managers

import androidx.room3.Room
import com.machankura.dedun.compose.contacts.ContactIdentifier
import com.machankura.dedun.compose.contacts.Party
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.GroupSignedEvent
import com.machankura.dedun.compose.database.model.Participant
import com.machankura.dedun.compose.database.repository.DatabaseFrostSigningRepository
import com.machankura.dedun.compose.managers.GroupFixtures.keyMaterial
import com.machankura.dedun.compose.managers.GroupFixtures.roomOf
import com.machankura.dedun.compose.managers.GroupFixtures.signedAsRoom
import com.machankura.dedun.compose.nostr.wallet.ContactAmendmentEvent
import com.machankura.dedun.compose.nostr.wallet.ContactClaimEvent
import com.machankura.dedun.compose.nostr.wallet.ContactDepositorStatementEvent
import com.machankura.dedun.compose.nostr.wallet.ContactPayoutDestinationEvent
import com.machankura.dedun.compose.nostr.wallet.ContactRecordEvent
import com.machankura.dedun.compose.nostr.wallet.DepositorStatementEvent
import com.machankura.dedun.compose.nostr.wallet.GroupPayoutDestination
import com.machankura.dedun.compose.nostr.wallet.GroupPayoutDestinationEvent
import com.machankura.dedun.compose.text.LightningAddress
import com.machankura.dedun.compose.wallet.GroupOnchainWallets
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.TxId
import fr.acinq.secp256k1.Hex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a signer checks before the group agrees to a contact, and what it lets the group give one:
 * `docs/non-member-deposits.md`, D7 and Phase 3. The quorum is supreme, so nothing here asks who
 * proposed: only that the proposal is well formed, consistent, and in a room every device reads.
 */
class ContactProposalJvmTest {
    private val db: DedunDatabase = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())

    @AfterTest
    fun tearDown() = db.close()

    private val chain = Chain.Regtest
    private val group = keyMaterial("6a0b")
    private val room = roomOf(group)
    private fun key(seed: String) = PrivateKey(ByteVector32(Hex.decode(seed.padStart(64, '0'))))
    private val aliceKey = key("a11ce")
    private val alice = aliceKey.xOnlyPublicKey().value.toHex()
    private val bob = key("b0b").xOnlyPublicKey().value.toHex()
    private val carol = key("ca201").xOnlyPublicKey().value.toHex()

    private val mum = ContactIdentifier.Phone("+27821234567")
    private val mumId = mum.contactId(room)

    private var clock = 1_700_000_000L

    /** The room, with [members] in it, every one of them having said they read contacts. */
    private suspend fun roomWith(vararg members: String, announced: Boolean = true) {
        GroupFixtures.seedRoom(db, room)
        members.forEach { GroupFixtures.seedProfile(db, it) }
        db.participantDao().upsert(members.map { Participant(participantPublicKey = it, chatRoomId = room, relayHint = null) })
        if (announced) members.forEach { Contacts.announce(db, room, it) }
    }

    /** [template] as the group would propose it: unsigned, authored by the room. */
    private fun proposal(template: EventTemplate<*>): Event = signedAsRoom(group, template.kind, template.tags, template.content, ++clock)

    /** [template], signed and on file. */
    private suspend fun signed(template: EventTemplate<*>): Event = proposal(template).also {
        db.groupSignedEventDao().upsert(GroupSignedEvent.fromEvent(it, chatRoomId = room, derivationPath = null, frostSigningSessionId = null))
    }

    private fun record(name: String = "Mum", identifier: ContactIdentifier = mum) = ContactRecordEvent.template(room, alice, name, identifier)

    @Test
    fun `nothing about a contact is signed while a member's device hasn't said it reads contacts`(): Unit = runBlocking {
        roomWith(alice, bob, announced = false)
        Contacts.announce(db, room, alice)
        assertEquals(Contacts.WAITING, Contacts.refuse(db, room, proposal(record())))
        Contacts.announce(db, room, bob)
        assertNull(Contacts.refuse(db, room, proposal(record())))
    }

    @Test
    fun `a second record for a number the group counts is refused, by its id`(): Unit = runBlocking {
        roomWith(alice, bob)
        signed(record())
        assertNotNull(Contacts.refuse(db, room, proposal(record(name = "Gogo"))))
        assertNull(Contacts.refuse(db, room, proposal(record(identifier = ContactIdentifier.Email("mum@example.com")))))
    }

    @Test
    fun `an amendment, a claim, a destination or a branch needs a record, and makes sense against it`(): Unit = runBlocking {
        roomWith(alice, bob)
        assertEquals(Contacts.NO_RECORD, Contacts.refuse(db, room, proposal(ContactAmendmentEvent.template(mumId, ContactAmendmentEvent.Change.Close))))
        assertEquals(Contacts.NO_RECORD, Contacts.refuse(db, room, proposal(ContactClaimEvent.template(mumId, bob))))
        signed(record())

        assertNotNull(Contacts.refuse(db, room, proposal(ContactAmendmentEvent.template(mumId, ContactAmendmentEvent.Change.Reopen))))
        assertNull(Contacts.refuse(db, room, proposal(ContactAmendmentEvent.template(mumId, ContactAmendmentEvent.Change.Close))))
        assertNull(Contacts.refuse(db, room, proposal(ContactAmendmentEvent.template(mumId, ContactAmendmentEvent.Change.Rename("Gogo")))))

        assertNotNull(Contacts.refuse(db, room, proposal(ContactClaimEvent.template(mumId, carol))), "Carol is not in the group")
        assertNotNull(Contacts.refuse(db, room, proposal(ContactClaimEvent.template(mumId, null))), "already nobody's")
        assertNull(Contacts.refuse(db, room, proposal(ContactClaimEvent.template(mumId, bob))))

        assertNull(Contacts.refuse(db, room, proposal(ContactDepositorStatementEvent.template(room, mumId, chain))))
        signed(ContactClaimEvent.template(mumId, bob))
        assertNotNull(Contacts.refuse(db, room, proposal(ContactClaimEvent.template(mumId, bob))), "says what the group already says")
        assertNotNull(Contacts.refuse(db, room, proposal(ContactPayoutDestinationEvent.template(mumId, chain, LightningAddress("mum", "example.com"), null))))
        assertNotNull(Contacts.refuse(db, room, proposal(ContactDepositorStatementEvent.template(room, mumId, chain))))
    }

    @Test
    fun `a contact's branch is refused when the contact already has one, or somebody has it`(): Unit = runBlocking {
        roomWith(alice, bob)
        signed(record())
        signed(ContactDepositorStatementEvent.template(room, mumId, chain, salt = 0))
        assertNotNull(Contacts.refuse(db, room, proposal(ContactDepositorStatementEvent.template(room, mumId, chain, salt = 1))))
        val gogo = ContactIdentifier.Phone("+27821234568")
        signed(record(name = "Gogo", identifier = gogo))
        assertNull(Contacts.refuse(db, room, proposal(ContactDepositorStatementEvent.template(room, gogo.contactId(room), chain))))
    }

    private suspend fun deposit(seed: Byte, sats: Long, branch: Long): String {
        val outPoint = OutPoint(TxId(ByteVector32(ByteArray(32) { seed })), 0)
        DepositLedger.recordOnchain(db, room, chain, listOf(GroupOnchainWallets.OnchainDeposit(outPoint, Satoshi(sats), branch, confirmed = true)))
        return "${outPoint.txid}:0"
    }

    private suspend fun aliceOpened(): DepositorStatementEvent.Depositor {
        val destination = GroupPayoutDestinationEvent.verified(
            GroupPayoutDestinationEvent.build(aliceKey, room, chain, LightningAddress("me", "example.com"), createdAt = 1_700_000_000)
        ) as GroupPayoutDestination
        return DepositorStatementEvent.verify(signed(DepositorStatementEvent.template(room, destination, 0, 1_700_000_100)), room)!!
    }

    @Test
    fun `the quorum may give any deposit to a contact it signed, a member's own included`(): Unit = runBlocking {
        roomWith(alice, bob)
        val branch = aliceOpened().branch
        val alices = deposit(1, 30_000, branch)
        val nobodys = deposit(2, 5_000, GroupWalletDerivation.RECEIVE)

        val toMum = DepositLedger.attributionTemplate(db, room, chain, mapOf(alices to Party.Contact(mumId), nobodys to Party.Contact(mumId)))
        assertTrue(toMum.tags.any { it.size >= 3 && it[2] == "contact:$mumId" })
        assertEquals(Contacts.NO_RECORD, DepositLedger.refuseAttribution(db, room, proposal(toMum)))

        signed(record())
        assertNull(DepositLedger.refuseAttribution(db, room, proposal(toMum)))

        // Whose deposits it gives away is said on the signing screen.
        val event = proposal(toMum)
        val losing = DatabaseFrostSigningRepository(db, CoroutineScope(Dispatchers.Default)).membersLosingDeposits(room, listOf(event))
        assertEquals(mapOf(event.id to setOf(alice)), losing)

        signed(toMum)
        val tallies = DepositLedger.tallies(db, room, chain)
        assertEquals(35_000_000, tallies.contacts.getValue(mumId).depositedMsat)
        assertNull(tallies.members[alice])
    }

    @Test
    fun `a closed or claimed contact is given nothing new`(): Unit = runBlocking {
        roomWith(alice, bob)
        val key = deposit(1, 5_000, GroupWalletDerivation.RECEIVE)
        signed(record())
        val toMum = DepositLedger.attributionTemplate(db, room, chain, mapOf(key to Party.Contact(mumId)))
        signed(ContactAmendmentEvent.template(mumId, ContactAmendmentEvent.Change.Close))
        assertNotNull(DepositLedger.refuseAttribution(db, room, proposal(toMum)))
        signed(ContactAmendmentEvent.template(mumId, ContactAmendmentEvent.Change.Reopen))
        assertNull(DepositLedger.refuseAttribution(db, room, proposal(toMum)))
        signed(ContactClaimEvent.template(mumId, bob))
        assertNotNull(DepositLedger.refuseAttribution(db, room, proposal(toMum)))
    }

    @Test
    fun `a member without a share asks, and the request waits until the group signs what it asked`(): Unit = runBlocking {
        roomWith(alice, bob)
        val template = record()
        Contacts.ask(db, room, bob, template)
        val pending = Contacts.pendingRequestsIn(db, room).single()
        assertEquals(bob, pending.member)
        assertEquals(ContactRecordEvent.KIND, pending.request.kind)
        assertNull(Contacts.refuse(db, room, proposal(pending.template)))

        signed(pending.template)
        assertTrue(Contacts.pendingRequestsIn(db, room).isEmpty())
        assertEquals("Mum", Contacts.contactOf(db, room, mumId)?.name)
    }
}
