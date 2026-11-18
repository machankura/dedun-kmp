package com.machankura.dedun.compose.wallet

import com.machankura.dedun.compose.nostr.wallet.DepositAttributionEvent.DepositKey
import com.machankura.dedun.compose.nostr.wallet.MemberTransferEvent
import com.machankura.dedun.compose.nostr.wallet.MemberTransferEvent.CoinShare
import com.machankura.dedun.compose.nostr.wallet.TransferRequestEvent
import com.machankura.dedun.compose.nostr.wallet.TransferRequestEvent.Rail
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Chain

/**
 * What the group's signed transfers do to its members' balances: `docs/member-transfers.md`, T1, T5 and T6. Pure: the
 * transfers come in already verified, and in [MemberTransferEvent.ORDER].
 *
 * On the Wavelength and Lightning rails a transfer is a term in a ledger, received less given. Onchain, a balance is the coins
 * a member owns, so a transfer moves part of named coins from one owner to another, applied on top of whatever owner rule
 * said whose the coin was.
 */
object TransferLedger {

    /** What [member] has been given and has given away on one rail, in sats. */
    data class Net(val receivedSat: Long = 0, val givenSat: Long = 0) {
        val sat: Long get() = receivedSat - givenSat

        companion object {
            val NONE = Net()
        }
    }

    /**
     * Every member's [Net] from [transfers] on [chain] and [rail], applied in order, each against what its giver can cover at
     * that point: [capacity], keyed by lowercase key -- what they hold by the group's signed records alone, every payout charged at its most -- plus what
     * they have received less what they have given so far (`docs/member-transfers.md`, T15). **A transfer its giver can't cover
     * is void**, and so is one the group signed outside its request's window, before the giver asked or after the request ran
     * out. Every device that holds the same signed records voids the same ones, so a quorum that signs an overdraft, or an old
     * request, moves nothing: the money is not there to move, and nobody else's share pays for it.
     */
    fun netsOf(transfers: List<MemberTransferEvent.Transfer>, chain: Chain, rail: Rail, capacity: Map<HexKey, Long>): Map<HexKey, Net> {
        val received = HashMap<HexKey, Long>()
        val given = HashMap<HexKey, Long>()
        counted(transfers, chain, rail, capacity).forEach { transfer ->
            received[transfer.to.lowercase()] = (received[transfer.to.lowercase()] ?: 0) + transfer.amountSat
            given[transfer.from.lowercase()] = (given[transfer.from.lowercase()] ?: 0) + transfer.amountSat
        }
        return (received.keys + given.keys).associateWith { Net(received[it] ?: 0, given[it] ?: 0) }
    }

    /** The transfers among [transfers] on [chain] and [rail] that count, in order: [netsOf]'s rule. */
    fun counted(transfers: List<MemberTransferEvent.Transfer>, chain: Chain, rail: Rail, capacity: Map<HexKey, Long>): List<MemberTransferEvent.Transfer> {
        val held = capacity.toMutableMap()
        return transfers.filter { it.chain == chain && it.rail == rail }.sortedWith(MemberTransferEvent.ORDER).filter { transfer ->
            if (transfer.createdAt < transfer.request.createdAt || transfer.createdAt > transfer.request.expiresAt) return@filter false
            val from = transfer.from.lowercase()
            val to = transfer.to.lowercase()
            if ((held[from] ?: 0) < transfer.amountSat) return@filter false
            held[from] = (held[from] ?: 0) - transfer.amountSat
            held[to] = (held[to] ?: 0) + transfer.amountSat
            true
        }
    }

    /** What [member] can still give on top of [capacity] after [transfers]: what a new transfer of theirs has to fit. */
    fun capacityAfter(transfers: List<MemberTransferEvent.Transfer>, chain: Chain, rail: Rail, capacity: Map<HexKey, Long>, member: HexKey): Long =
        (capacity[member.lowercase()] ?: 0) + (netsOf(transfers, chain, rail, capacity)[member.lowercase()]?.sat ?: 0)

    /**
     * Whose [key] is after [transfers], starting from [base] -- the owner rule's shares of it, by member. Each transfer naming
     * the coin moves its share from the giver to the receiver, in order. **One whose giver doesn't hold that much of the coin
     * at that point, or that would leave the giver a share below dust, or that the group signed outside its request's window,
     * is void** and skipped (T6, T15): every device that holds
     * the same records skips the same ones, and the coin's shares still add up to the coin.
     */
    fun applyToCoin(key: DepositKey.Onchain, base: Map<HexKey, Long>, transfers: List<MemberTransferEvent.Transfer>): Map<HexKey, Long> {
        val shares = base.mapKeys { it.key.lowercase() }.toMutableMap()
        transfers.forEach { transfer ->
            if (transfer.rail != Rail.ONCHAIN) return@forEach
            if (transfer.createdAt < transfer.request.createdAt || transfer.createdAt > transfer.request.expiresAt) return@forEach
            transfer.coins.filter { it.key == key }.forEach { moved ->
                val from = transfer.from.lowercase()
                val held = shares[from] ?: 0
                val left = held - moved.sat
                if (left < 0 || left in 1 until TransferRequestEvent.MIN_TRANSFER_SAT) return@forEach
                if (left == 0L) shares.remove(from) else shares[from] = left
                val to = transfer.to.lowercase()
                shares[to] = (shares[to] ?: 0) + moved.sat
            }
        }
        return shares
    }

    /**
     * Which coins, and how much of each, move [amountSat] of [held] -- what the giver owns of each confirmed coin -- or null
     * when no choice can. Whole coins first, the largest that still fit, so as few coins as possible end up shared; then at
     * most one coin split, the smallest that leaves both sides at least dust. A transfer so makes at most one shared coin
     * (T6), and every share it moves or leaves is at least [TransferRequestEvent.MIN_TRANSFER_SAT].
     */
    fun chooseCoins(held: Map<DepositKey.Onchain, Long>, amountSat: Long): List<CoinShare>? {
        val dust = TransferRequestEvent.MIN_TRANSFER_SAT
        if (amountSat < dust) return null
        val coins = held.entries.filter { it.value > 0 }.sortedWith(compareByDescending<Map.Entry<DepositKey.Onchain, Long>> { it.value }.thenBy { it.key.text })
        val chosen = mutableListOf<CoinShare>()
        var remaining = amountSat
        for ((key, sat) in coins) {
            if (remaining == 0L) break
            val after = remaining - sat
            if (sat <= remaining && (after == 0L || after >= dust)) {
                chosen += CoinShare(key, sat)
                remaining = after
            }
        }
        if (remaining > 0) {
            val taken = chosen.map { it.key }.toSet()
            val split = coins.filter { it.key !in taken && it.value - remaining >= dust }.minByOrNull { it.value } ?: return null
            chosen += CoinShare(split.key, remaining)
        }
        return chosen.sortedBy { it.key.text }
    }
}
