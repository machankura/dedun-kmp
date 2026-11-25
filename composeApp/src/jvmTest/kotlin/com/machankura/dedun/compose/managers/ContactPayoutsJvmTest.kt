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
import com.machankura.dedun.compose.nostr.wallet.ContactClaimEvent
import com.machankura.dedun.compose.nostr.wallet.ContactPayoutDestinationEvent
import com.machankura.dedun.compose.nostr.wallet.ContactRecordEvent
import com.machankura.dedun.compose.nostr.wallet.GroupPayoutDestination
import com.machankura.dedun.compose.nostr.wallet.GroupPayoutDestinationEvent
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthOnchainPayoutEvent
import com.machankura.dedun.compose.text.LightningAddress
import com.machankura.dedun.compose.wallet.PayoutRule
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import fr.acinq.bitcoin.Bitcoin
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.secp256k1.Hex
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The group paying a contact who hasn't joined: at the destination it signed for them, under the
 * rules a member's payout follows. `docs/non-member-deposits.md`, D9 and Phase 6.
 */
class ContactPayoutsJvmTest {
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
    private val mumsAddress = Bitcoin.computeP2WpkhAddress(key("ad").publicKey(), chain.chainHash)

    private var clock = 1_700_000_000L

    private fun proposal(template: EventTemplate<*>): Event = signedAsRoom(group, template.kind, template.tags, template.content, ++clock)

    private suspend fun signed(template: EventTemplate<*>): Event = proposal(template).also {
        db.groupSignedEventDao().upsert(GroupSignedEvent.fromEvent(it, chatRoomId = room, derivationPath = null, frostSigningSessionId = null))
    }

    private suspend fun setUp(): Event {
        GroupFixtures.seedRoom(db, room)
        listOf(alice, bob).forEach { GroupFixtures.seedProfile(db, it) }
        db.participantDao().upsert(listOf(alice, bob).map { Participant(participantPublicKey = it, chatRoomId = room, relayHint = null) })
        listOf(alice, bob).forEach { Contacts.announce(db, room, it) }
        signed(ContactRecordEvent.template(room, alice, "Mum", mum))
        return signed(ContactPayoutDestinationEvent.template(mumId, chain, null, mumsAddress))
    }

    @Test
    fun `a contact's onchain address is in the payout context, so a payout of their coins can pay it`(): Unit = runBlocking {
        setUp()
        val context = MemberBalances.payoutContextOf(db, room, chain)
        assertEquals(
            com.machankura.dedun.compose.wallet.PayoutAddress.parseOrNull(chain, mumsAddress)!!.script,
            context.scripts[mumId],
        )
        assertTrue(context is PayoutRule.Context)
    }

    @Test
    fun `a Wavelength payout to a contact is checked against the destination the group signed for them`(): Unit = runBlocking {
        val destination = setUp()
        fun payout(contact: Boolean, destinationId: String = destination.id) =
            GroupWavelengthOnchainPayoutEvent.paying(chain, 0, mumId, destinationId, mumsAddress, 10_000, 500, now = clock, contact = contact)
        suspend fun refusal(payout: GroupWavelengthOnchainPayoutEvent.Payout) =
            GroupWavelengthPayouts.refuse(db, room, proposal(GroupWavelengthOnchainPayoutEvent.template(payout)), nowSeconds = clock)

        // Past the destination's checks: what stops it now is that Mum holds nothing in the Wavelength wallet.
        val toMum = refusal(payout(contact = true))
        assertNotNull(toMum)
        listOf("nowhere to be paid", "since replaced", "member when it is a contact", "no onchain address", "other than the one").forEach {
            assertTrue(it !in toMum, "refused for the destination: $toMum")
        }
        assertEquals("pays a destination the member has since replaced", refusal(payout(contact = true, destinationId = "ee".repeat(32))))

        signed(ContactClaimEvent.template(mumId, bob))
        assertEquals("pays a member who has given nowhere to be paid", refusal(payout(contact = true)))
    }

    @Test
    fun `a destination is signed only at an address nobody else is paid at, and never for a claimed contact`(): Unit = runBlocking {
        setUp()
        val own = GroupPayoutDestinationEvent.verified(
            GroupPayoutDestinationEvent.build(aliceKey, room, chain, LightningAddress("alice", "example.com"), onchainAddress = Bitcoin.computeP2WpkhAddress(key("a1").publicKey(), chain.chainHash), createdAt = 1_700_000_000)
        ) as GroupPayoutDestination
        GroupPayoutDestinations.publish(db, room, own.event)

        val atAlices = ContactPayoutDestinationEvent.template(mumId, chain, null, own.onchainAddress)
        assertEquals("pays a contact at an address somebody else is paid at", Contacts.refuse(db, room, proposal(atAlices)))
        assertNull(Contacts.refuse(db, room, proposal(ContactPayoutDestinationEvent.template(mumId, chain, LightningAddress("mum", "example.com"), mumsAddress))))
        assertNull(Contacts.refuse(db, room, proposal(ContactPayoutDestinationEvent.removedTemplate(mumId, chain))))

        signed(ContactClaimEvent.template(mumId, bob))
        assertNotEquals(null, Contacts.refuse(db, room, proposal(ContactPayoutDestinationEvent.template(mumId, chain, null, mumsAddress))))
    }
}
