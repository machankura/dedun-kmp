package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthPaymentEvent
import com.machankura.dedun.compose.wallet.wavelength.group.ArkPolicy
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.ByteVector32
import kotlin.time.Clock

/**
 * The group deciding what its Wavelength wallet pays (`docs/group-wavelength-wallet.md`, Phase 11). Anybody can propose, and a payment
 * exists once a quorum of the group signs it: until then, no member's device will pay it, whatever the daemon asks.
 */
object GroupWavelengthPayments {
    /** Asks the group to approve a payment. [maxRefundHeight] is the longest it may tie the money up if the payment fails. */
    suspend fun propose(database: DedunDatabase, localChatRoom: LocalChatRoom, userPublicKey: HexKey, wallet: GroupWavelengthWalletManager.Wallet, invoice: String, maxFeeSat: Long, maxRefundHeight: Long, expiresAt: Long): FrostSigningSession {
        val payment = GroupWavelengthPaymentEvent.Payment(wallet.statement.chain, wallet.statement.wallet, invoice, maxFeeSat, maxRefundHeight, expiresAt)
        requireNotNull(payment.amountSat) { "That invoice names no amount, so there is nothing to approve" }
        val template = GroupWavelengthPaymentEvent.template(payment)
        return FrostSigningManager.proposeSigning(database, localChatRoom, userPublicKey, template.kind, template.tags, template.content)
    }

    /** What the group has approved for [wallet] that hasn't expired: signed by the room, for this chain and wallet number. */
    suspend fun approved(database: DedunDatabase, chatRoomId: String, wallet: GroupWavelengthWalletManager.Wallet, now: Long = Clock.System.now().epochSeconds): Map<ByteVector32, ArkPolicy.ApprovedPayment> =
        database.groupSignedEventDao().getByChatRoomIdAndKind(chatRoomId, GroupWavelengthPaymentEvent.KIND)
            .filter { it.verifies() }
            .mapNotNull { GroupWavelengthPaymentEvent.read(it.tags) }
            .filter { it.chain == wallet.statement.chain && it.wallet == wallet.statement.wallet && it.expiresAt > now }
            .mapNotNull { payment ->
                val hash = payment.paymentHash ?: return@mapNotNull null
                val maxSat = payment.maxSat ?: return@mapNotNull null
                hash to ArkPolicy.ApprovedPayment(maxSat, payment.maxRefundHeight)
            }
            .toMap()
}
