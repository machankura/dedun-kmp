package com.machankura.dedun.compose.wallet

import com.machankura.dedun.compose.managers.GroupWalletDerivation
import com.machankura.dedun.compose.nostr.wallet.CoinAssignmentEvent
import com.machankura.dedun.compose.nostr.wallet.DepositAttributionEvent
import com.machankura.dedun.compose.nostr.wallet.DepositAttributionEvent.DepositKey
import com.machankura.dedun.compose.nostr.wallet.DepositorStatementEvent
import com.machankura.dedun.compose.nostr.wallet.MemberTransferEvent
import com.machankura.dedun.compose.nostr.wallet.TransferRequestEvent
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.OutPoint

/**
 * Whose a coin of the group's wallet is: `docs/payouts-only.md`, D2.
 *
 * In this order:
 *
 *  1. the newest coin assignment (30341) that names it, for a coin on the receive or change
 *     branch, when its amount is the coin's;
 *  2. the newest deposit attribution (30340) that names it, when its amount is the coin's --
 *     `group` there meaning nobody;
 *  3. its branch, when that branch is a depositor's;
 *  4. otherwise nobody;
 *
 * and then, on top of whichever of those said whose it is, every member's transfer (30343) that names it, in the group's
 * order, each moving part of it from its giver to its receiver -- or nothing, where the giver doesn't hold that much of it
 * then (`docs/member-transfers.md`, T6).
 *
 * Every rule reads only what a signer already holds: the coin's branch, which its derivation
 * proves, its amount, which the sighash commits to, and the group's signed events, which the
 * chronicle carries. So two devices with the same signed history always agree, with no Electrum
 * and no local ledger asked.
 */
object CoinOwnership {

    sealed interface Owner {
        /** All of it is [member]'s. */
        data class Member(val member: HexKey) : Owner

        /** It is several members', in [shares] of sats that add up to the coin. */
        data class Shares(val shares: Map<HexKey, Long>) : Owner

        /** Nobody's yet: it waits for the group to say whose ([why] says where it came from). */
        data class Nobody(val why: Why) : Owner
    }

    enum class Why {
        /** On the legacy receive branch: from before deposits were attributed, a closed channel, or a sweep. */
        RECEIVE,
        /** On the change branch: from a spend before payouts were the only spend. */
        CHANGE,
        /** The group signed that it is no member's deposit. */
        THE_GROUPS,
        /** On a branch no depositor statement this device holds names. */
        UNKNOWN_BRANCH,
    }

    /** The group's signed word about coins, read once for a whole wallet. */
    data class Records(
        val branches: Map<Long, HexKey>,
        val attributions: Map<DepositKey, DepositAttributionEvent.Attribution> = emptyMap(),
        val assignments: Map<DepositKey.Onchain, CoinAssignmentEvent.Coin> = emptyMap(),
        /** The group's onchain transfers, each naming coins and how much of each moves (`docs/member-transfers.md`). */
        val transfers: List<MemberTransferEvent.Transfer> = emptyList(),
    ) {
        /** The transfers that name [key], in the group's order. */
        fun transfersOf(key: DepositKey.Onchain): List<MemberTransferEvent.Transfer> =
            transfers.filter { transfer -> transfer.coins.any { it.key == key } }.sortedWith(MemberTransferEvent.ORDER)

        companion object {
            val NONE = Records(emptyMap())

            fun of(
                depositors: Collection<DepositorStatementEvent.Depositor>,
                attributions: Map<DepositKey, DepositAttributionEvent.Attribution>,
                assignments: Map<DepositKey.Onchain, CoinAssignmentEvent.Coin>,
                transfers: List<MemberTransferEvent.Transfer> = emptyList(),
            ): Records = Records(
                depositors.associate { it.branch to it.member.lowercase() },
                attributions,
                assignments,
                transfers.filter { it.rail == TransferRequestEvent.Rail.ONCHAIN },
            )
        }
    }

    /** Whose the coin at [outPoint] is: [amountSat] sats on [branch], by [records], transfers included. */
    fun ownerOf(outPoint: OutPoint, branch: Long, amountSat: Long, records: Records): Owner {
        val key = DepositKey.Onchain(outPoint.txid.toString().lowercase(), outPoint.index)
        val base = baseOwnerOf(key, branch, amountSat, records)
        val transfers = records.transfersOf(key)
        if (transfers.isEmpty() || base is Owner.Nobody) return base
        val shares = TransferLedger.applyToCoin(key, sharesOf(base, amountSat), transfers)
        return shares.entries.singleOrNull()?.let { Owner.Member(it.key) } ?: Owner.Shares(shares)
    }

    /** Whose the coin at [key] is before any transfer: D2's rule. */
    fun baseOwnerOf(key: DepositKey.Onchain, branch: Long, amountSat: Long, records: Records): Owner {
        if (branch == GroupWalletDerivation.RECEIVE || branch == GroupWalletDerivation.CHANGE) {
            records.assignments[key]?.takeIf { it.amount == amountSat }?.let { coin ->
                return coin.shares.entries.singleOrNull()?.let { Owner.Member(it.key) } ?: Owner.Shares(coin.shares)
            }
        }
        records.attributions[key]?.takeIf { it.amount == amountSat }?.let { attribution ->
            return attribution.member?.let { Owner.Member(it.lowercase()) } ?: Owner.Nobody(Why.THE_GROUPS)
        }
        records.branches[branch]?.let { return Owner.Member(it) }
        return Owner.Nobody(
            when (branch) {
                GroupWalletDerivation.RECEIVE -> Why.RECEIVE
                GroupWalletDerivation.CHANGE -> Why.CHANGE
                else -> Why.UNKNOWN_BRANCH
            }
        )
    }

    /** How many sats of a coin of [amountSat] [owner] gives each member: nothing for nobody's. */
    fun sharesOf(owner: Owner, amountSat: Long): Map<HexKey, Long> = when (owner) {
        is Owner.Member -> mapOf(owner.member to amountSat)
        is Owner.Shares -> owner.shares
        is Owner.Nobody -> emptyMap()
    }
}
