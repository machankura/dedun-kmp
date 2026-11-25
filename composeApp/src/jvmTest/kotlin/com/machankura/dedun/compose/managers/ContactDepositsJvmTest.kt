package com.machankura.dedun.compose.managers

import androidx.room3.Room
import com.machankura.dedun.compose.contacts.ContactIdentifier
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.GroupSignedEvent
import com.machankura.dedun.compose.database.model.Participant
import com.machankura.dedun.compose.managers.GroupFixtures.keyMaterial
import com.machankura.dedun.compose.managers.GroupFixtures.roomOf
import com.machankura.dedun.compose.managers.GroupFixtures.signedAsRoom
import com.machankura.dedun.compose.nostr.wallet.ContactAmendmentEvent
import com.machankura.dedun.compose.nostr.wallet.ContactDepositorStatementEvent
import com.machankura.dedun.compose.nostr.wallet.ContactRecordEvent
import com.machankura.dedun.compose.nostr.wallet.DepositorStatementEvent
import com.machankura.dedun.compose.nostr.wallet.GroupPayoutDestination
import com.machankura.dedun.compose.nostr.wallet.GroupPayoutDestinationEvent
import com.machankura.dedun.compose.text.LightningAddress
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.secp256k1.Hex
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A contact's own deposit branch: opened by the group, clear of every other branch, and refused for
 * a contact the group closed or said was a member. `docs/non-member-deposits.md`, D10 and Phase 7.
 */
class ContactDepositsJvmTest {
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
    private val mum = ContactIdentifier.Phone("+27821234567")
    private val mumId = mum.contactId(room)

    private var clock = 1_700_000_000L

    private fun proposal(template: EventTemplate<*>): Event = signedAsRoom(group, template.kind, template.tags, template.content, ++clock)

    private suspend fun signed(template: EventTemplate<*>): Event = proposal(template).also {
        db.groupSignedEventDao().upsert(GroupSignedEvent.fromEvent(it, chatRoomId = room, derivationPath = null, frostSigningSessionId = null))
    }

    private suspend fun setUp() {
        GroupFixtures.seedRoom(db, room)
        listOf(alice, bob).forEach { GroupFixtures.seedProfile(db, it) }
        db.participantDao().upsert(listOf(alice, bob).map { Participant(participantPublicKey = it, chatRoomId = room, relayHint = null) })
        listOf(alice, bob).forEach { Contacts.announce(db, room, it) }
        signed(ContactRecordEvent.template(room, alice, "Mum", mum))
    }

    @Test
    fun `a contact's branch is picked clear of every branch the wallet watches`(): Unit = runBlocking {
        setUp()
        // Nobody holds the branch the contact's first salt gives, so it is the one picked.
        val first = DepositBranch.of(room, mumId, 0)
        val template = Contacts.depositorTemplate(db, room, chain, mumId)
        assertEquals(0, ContactDepositorStatementEvent.read(proposal(template), room)!!.salt)

        signed(template)
        val branch = Contacts.depositorsIn(db, room, chain).getValue(mumId).branch
        assertEquals(first, branch)
        assertEquals(branch, Depositors.branchesIn(db, room, chain)[mumId])
        assertNotNull(Contacts.refuse(db, room, proposal(Contacts.depositorTemplate(db, room, chain, mumId))), "a second branch for one contact")
    }

    @Test
    fun `a contact's branch that doesn't recompute is no branch, and takes nothing from a member`(): Unit = runBlocking {
        setUp()
        val destination = GroupPayoutDestinationEvent.verified(
            GroupPayoutDestinationEvent.build(aliceKey, room, chain, LightningAddress("me", "example.com"), createdAt = 1_700_000_000)
        ) as GroupPayoutDestination
        GroupPayoutDestinations.publish(db, room, destination.event)
        // Claiming the branch Alice's first salt gives, which no salt of the contact's does.
        val alicesFirst = DepositBranch.of(room, alice, 0)
        val forged = ContactDepositorStatementEvent.template(room, mumId, chain, 0).tags
            .map { if (it[0] == "branch") arrayOf("branch", alicesFirst.toString(), "0") else it }.toTypedArray()
        db.groupSignedEventDao().upsert(
            GroupSignedEvent.fromEvent(signedAsRoom(group, ContactDepositorStatementEvent.KIND, forged, "", ++clock), chatRoomId = room, derivationPath = null, frostSigningSessionId = null)
        )
        // A forged branch doesn't recompute, so it is no branch at all, and Alice's first salt stays hers.
        assertNull(Contacts.depositorsIn(db, room, chain)[mumId])
        assertNull(Depositors.refuse(db, room, proposal(DepositorStatementEvent.template(room, destination, 0))))
    }

    @Test
    fun `no branch is opened for a closed or claimed contact`(): Unit = runBlocking {
        setUp()
        signed(ContactAmendmentEvent.template(mumId, ContactAmendmentEvent.Change.Close))
        assertNotNull(Contacts.refuse(db, room, proposal(Contacts.depositorTemplate(db, room, chain, mumId))))
        signed(ContactAmendmentEvent.template(mumId, ContactAmendmentEvent.Change.Reopen))
        assertNull(Contacts.refuse(db, room, proposal(Contacts.depositorTemplate(db, room, chain, mumId))))
    }
}
