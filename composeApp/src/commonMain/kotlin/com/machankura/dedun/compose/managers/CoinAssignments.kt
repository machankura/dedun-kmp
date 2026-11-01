package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.DepositRow
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.nostr.wallet.CoinAssignmentEvent
import com.machankura.dedun.compose.nostr.wallet.GroupWalletStatementEvent
import com.machankura.dedun.compose.wallet.GroupSpend
import com.machankura.dedun.compose.wallet.PayoutFees
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Transaction

/**
 * The group saying whose the coins nobody owns are, so they can be paid out:
 * `docs/payouts-only.md`, D3 and Phase 3.
 */
object CoinAssignments {

    /**
     * Asks the group to sign whose [coins] are: a signing session over one 30341, which proposing
     * counts as approving.
     */
    suspend fun propose(
        database: DedunDatabase,
        localChatRoom: LocalChatRoom,
        proposerPublicKey: HexKey,
        chain: Chain,
        coins: List<CoinAssignmentEvent.Coin>,
    ): FrostSigningSession {
        val template = CoinAssignmentEvent.template(chain, coins)
        return FrostSigningManager.proposeSigning(database, localChatRoom, proposerPublicKey, template.kind, template.tags, template.content)
    }

    /**
     * Why a signer won't sign [proposed], a coin assignment for [chatRoomId], or null when it will:
     *
     *  - it reads: shares by distinct members, none below dust, adding up to each coin;
     *  - every coin is one this device knows, for the amount it says -- a deposit on its ledger, or
     *    an output of one of the room's own spends -- and not a deposit on a member's own branch,
     *    which is that member's already;
     *  - every member it names is somebody the group has opened deposits for, and so has a branch
     *    and a destination.
     *
     * Whether the coin is still unspent is not asked: that takes the chain, a signer may have no
     * wallet open, and an assignment of a spent coin gives nobody anything.
     */
    suspend fun refuse(database: DedunDatabase, chatRoomId: String, proposed: Event): String? {
        val signed = CoinAssignmentEvent.read(proposed) ?: return "does not read as a coin assignment"
        val depositors = Depositors.statementsIn(database, chatRoomId, signed.chain)
        val outputs = ownOutputs(database, chatRoomId, signed.chain)
        signed.coins.forEach { coin ->
            val deposit = database.depositDao().get(chatRoomId, coin.key.text)
                ?.takeIf { it.source == DepositRow.ONCHAIN && it.chain == GroupWalletStatementEvent.chainName(signed.chain) }
            when {
                deposit != null -> {
                    if (deposit.amountMsat / 1000 != coin.amount) return "names a coin for another amount than this device has"
                    if (deposit.attributedBy == DepositRow.BY_BRANCH) return "assigns a coin on a member's own branch, which is theirs already"
                }
                coin.key.text in outputs -> if (outputs.getValue(coin.key.text) != coin.amount) return "names a coin for another amount than this device has"
                else -> return "names a coin this device has not seen"
            }
            coin.shares.keys.firstOrNull { it !in depositors }?.let { return "gives a share to somebody the group has not opened deposits for" }
        }
        return null
    }

    /** Every output of [chatRoomId]'s own spends on [chain], `<txid>:<vout>` to sats: where change and sweeps land. */
    private suspend fun ownOutputs(database: DedunDatabase, chatRoomId: String, chain: Chain): Map<String, Long> =
        database.groupTransactionDao().getByChatRoomId(chatRoomId)
            .filter { it.chain == GroupWalletStatementEvent.chainName(chain) }
            .mapNotNull { row ->
                row.signedTransaction?.let { runCatching { Transaction.read(it) }.getOrNull() }
                    ?: GroupSpend.decode(row.psbt)?.global?.tx
            }
            .flatMap { tx -> tx.txOut.mapIndexed { index, out -> "${tx.txid.toString().lowercase()}:$index" to out.amount.sat } }
            .toMap()

    /**
     * The shares a coin of [amount] sats is suggested in, before anybody edits them: pro rata to
     * what each member has put in and not been paid back -- [net], in msat -- with the sats the
     * rounding leaves going to the largest, and any share too small to be paid on its own folded
     * into the largest. Nobody with nothing in gets a share; with nobody in at all, nothing is
     * suggested. A suggestion the proposer can change, never a rule: the group signs what it is shown.
     */
    fun suggest(amount: Long, net: Map<HexKey, Long>): Map<HexKey, Long> {
        val weights = net.filterValues { it > 0 }
        if (weights.isEmpty() || amount < CoinAssignmentEvent.MIN_SHARE_SAT) return emptyMap()
        val shares = PayoutFees.split(weights, amount).filterValues { it > 0 }.toMutableMap()
        val largest = shares.maxWith(compareBy<Map.Entry<HexKey, Long>> { it.value }.thenByDescending { it.key }).key
        shares.filter { it.key != largest && it.value < CoinAssignmentEvent.MIN_SHARE_SAT }.forEach { (member, sat) ->
            shares.remove(member)
            shares[largest] = shares.getValue(largest) + sat
        }
        return shares
    }
}
