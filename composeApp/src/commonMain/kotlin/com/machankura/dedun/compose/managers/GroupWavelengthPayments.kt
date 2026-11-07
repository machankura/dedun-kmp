package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthPaymentEvent
import com.machankura.dedun.compose.wallet.wavelength.group.ArkPolicy
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.ByteVector32
import kotlin.time.Clock

/**
 * The group deciding what its Wavelength wallet pays (`docs/group-wavelength-wallet.md`, Phase 11). Anybody can propose, and a payment
 * exists once a quorum of the group signs it: until then, no member's device will pay it, whatever the daemon asks.
 */
object GroupWavelengthPayments {
    /** Asks the group to approve a payment. [maxRefundHeight] is the longest it may tie the money up if the payment fails. */
    suspend fun propose(database: DedunDatabase, localChatRoom: LocalChatRoom, userPublicKey: HexKey, wallet: GroupWavelengthWalletManager.Wallet, invoice: String, maxFeeSat: Long, maxRefundHeight: Long, expiresAt: Long, payout: GroupWavelengthPaymentEvent.Payout, lnurl: String?): FrostSigningSession {
        val payment = GroupWavelengthPaymentEvent.Payment(wallet.statement.chain, wallet.statement.wallet, invoice, maxFeeSat, maxRefundHeight, expiresAt, payout, lnurl)
        requireNotNull(payment.amountSat) { "That invoice names no amount, so there is nothing to approve" }
        val template = GroupWavelengthPaymentEvent.template(payment)
        // The proposer's device takes it in as anyone else's would.
        val asEvent = Event("00".repeat(32), "00".repeat(32), 0, template.kind, template.tags, template.content, "")
        GroupWavelengthPayouts.refuse(database, localChatRoom.chatRoom.id, asEvent)?.let { throw IllegalArgumentException("This payment $it") }
        return FrostSigningManager.proposeSigning(database, localChatRoom, userPublicKey, template.kind, template.tags, template.content)
    }

    /**
     * What the group has approved for [wallet] that hasn't expired, the same one per hash that a member's balance is charged with, and
     * only where everything open for that member fits their balance by this device's own count: a device that missed a payment the
     * quorum's other members saw would approve the same money twice (`docs/group-wavelength-payouts.md`, "What the review found").
     */
    suspend fun approved(database: DedunDatabase, chatRoomId: String, wallet: GroupWavelengthWalletManager.Wallet, now: Long = Clock.System.now().epochSeconds): Map<ByteVector32, ArkPolicy.ApprovedPayment> {
        val balances = GroupWavelengthPayouts.balancesIn(database, chatRoomId, wallet.statement.chain, now)
        return GroupWavelengthPayouts.signed(database, chatRoomId, wallet.statement.chain)
            .filter { it.wallet == wallet.statement.wallet && it.expiresAt > now }
            .filter { balances[it.payout?.member?.lowercase()]?.bearsWhatsOpen == true }
            .mapNotNull { payment ->
                val hash = payment.paymentHash ?: return@mapNotNull null
                val maxSat = payment.maxSat ?: return@mapNotNull null
                hash to ArkPolicy.ApprovedPayment(maxSat, payment.maxRefundHeight)
            }
            .toMap()
    }
}
