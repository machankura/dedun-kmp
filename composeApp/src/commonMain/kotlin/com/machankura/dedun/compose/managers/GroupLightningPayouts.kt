package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.DepositRow
import com.machankura.dedun.compose.nostr.lightning.GroupLightningAuthorisationEvent
import com.machankura.dedun.compose.wallet.lightning.LightningBalance
import com.vitorpamplona.quartz.nip01Core.core.Event
import fr.acinq.bitcoin.Crypto
import fr.acinq.lightning.payment.Bolt11Invoice
import fr.acinq.bitcoin.byteVector32
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Clock

/**
 * The one payment the group's Lightning wallet authorises: a payout of a member's own Lightning
 * balance to their own Lightning address. `docs/payouts-only.md`, D8 and Phase 6.
 *
 * What a signer can check, and does: that the payment is labelled for a member who has said where
 * to be paid, for their destination as it stands; that it and its fee limit fit what they have in;
 * and, where the authorisation carries the LNURL metadata, that the invoice commits to it and it
 * names their address. What nobody can check is that the invoice pays them: an address's server
 * signs nothing. A lying proposer can misdirect one member's Lightning balance, and the room's
 * signing session says who proposed it.
 */
object GroupLightningPayouts {

    /**
     * Why a signer won't sign [proposed], a Lightning authorisation for [chatRoomId], or null when
     * it will. A close or a force close passes: it pays the group's own wallet, and refusing one could
     * strand the money with an operator who has gone. Buying liquidity is refused: it pays the LSP,
     * and nobody's balance is charged with it until the group has a rule for sharing costs.
     */
    suspend fun refuse(database: DedunDatabase, chatRoomId: String, proposed: Event, nowSeconds: Long = Clock.System.now().epochSeconds): String? {
        if (GroupLightningAuthorisationEvent.readBuyLiquidity(proposed.tags) != null) {
            return "buys liquidity, which pays the LSP and no member"
        }
        val pay = GroupLightningAuthorisationEvent.read(proposed.tags) ?: return null
        val payout = pay.payout ?: return "pays somebody it does not name as a member"

        val destination = GroupPayoutDestinations.payoutDestinationOf(database, chatRoomId, pay.chain, payout.member)
            ?: return "pays a member who has given nowhere to be paid"
        if (!destination.event.id.equals(payout.destinationEventId, ignoreCase = true)) {
            return "pays a destination the member has since replaced"
        }

        pay.lnurl?.let { metadata ->
            val invoice = runCatching { Bolt11Invoice.read(pay.invoice).get() }.getOrNull() ?: return "carries an invoice that does not read"
            if (invoice.descriptionHash != Crypto.sha256(metadata.encodeToByteArray()).byteVector32()) {
                return "carries an invoice that does not commit to the metadata beside it"
            }
            if (!names(metadata, destination.lightningAddress.toString())) {
                return "carries metadata that does not name the member's Lightning address"
            }
        }

        val available = balanceOf(database, chatRoomId, pay, nowSeconds).availableMsat
        if (pay.budgetMsat > available) {
            return "pays ${pay.budgetMsat / 1000} sats with its fee limit, more than the ${available / 1000} sats of the member's Lightning balance"
        }
        return null
    }

    /** [pay]'s member's Lightning balance on this device, before [pay]. */
    private suspend fun balanceOf(database: DedunDatabase, chatRoomId: String, pay: GroupLightningAuthorisationEvent.Pay, nowSeconds: Long): LightningBalance.Balance {
        val member = pay.payout!!.member
        val deposited = DepositLedger.depositsIn(database, chatRoomId, pay.chain)
            .filter { it.source == DepositRow.LIGHTNING && it.member.equals(member, ignoreCase = true) }
            .sumOf { it.amountMsat }
        val signed = DepositLedger.signedPayouts(database, chatRoomId, pay.chain).filter { !it.paymentHash.equals(pay.paymentHash, ignoreCase = true) }
        val sentFees = signed.map { it.wallet }.distinct()
            .flatMap { DepositLedger.sentFees(database, chatRoomId, pay.chain, it).entries }
            .associate { it.key to it.value }
        return LightningBalance.of(member, deposited, signed, sentFees, nowSeconds)
    }

    /** Whether LUD-06 [metadata] names [address] as its `text/identifier` or `text/email`, as LUD-16 asks. */
    internal fun names(metadata: String, address: String): Boolean = runCatching {
        Json.parseToJsonElement(metadata).jsonArray.any { entry ->
            val pair = entry.jsonArray
            pair.size >= 2 && pair[0].jsonPrimitive.content in IDENTIFIERS && pair[1].jsonPrimitive.content.trim().equals(address, ignoreCase = true)
        }
    }.getOrDefault(false)

    private val IDENTIFIERS = setOf("text/identifier", "text/email")
}
