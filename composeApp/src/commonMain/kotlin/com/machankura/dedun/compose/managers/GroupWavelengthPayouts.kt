package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthExitEvent
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthPaymentEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.machankura.dedun.compose.database.model.DepositRow
import fr.acinq.bitcoin.Chain
import kotlin.time.Clock

/**
 * A group's Wavelength wallet pays only its members, from what they put in: `docs/payouts-only.md`, as the other two wallets do.
 *
 * A member's balance is their deposits less what's been paid to them, shrunk in proportion to what's actually left in the wallet by this
 * device's own count. So fees for renewing and leaving, which belong to nobody, fall on everybody in proportion to what they hold. That is
 * a choice the plan left open, and the simplest one that can't pay out more than there is. A device with no record of the wallet's coins
 * sees nothing to pay, which is the safe way round: it won't sign a payout it can't check.
 */
object GroupWavelengthPayouts {
    data class Balance(val depositedSat: Long = 0, val paidSat: Long = 0, val openSat: Long = 0, val availableSat: Long = 0) {
        companion object {
            val NONE = Balance()
        }
    }

    /** Every payout the group has signed on [chain] for any wallet, newest statement per payment. */
    suspend fun signed(database: DedunDatabase, chatRoomId: String, chain: Chain): List<GroupWavelengthPaymentEvent.Payment> =
        database.groupSignedEventDao().getByChatRoomIdAndKind(chatRoomId, GroupWavelengthPaymentEvent.KIND)
            .filter { it.verifies() }
            .mapNotNull { GroupWavelengthPaymentEvent.read(it.tags) }
            .filter { it.chain == chain && it.payout != null }
            .distinctBy { it.paymentHash }

    /** Every request the group has signed for [wallet] to leave, whenever it was. */
    suspend fun signedExits(database: DedunDatabase, chatRoomId: String, chain: Chain, wallet: Long): List<GroupWavelengthExitEvent.Exit> =
        database.groupSignedEventDao().getByChatRoomIdAndKind(chatRoomId, GroupWavelengthExitEvent.KIND)
            .filter { it.verifies() }
            .mapNotNull { GroupWavelengthExitEvent.read(it.tags) }
            .filter { it.chain == chain && it.wallet == wallet }

    /** Asks the group to leave [wallet] for its onchain wallet. */
    suspend fun proposeExit(database: DedunDatabase, localChatRoom: LocalChatRoom, userPublicKey: HexKey, wallet: GroupWavelengthWalletManager.Wallet) =
        GroupWavelengthExitEvent.template(GroupWavelengthExitEvent.asking(wallet.statement.chain, wallet.statement.wallet)).let { template ->
            FrostSigningManager.proposeSigning(database, localChatRoom, userPublicKey, template.kind, template.tags, template.content)
        }

    /** Whether this device signed the transfer that makes [payment], which from then on can't be assumed not to have left. */
    private suspend fun wentOut(database: DedunDatabase, chatRoomId: String, payment: GroupWavelengthPaymentEvent.Payment): Boolean {
        val key = GroupArkCoSigner.walletKey(chatRoomId, payment.chain, payment.wallet)
        val hash = payment.paymentHash?.toString() ?: return false
        return database.groupArkDao().payments(key).any { it.paymentHash.equals(hash, ignoreCase = true) }
    }

    /** Every member with a Wavelength deposit or payout on [chain], and what they can be paid. */
    suspend fun balancesIn(database: DedunDatabase, chatRoomId: String, chain: Chain, nowSeconds: Long = Clock.System.now().epochSeconds): Map<HexKey, Balance> {
        val deposited = DepositLedger.depositsIn(database, chatRoomId, chain)
            .filter { it.source == DepositRow.WAVELENGTH && it.member != null }
            .groupBy({ it.member!!.lowercase() }, { it.amountMsat / 1000 })
            .mapValues { it.value.sum() }
        val paid = HashMap<HexKey, Long>()
        val open = HashMap<HexKey, Long>()
        signed(database, chatRoomId, chain).forEach { payment ->
            val member = payment.payout!!.member.lowercase()
            val cost = payment.maxSat ?: return@forEach
            when {
                wentOut(database, chatRoomId, payment) -> paid[member] = (paid[member] ?: 0) + cost
                payment.expiresAt > nowSeconds -> open[member] = (open[member] ?: 0) + cost
            }
        }
        val net = (deposited.keys + paid.keys + open.keys).associateWith { ((deposited[it] ?: 0) - (paid[it] ?: 0)).coerceAtLeast(0) }
        val totalNet = net.values.sum()
        val inTheWallet = GroupWavelengthWalletManager.wallets(database, chatRoomId, chain).sumOf { wallet ->
            database.groupArkDao().vtxos(GroupArkCoSigner.walletKey(chatRoomId, chain, wallet.statement.wallet)).filter { it.settled && !it.spent }.sumOf { it.amountSat }
        }
        return net.mapValues { (member, sat) ->
            val share = if (totalNet == 0L || inTheWallet >= totalNet) sat else sat * inTheWallet / totalNet
            Balance(deposited[member] ?: 0, paid[member] ?: 0, open[member] ?: 0, (share - (open[member] ?: 0)).coerceAtLeast(0))
        }
    }

    /** Why a signer won't sign [proposed], a Wavelength payment for [chatRoomId], or null when it will. */
    suspend fun refuse(database: DedunDatabase, chatRoomId: String, proposed: Event, nowSeconds: Long = Clock.System.now().epochSeconds): String? {
        // Leaving pays only the group's own onchain wallet, which the policy checks when it's signed (D9).
        if (GroupWavelengthExitEvent.read(proposed.tags) != null) return null
        val payment = GroupWavelengthPaymentEvent.read(proposed.tags) ?: return "isn't a payment this device can read"
        val payout = payment.payout ?: return "pays somebody it does not name as a member"
        val destination = GroupPayoutDestinations.payoutDestinationOf(database, chatRoomId, payment.chain, payout.member)
            ?: return "pays a member who has given nowhere to be paid"
        if (!destination.event.id.equals(payout.destinationEventId, ignoreCase = true)) return "pays a destination the member has since replaced"
        val cost = payment.maxSat ?: return "names no amount"
        val available = balancesIn(database, chatRoomId, payment.chain, nowSeconds)[payout.member.lowercase()]?.availableSat ?: 0
        if (cost > available) return "pays $cost sats with its fee limit, more than the $available sats of the member's balance in this wallet"
        return null
    }
}
