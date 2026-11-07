package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthExitEvent
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthOnchainPayoutEvent
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthPaymentEvent
import com.machankura.dedun.compose.wallet.wavelength.group.ArkPolicy
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.machankura.dedun.compose.database.model.DepositRow
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.GroupSignedEvent
import com.machankura.dedun.compose.database.model.types.FrostSigningStage
import kotlinx.coroutines.flow.first
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.byteVector32
import kotlin.time.Clock

/**
 * A group's Wavelength wallet pays only its members, from what they put in: `docs/payouts-only.md`, as the other two wallets do, over
 * Lightning or onchain as a leave in a round (`docs/group-wavelength-payouts.md`).
 *
 * A member's balance is their deposits less what's been paid to them, shrunk in proportion to what's actually left in the wallet by this
 * device's own count. So fees for renewing and leaving, which belong to nobody, fall on everybody in proportion to what they hold. That is
 * a choice the plan left open, and the simplest one that can't pay out more than there is. A device with no record of the wallet's coins
 * sees nothing to pay, which is the safe way round: it won't sign a payout it can't check.
 */
object GroupWavelengthPayouts {
    data class Balance(
        val depositedSat: Long = 0,
        val paidSat: Long = 0,
        val openSat: Long = 0,
        val availableSat: Long = 0,
        /** Their share of what's in the wallet before anything approved and unpaid is taken off: what every open payout of theirs has to fit. */
        val heldSat: Long = 0,
    ) {
        /** Whether everything approved for them and not yet paid fits what they hold, by this device's own count. */
        val bearsWhatsOpen: Boolean get() = heldSat >= openSat

        companion object {
            val NONE = Balance()
        }
    }

    /** Every payout the group has signed on [chain] for any wallet, newest statement per payment. */
    suspend fun signed(database: DedunDatabase, chatRoomId: String, chain: Chain): List<GroupWavelengthPaymentEvent.Payment> =
        signedEvents(database, chatRoomId)
            .mapNotNull { GroupWavelengthPaymentEvent.read(it.tags) }
            .filter { it.chain == chain && it.payout != null }
            .distinctBy { it.paymentHash }

    /** Every onchain payout the group has signed on [chain] for any wallet, the first statement of each. */
    suspend fun signedOnchain(database: DedunDatabase, chatRoomId: String, chain: Chain): List<GroupWavelengthOnchainPayoutEvent.Payout> =
        signedEvents(database, chatRoomId)
            .mapNotNull { GroupWavelengthOnchainPayoutEvent.read(it.tags) }
            .filter { it.chain == chain }
            .distinctBy { it.id }

    // The first for a hash or an id, on every device and for every purpose: a second approval changes nothing it could be charged with.
    private suspend fun signedEvents(database: DedunDatabase, chatRoomId: String): List<GroupSignedEvent> =
        database.groupSignedEventDao().getByChatRoomIdAndKind(chatRoomId, GroupWavelengthPaymentEvent.KIND)
            .filter { it.verifies() }
            .sortedWith(compareBy<GroupSignedEvent> { it.createdAt }.thenBy { it.id })

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

    /** Asks the group to pay [payout], after checking it as any other device would. */
    suspend fun proposeOnchain(database: DedunDatabase, localChatRoom: LocalChatRoom, userPublicKey: HexKey, payout: GroupWavelengthOnchainPayoutEvent.Payout): FrostSigningSession {
        val template = GroupWavelengthOnchainPayoutEvent.template(payout)
        val asEvent = Event("00".repeat(32), "00".repeat(32), 0, template.kind, template.tags, template.content, "")
        refuse(database, localChatRoom.chatRoom.id, asEvent)?.let { throw IllegalArgumentException("This payout $it") }
        return FrostSigningManager.proposeSigning(database, localChatRoom, userPublicKey, template.kind, template.tags, template.content)
    }

    /**
     * What [payment] cost, once this device signed the transfer that makes it, which from then on can't be assumed not to have left: what
     * its vHTLC held, or the approval's limit on a row from before that was kept. Nothing once a refund of it has settled, since the money
     * came home (`docs/group-wavelength-payouts.md`, W11). Null while this device hasn't signed it.
     */
    private suspend fun sentFor(database: DedunDatabase, chatRoomId: String, payment: GroupWavelengthPaymentEvent.Payment): Long? {
        val key = GroupArkCoSigner.walletKey(chatRoomId, payment.chain, payment.wallet)
        val hash = payment.paymentHash?.toString() ?: return null
        val dao = database.groupArkDao()
        val row = dao.payments(key).firstOrNull { it.paymentHash.equals(hash, ignoreCase = true) } ?: return null
        val refund = row.refundedIn
        if (refund != null && dao.vtxos(key).any { it.origin == refund && it.settled }) return 0
        return row.amountSat ?: payment.maxSat
    }

    /** What this device has signed a round paying [payout] with, the leave and the round's fee, or null when it hasn't. */
    private suspend fun leftWith(database: DedunDatabase, chatRoomId: String, payout: GroupWavelengthOnchainPayoutEvent.Payout): Long? {
        val key = GroupArkCoSigner.walletKey(chatRoomId, payout.chain, payout.wallet)
        return database.groupArkDao().leaves(key).firstOrNull { it.authorisation == payout.id }?.let { it.amountSat + it.feeSat }
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
            val sent = sentFor(database, chatRoomId, payment)
            when {
                sent != null -> paid[member] = (paid[member] ?: 0) + sent
                payment.expiresAt > nowSeconds -> open[member] = (open[member] ?: 0) + cost
            }
        }
        signedOnchain(database, chatRoomId, chain).forEach { payout ->
            val member = payout.member.lowercase()
            val left = leftWith(database, chatRoomId, payout)
            when {
                left != null -> paid[member] = (paid[member] ?: 0) + left
                payout.expiresAt > nowSeconds -> open[member] = (open[member] ?: 0) + payout.maxSat
            }
        }
        val net = (deposited.keys + paid.keys + open.keys).associateWith { ((deposited[it] ?: 0) - (paid[it] ?: 0)).coerceAtLeast(0) }
        val totalNet = net.values.sum()
        val inTheWallet = GroupWavelengthWalletManager.wallets(database, chatRoomId, chain).sumOf { wallet ->
            database.groupArkDao().vtxos(GroupArkCoSigner.walletKey(chatRoomId, chain, wallet.statement.wallet)).filter { it.settled && !it.spent }.sumOf { it.amountSat }
        }
        return net.mapValues { (member, sat) ->
            val share = if (totalNet == 0L || inTheWallet >= totalNet) sat else sat * inTheWallet / totalNet
            Balance(deposited[member] ?: 0, paid[member] ?: 0, open[member] ?: 0, (share - (open[member] ?: 0)).coerceAtLeast(0), share)
        }
    }

    /**
     * The onchain payouts a co-signer pays as a round's leave in [wallet], by the script each pays (`docs/group-wavelength-payouts.md`, W4):
     * one this device has given a round that hasn't confirmed, so that round can still finish, and otherwise one approved, standing, and
     * fitting the member's balance by this device's own count. A quorum's approval isn't enough alone: a device that missed the round that
     * paid the member before would have approved a second payout of the same money, and every device that saw it pay refuses that one here.
     * Where two name one script, which approving refuses while either is live, the one already given a round comes first.
     */
    suspend fun approvedLeaves(database: DedunDatabase, chatRoomId: String, wallet: GroupWavelengthWalletManager.Wallet, now: Long = Clock.System.now().epochSeconds): Map<ByteVector, ArkPolicy.AuthorisedLeave> {
        val key = GroupArkCoSigner.walletKey(chatRoomId, wallet.statement.chain, wallet.statement.wallet)
        val leaves = database.groupArkDao().leaves(key).associateBy { it.authorisation }
        val candidates = signedOnchain(database, chatRoomId, wallet.statement.chain).filter { it.wallet == wallet.statement.wallet }
        val inFlight = candidates.filter { leaves[it.id]?.confirmed == false }
        val unpaid = candidates.filter { it.id !in leaves && it.expiresAt > now }
        val balances = if (unpaid.isEmpty()) emptyMap() else balancesIn(database, chatRoomId, wallet.statement.chain, now)
        return (inFlight + unpaid.filter { balances[it.member.lowercase()]?.bearsWhatsOpen == true })
            .mapNotNull { payout -> payout.script?.let { it to ArkPolicy.AuthorisedLeave(payout.id, payout.amountSat, payout.maxFeeSat) } }
            .distinctBy { it.first }
            .toMap()
    }

    /** Why a signer won't sign [proposed], a Wavelength payment for [chatRoomId], or null when it will. */
    suspend fun refuse(database: DedunDatabase, chatRoomId: String, proposed: Event, nowSeconds: Long = Clock.System.now().epochSeconds): String? {
        // Leaving pays only the group's own onchain wallet, which the policy checks when it's signed (D9).
        GroupWavelengthExitEvent.read(proposed.tags)?.let { exit ->
            return if (exit.expiresAt > nowSeconds + MAX_APPROVAL_SECONDS) "stands for longer than the group lets anything stand" else null
        }
        GroupWavelengthOnchainPayoutEvent.read(proposed.tags)?.let { return refuseOnchain(database, chatRoomId, it, nowSeconds) }
        val payment = GroupWavelengthPaymentEvent.read(proposed.tags) ?: return "isn't a payment this device can read"
        val payout = payment.payout ?: return "pays somebody it does not name as a member"
        val destination = GroupPayoutDestinations.payoutDestinationOf(database, chatRoomId, payment.chain, payout.member)
            ?: return "pays a member who has given nowhere to be paid"
        if (!destination.event.id.equals(payout.destinationEventId, ignoreCase = true)) return "pays a destination the member has since replaced"
        val cost = payment.maxSat ?: return "names no amount"
        // The invoice commits to metadata naming the member's address, as the Lightning wallet's payouts do (`docs/payouts-only.md`, D8). It
        // doesn't prove the invoice pays them, which nothing can, but a proposer can no longer hand over any invoice at all.
        val metadata = payment.lnurl ?: return "carries no metadata to check the member's Lightning address against"
        if (payment.descriptionHash != Crypto.sha256(metadata.encodeToByteArray()).byteVector32()) return "carries an invoice that does not commit to the metadata beside it"
        if (!GroupLightningPayouts.names(metadata, destination.lightningAddress.toString())) return "carries metadata that does not name the member's Lightning address"
        if (payment.expiresAt > nowSeconds + MAX_APPROVAL_SECONDS) return "stands for longer than the group lets anything stand"
        if (signed(database, chatRoomId, payment.chain).any { it.paymentHash == payment.paymentHash }) return "pays a hash the group has already approved"
        return refuseAgainstBalance(database, chatRoomId, payment.chain, payout.member, cost, payment.paymentHash.toString(), nowSeconds)
    }

    /** The onchain payout's rules, `docs/group-wavelength-payouts.md`, W3. */
    private suspend fun refuseOnchain(database: DedunDatabase, chatRoomId: String, payout: GroupWavelengthOnchainPayoutEvent.Payout, nowSeconds: Long): String? {
        val destinations = GroupPayoutDestinations.payoutDestinationsIn(database, chatRoomId, payout.chain, nowSeconds)
        val destination = destinations[payout.member.lowercase()] ?: return "pays a member who has given nowhere to be paid"
        if (!destination.event.id.equals(payout.destinationEventId, ignoreCase = true)) return "pays a destination the member has since replaced"
        if (destination.onchainAddress == null) return "pays a member who has given no onchain address"
        if (destination.onchainAddress != payout.address) return "pays an address other than the one the member gave"
        if (destinations.any { (member, other) -> !member.equals(payout.member, ignoreCase = true) && other.onchainAddress == payout.address }) {
            return "pays an address more than one member gave"
        }
        if (payout.amountSat < MIN_LEAVE_SAT) return "pays ${payout.amountSat} sats, less than an output can carry"
        if (payout.expiresAt > nowSeconds + MAX_APPROVAL_SECONDS) return "stands for longer than the group lets anything stand"
        val approved = signedOnchain(database, chatRoomId, payout.chain)
        if (approved.any { it.id == payout.id }) return "is a payout the group has already approved"
        // One payout to an address at a time: a co-signer pays a script's leave for one approval, and a second standing beside it is a
        // second claim on the member's balance while the first's round may still confirm.
        val leaves = database.groupArkDao().leaves(GroupArkCoSigner.walletKey(chatRoomId, payout.chain, payout.wallet)).associateBy { it.authorisation }
        val live = approved.filter { it.wallet == payout.wallet && it.address == payout.address }
            .any { other -> leaves[other.id]?.let { !it.confirmed } ?: (other.expiresAt > nowSeconds) }
        if (live) return "pays an address another payout is still being paid to"
        return refuseAgainstBalance(database, chatRoomId, payout.chain, payout.member, payout.maxSat, payout.id, nowSeconds)
    }

    /**
     * Whether [member]'s balance bears [cost], with whatever else of theirs is being signed, and they have no other payout waiting, on either
     * rail: one at a time, or several could each pass against one balance (W9). [itself] is the hash or id of the payout being checked.
     */
    private suspend fun refuseAgainstBalance(database: DedunDatabase, chatRoomId: String, chain: Chain, member: HexKey, cost: Long, itself: String, nowSeconds: Long): String? {
        val balance = balancesIn(database, chatRoomId, chain, nowSeconds)[member.lowercase()] ?: Balance.NONE
        val pending = proposedFor(database, chatRoomId, chain, member, itself)
        if (balance.openSat > 0 || pending.isNotEmpty()) return "pays a member who already has a payout waiting"
        if (cost > balance.availableSat) return "pays $cost sats with its fee limit, more than the ${balance.availableSat} sats of the member's balance in this wallet"
        return null
    }

    /** What the member's other payouts being signed in the room just now would take, on either rail, by hash or id. */
    private suspend fun proposedFor(database: DedunDatabase, chatRoomId: String, chain: Chain, member: HexKey, itself: String): Map<String, Long> =
        database.frostSigningSessionDao().observeSessionsForChatRoom(chatRoomId).first()
            .filter { it.session.stage != FrostSigningStage.FAILED && it.session.stage != FrostSigningStage.COMPLETE }
            .flatMap { it.items }
            .mapNotNull { Event.fromJsonOrNull(it.unsignedEventJson) }
            .filter { it.kind == GroupWavelengthPaymentEvent.KIND }
            .mapNotNull { event ->
                GroupWavelengthOnchainPayoutEvent.read(event.tags)?.takeIf { it.chain == chain && it.member.equals(member, ignoreCase = true) }?.let { it.id to it.maxSat }
                    ?: GroupWavelengthPaymentEvent.read(event.tags)?.takeIf { it.chain == chain && it.payout?.member.equals(member, ignoreCase = true) }
                        ?.let { it.paymentHash.toString() to (it.maxSat ?: 0) }
            }
            .filter { (id, _) -> id != itself }
            .toMap()

    /** The longest a device signs an approval to stand for. A far-off one could still be acted on long after anyone remembers it. */
    private const val MAX_APPROVAL_SECONDS = 7L * 24 * 60 * 60

    /** The least a leave may pay: the dust limit of the costliest address type a destination can be. The operator's own floor is the screen's. */
    const val MIN_LEAVE_SAT = 546L
}
