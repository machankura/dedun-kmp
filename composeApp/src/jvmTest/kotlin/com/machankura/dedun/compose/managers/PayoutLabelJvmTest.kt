package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.nostr.lightning.GroupLightningAuthorisationEvent
import com.machankura.dedun.compose.nostr.wallet.GroupPayoutDestination
import com.machankura.dedun.compose.nostr.wallet.GroupPayoutDestinationEvent
import com.machankura.dedun.compose.text.LightningAddress
import com.machankura.dedun.compose.text.ProposedEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import fr.acinq.bitcoin.Bitcoin
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.CltvExpiryDelta
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.payment.Bolt11Invoice
import fr.acinq.lightning.utils.msat
import fr.acinq.secp256k1.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Naming a payment for a member -- `docs/group-payout-destination.md`, Phase 7. Over Lightning the
 * name is the proposer's word, a tag every build may ignore; onchain it is this device's own match
 * of the output against the member's signed address.
 */
class PayoutLabelJvmTest {
    private val chain = Chain.Regtest
    private fun key(seed: String) = PrivateKey(ByteVector32(Hex.decode(seed.padStart(64, '0'))))
    private val alice = key("a11ce")
    private val alicePublicKey = alice.xOnlyPublicKey().value.toHex()
    private val room = "9a17".repeat(16)

    private val bolt11 = Bolt11Invoice.create(chain, 21_000_000.msat, randomBytes32(), key("70de"), Either.Left("payout"), CltvExpiryDelta(144), INVOICE_FEATURES).write()
    private val hash = Bolt11Invoice.read(bolt11).get().paymentHash.toHex()

    private fun pay(payout: GroupLightningAuthorisationEvent.Payout?) =
        GroupLightningAuthorisationEvent.Pay(chain, 0, bolt11, 21_000_000, 210_000, hash, Bolt11Invoice.read(bolt11).get().timestampSeconds + 60, payout)

    @Test
    fun `a payment names the member's destination, and reads back with it`() {
        val payout = GroupLightningAuthorisationEvent.Payout(alicePublicKey, "de".repeat(32))
        val tags = GroupLightningAuthorisationEvent.template(pay(payout)).tags
        assertTrue(tags.any { it.toList() == listOf("payout", alicePublicKey, "de".repeat(32)) })
        assertEquals(payout, GroupLightningAuthorisationEvent.read(tags)?.payout)
    }

    @Test
    fun `a payment without a destination has no tag, and reads as before`() {
        val tags = GroupLightningAuthorisationEvent.template(pay(null)).tags
        assertTrue(tags.none { it[0] == "payout" })
        assertNull(GroupLightningAuthorisationEvent.read(tags)?.payout)
    }

    @Test
    fun `the summary names the member, and says whose word it is`() {
        val template = GroupLightningAuthorisationEvent.template(pay(GroupLightningAuthorisationEvent.Payout(alicePublicKey, "de".repeat(32))))
        val event = Event("00".repeat(32), room, template.createdAt, template.kind, template.tags, template.content, "")
        val summary = ProposedEvent.summarize(event) { if (it == alicePublicKey) "alice" else null }
        assertEquals("Pay 21,000 sats", summary.label)
        assertTrue(summary.detail.startsWith("To @alice's Lightning address, as the proposer resolved it"), summary.detail)
    }

    @Test
    fun `an output paying a member's signed address is named, any other is not`() {
        val address = Bitcoin.computeBIP86Address(key("f1").publicKey(), chain.chainHash)
        val destination = GroupPayoutDestinationEvent.verified(
            GroupPayoutDestinationEvent.build(alice, room, chain, LightningAddress("alice", "example.com"), onchainAddress = address)
        ) as GroupPayoutDestination
        val theirs = ByteVector(Script.write(Bitcoin.addressToPublicKeyScript(chain.chainHash, address).right!!))
        val somebodyElses = ByteVector(Script.write(Script.pay2wpkh(key("f2").publicKey())))

        val payees = GroupPayoutDestinations.payeesOf(listOf(theirs, somebodyElses), listOf(destination)) { "alice" }
        assertEquals(mapOf(theirs.toHex() to "alice"), payees)
    }
}
