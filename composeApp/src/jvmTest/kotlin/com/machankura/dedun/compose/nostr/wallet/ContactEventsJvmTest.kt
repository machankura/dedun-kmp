package com.machankura.dedun.compose.nostr.wallet

import com.machankura.dedun.compose.contacts.ContactIdentifier
import com.machankura.dedun.compose.contacts.Party
import com.machankura.dedun.compose.managers.DepositBranch
import com.machankura.dedun.compose.managers.GroupFixtures.keyMaterial
import com.machankura.dedun.compose.managers.GroupFixtures.roomOf
import com.machankura.dedun.compose.managers.GroupFixtures.signedAsRoom
import com.machankura.dedun.compose.nostr.lightning.GroupLightningAuthorisationEvent
import com.machankura.dedun.compose.nostr.wallet.ContactAmendmentEvent.Change
import com.machankura.dedun.compose.nostr.wallet.DepositAttributionEvent.Attribution
import com.machankura.dedun.compose.nostr.wallet.DepositAttributionEvent.DepositKey
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthOnchainPayoutEvent
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthPaymentEvent
import com.machankura.dedun.compose.text.LightningAddress
import com.vitorpamplona.quartz.nip01Core.core.Event
import fr.acinq.bitcoin.Bitcoin
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.crypto.frost.KeyMaterial
import fr.acinq.secp256k1.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The six contact kinds and the payee slots that name a contact, read and believed -- one
 * mutation per check. `docs/non-member-deposits.md`, Phase 1.
 */
open class ContactEventsJvmTest {
    private val chain = Chain.Regtest
    private val group = keyMaterial("6a0b")
    private val room = roomOf(group)
    private val other = keyMaterial("9a9e")

    private fun key(seed: String) = PrivateKey(ByteVector32(Hex.decode(seed.padStart(64, '0'))))
    private val alicePublicKey = key("a11ce").xOnlyPublicKey().value.toHex()
    private val bobPublicKey = key("b0b").xOnlyPublicKey().value.toHex()

    private val mum = ContactIdentifier.Phone("+27821234567")
    private val mumId = mum.contactId(room)

    private fun signed(kind: Int, tags: Array<Array<String>>, createdAt: Long = 1_700_000_100, by: KeyMaterial = group): Event =
        signedAsRoom(by, kind, tags, "", createdAt)

    private fun recordTags(name: String = "Mum", identifier: ContactIdentifier = mum) =
        ContactRecordEvent.template(room, alicePublicKey, name, identifier, 1_700_000_100).tags

    private fun Array<Array<String>>.replacing(name: String, vararg value: String) =
        map { if (it[0] == name) arrayOf(name, *value) else it }.toTypedArray()

    private fun Array<Array<String>>.withTag(vararg tag: String) = (toList() + listOf(arrayOf(*tag))).toTypedArray()

    private val address = Bitcoin.computeP2WpkhAddress(key("ad").publicKey(), chain.chainHash)

    @Test
    fun `a record reads and is believed, and its id is the number's`() {
        val record = assertNotNull(ContactRecordEvent.verify(signed(ContactRecordEvent.KIND, recordTags()), room))
        assertEquals(mumId, record.contactId)
        assertEquals("Mum", record.name)
        assertEquals(mum, record.identifier)
        assertEquals(alicePublicKey, record.proposer)
        assertNotNull(ContactRecordEvent.verify(signed(ContactRecordEvent.KIND, recordTags(identifier = ContactIdentifier.Email("mum@example.com"))), room))
    }

    @Test
    fun `a record another room signed is not this room's`() {
        assertNull(ContactRecordEvent.verify(signed(ContactRecordEvent.KIND, recordTags(), by = other), room))
    }

    @Test
    fun `an id that doesn't recompute is refused, a member's key above all`() {
        assertNull(ContactRecordEvent.verify(signed(ContactRecordEvent.KIND, recordTags().replacing("d", alicePublicKey)), room))
        assertNull(ContactRecordEvent.verify(signed(ContactRecordEvent.KIND, recordTags().replacing("d", ContactIdentifier.Phone("+27821234568").contactId(room))), room))
    }

    @Test
    fun `an identifier that doesn't normalise to itself is refused`() {
        listOf("+27 82 123 4567", "0821234567", "27821234567").forEach { text ->
            val tags = recordTags().replacing("identifier", "phone", text).replacing("d", ContactIdentifier.Phone("+27821234567").contactId(room))
            assertNull(ContactRecordEvent.verify(signed(ContactRecordEvent.KIND, tags), room), text)
        }
        assertNull(ContactRecordEvent.verify(signed(ContactRecordEvent.KIND, recordTags().replacing("identifier", "fax", "+27821234567")), room))
    }

    @Test
    fun `a name a contact may not have is refused`() {
        assertNull(ContactRecordEvent.verify(signed(ContactRecordEvent.KIND, recordTags().replacing("name", "")), room))
        assertNull(ContactRecordEvent.verify(signed(ContactRecordEvent.KIND, recordTags().replacing("name", " Mum")), room))
        assertNull(ContactRecordEvent.verify(signed(ContactRecordEvent.KIND, recordTags().replacing("name", "m".repeat(65))), room))
        assertNotNull(ContactRecordEvent.verify(signed(ContactRecordEvent.KIND, recordTags().replacing("name", "m".repeat(64))), room))
    }

    @Test
    fun `a second record for one number is a second, and the first stands`() {
        val first = signed(ContactRecordEvent.KIND, recordTags(name = "Mum"), createdAt = 1_700_000_100)
        val second = signed(ContactRecordEvent.KIND, recordTags(name = "Gogo"), createdAt = 1_700_000_200)
        assertEquals("Mum", ContactRecordEvent.firstPerContact(listOf(second, first), room)[mumId]?.name)
    }

    @Test
    fun `amendments fold in order, and never carry an identifier`() {
        fun amendment(change: Change, at: Long) = signed(ContactAmendmentEvent.KIND, ContactAmendmentEvent.template(mumId, change, at).tags, at)
        val states = ContactAmendmentEvent.stateOf(
            listOf(amendment(Change.Rename("Gogo"), 300), amendment(Change.Close, 200), amendment(Change.Rename("Ma"), 100)),
            room,
        )
        assertEquals(ContactAmendmentEvent.State("Gogo", closed = true), states[mumId])
        assertFalse(ContactAmendmentEvent.stateOf(listOf(amendment(Change.Close, 100), amendment(Change.Reopen, 200)), room)[mumId]!!.closed)

        val withIdentifier = ContactAmendmentEvent.template(mumId, Change.Rename("Gogo"), 100).tags.withTag("identifier", "phone", "+27821234568")
        assertNull(ContactAmendmentEvent.verify(signed(ContactAmendmentEvent.KIND, withIdentifier), room))
        val twoChanges = ContactAmendmentEvent.template(mumId, Change.Close, 100).tags.withTag("open")
        assertNull(ContactAmendmentEvent.verify(signed(ContactAmendmentEvent.KIND, twoChanges), room))
    }

    @Test
    fun `a contact's branch recomputes, as a member's does`() {
        val tags = ContactDepositorStatementEvent.template(room, mumId, chain, salt = 0, createdAt = 100).tags
        val depositor = assertNotNull(ContactDepositorStatementEvent.verify(signed(ContactDepositorStatementEvent.KIND, tags), room))
        assertEquals(DepositBranch.of(room, mumId), depositor.branch)
        assertTrue(DepositBranch.isMemberBranch(depositor.branch))
        assertNull(ContactDepositorStatementEvent.verify(signed(ContactDepositorStatementEvent.KIND, tags.replacing("branch", "0", "0")), room))
        assertNull(ContactDepositorStatementEvent.verify(signed(ContactDepositorStatementEvent.KIND, tags.replacing("d", "regtest:$alicePublicKey")), room))
        val salted = ContactDepositorStatementEvent.template(room, mumId, chain, salt = 1, createdAt = 200).tags
        assertEquals(0, ContactDepositorStatementEvent.firstPerContact(
            listOf(signed(ContactDepositorStatementEvent.KIND, salted, 200), signed(ContactDepositorStatementEvent.KIND, tags, 100)), room, chain,
        )[mumId]?.salt)
    }

    @Test
    fun `the newest claim wins, and none undoes one`() {
        fun claim(member: String?, at: Long) = signed(ContactClaimEvent.KIND, ContactClaimEvent.template(mumId, member, at).tags, at)
        assertEquals(mapOf(mumId to bobPublicKey), ContactClaimEvent.claimsIn(listOf(claim(alicePublicKey, 100), claim(bobPublicKey, 200)), room))
        assertEquals(emptyMap(), ContactClaimEvent.claimsIn(listOf(claim(alicePublicKey, 100), claim(null, 200)), room))
        assertNull(ContactClaimEvent.verify(signed(ContactClaimEvent.KIND, ContactClaimEvent.template(mumId, alicePublicKey, 100).tags.withTag("none")), room))
        assertNull(ContactClaimEvent.verify(signed(ContactClaimEvent.KIND, ContactClaimEvent.template(mumId, mumId, 100).tags), room))
        assertNull(ContactClaimEvent.verify(signed(ContactClaimEvent.KIND, ContactClaimEvent.template(mumId, alicePublicKey, 100).tags, by = other), room))
    }

    @Test
    fun `a destination has an address or says removed, never both, never neither`() {
        val both = ContactPayoutDestinationEvent.template(mumId, chain, LightningAddress("mum", "example.com"), address, 100).tags
        val destination = assertNotNull(ContactPayoutDestinationEvent.verify(signed(ContactPayoutDestinationEvent.KIND, both), room))
        assertEquals(address, destination.onchainAddress)
        assertEquals(LightningAddress("mum", "example.com"), destination.lightningAddress)

        val onchainOnly = ContactPayoutDestinationEvent.template(mumId, chain, null, address, 100).tags
        assertNull(assertNotNull(ContactPayoutDestinationEvent.verify(signed(ContactPayoutDestinationEvent.KIND, onchainOnly), room)).lightningAddress)

        val neither = onchainOnly.filterNot { it[0] == "onchain" }.toTypedArray()
        assertNull(ContactPayoutDestinationEvent.verify(signed(ContactPayoutDestinationEvent.KIND, neither), room))
        assertNull(ContactPayoutDestinationEvent.verify(signed(ContactPayoutDestinationEvent.KIND, both.withTag("removed")), room))
        val mainnetAddress = Bitcoin.computeP2WpkhAddress(key("ad").publicKey(), Chain.Mainnet.chainHash)
        assertNull(ContactPayoutDestinationEvent.verify(signed(ContactPayoutDestinationEvent.KIND, onchainOnly.replacing("onchain", mainnetAddress)), room))

        val removed = ContactPayoutDestinationEvent.removedTemplate(mumId, chain, 200).tags
        val newest = ContactPayoutDestinationEvent.newestPerContact(
            listOf(signed(ContactPayoutDestinationEvent.KIND, both, 100), signed(ContactPayoutDestinationEvent.KIND, removed, 200)), room, chain,
        )
        assertTrue(newest[mumId]!!.removed)
    }

    @Test
    fun `a request reads back what it asks`() {
        val reads = ContactRequestEvent.reads(room, 100)
        val readsEvent = Event("00".repeat(32), alicePublicKey, 100, ContactRequestEvent.KIND, reads.tags, "", "")
        assertEquals(ContactRequestEvent.Request.Reads(room.lowercase(), setOf(ContactRequestEvent.CONTACTS_V1)), ContactRequestEvent.read(readsEvent))

        val record = ContactRecordEvent.template(room, alicePublicKey, "Mum", mum, 100)
        val propose = ContactRequestEvent.propose(room, record, 100)
        val proposeEvent = Event("00".repeat(32), alicePublicKey, 100, ContactRequestEvent.KIND, propose.tags, "", "")
        val read = assertNotNull(ContactRequestEvent.read(proposeEvent) as? ContactRequestEvent.Request.Propose)
        assertEquals(ContactRecordEvent.KIND, read.kind)
        assertTrue(read.tags.indices.all { read.tags[it].contentEquals(record.tags[it]) })

        val wrongKind = propose.tags.replacing("template", """{"kind":1,"tags":[],"content":""}""")
        assertNull(ContactRequestEvent.read(Event("00".repeat(32), alicePublicKey, 100, ContactRequestEvent.KIND, wrongKind, "", "")))
    }

    @Test
    fun `an attribution names a contact on the wire, and a malformed one is no attribution at all`() {
        val key = DepositKey.Onchain("ab".repeat(32), 0)
        val template = DepositAttributionEvent.template(chain, listOf(Attribution(key, mumId, 1_000, contact = true), Attribution(DepositKey.Onchain("ab".repeat(32), 1), alicePublicKey, 2_000)), 100)
        assertTrue(template.tags.any { it.size >= 3 && it[2] == "contact:$mumId" })
        val read = assertNotNull(DepositAttributionEvent.verify(signed(template.kind, template.tags), room)).attributions
        assertEquals(Party.Contact(mumId), read[0].party)
        assertEquals(Party.Member(alicePublicKey), read[1].party)

        val malformed = template.tags.map { if (it.size >= 3 && it[2] == "contact:$mumId") arrayOf(it[0], it[1], "contact:${mumId.drop(2)}", it[3]) else it }.toTypedArray()
        assertNull(DepositAttributionEvent.verify(signed(template.kind, malformed), room))
    }

    @Test
    fun `a payout names a contact as payee on every rail`() {
        assertEquals(GroupLightningAuthorisationEvent.Payout(mumId, "ee".repeat(32), contact = true), GroupLightningAuthorisationEvent.Payout.of("contact:$mumId", "ee".repeat(32)))
        assertEquals("contact:$mumId", GroupLightningAuthorisationEvent.Payout(mumId, "ee", contact = true).wire)
        assertEquals(GroupWavelengthPaymentEvent.Payout(mumId, "ee", contact = true), GroupWavelengthPaymentEvent.Payout.of("contact:$mumId", "ee"))
        assertEquals(GroupWavelengthPaymentEvent.Payout(alicePublicKey, "ee"), GroupWavelengthPaymentEvent.Payout.of(alicePublicKey, "ee"))

        val payout = GroupWavelengthOnchainPayoutEvent.paying(chain, 0, mumId, "ee".repeat(32), address, 10_000, 500, now = 1_700_000_000, contact = true)
        val tags = GroupWavelengthOnchainPayoutEvent.template(payout).tags
        assertTrue(tags.any { it[0] == "payout" && it[1] == "contact:$mumId" })
        assertEquals(payout, GroupWavelengthOnchainPayoutEvent.read(tags))
        // A build from before contacts reads the payee as `contact:…`, which the label's bare id doesn't match: no payout.
        assertTrue(tags.first { it[0] == "d" }[1].contains(":$mumId:"))
    }
}
