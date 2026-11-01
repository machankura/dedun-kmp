package com.machankura.dedun.compose.wallet

import com.machankura.dedun.compose.managers.GroupWalletDerivation
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector64
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import fr.acinq.bitcoin.psbt.Psbt

/**
 * Builds the one spend the group's wallet signs: paying members everything they own.
 * `docs/payouts-only.md`, Phase 4.
 *
 * Every coin a selected member owns that is confirmed and held by no open spend goes in, a coin
 * they share with others included -- which pays those others their share of it too, since a
 * shared coin can't pay one owner without change (D4). One output per member, to their payout
 * address, of what they own among the inputs less a pro-rata share of the fee (D5). No change.
 *
 * What it builds is checked by [PayoutRule] before it is handed back, as every signer will check
 * it, so a proposer never asks the group to sign what the group would refuse.
 */
object PayoutBuilder {

    sealed interface Result {
        data class Built(val psbt: Psbt, val payout: PayoutRule.Result.Payout, val fee: Satoshi) : Result
        /** [member] owns no confirmed coin. */
        data class NothingToPay(val member: HexKey) : Result
        /** Every coin [member] owns is held by a spend waiting for signatures: a payout of theirs is open. */
        data class AlreadyWaiting(val member: HexKey) : Result
        /** [member] has given no onchain address, or withdrew theirs. */
        data class NoOnchainAddress(val member: HexKey) : Result
        /** What arrives for [member] after their share of the fee is too little to send. */
        data class BelowDust(val member: HexKey) : Result
        /** A bump has to pay more than the payout it replaces. */
        data object NotHigher : Result
        /** What this builder made, the rule refuses: a bug, said rather than proposed. */
        data class Refused(val reason: String) : Result
    }

    /** Pays [members] of [state]'s wallet everything they own, at [feeratePerVbyte], leaving out [reserved] coins. */
    fun build(
        state: GroupWalletState,
        members: Set<HexKey>,
        context: PayoutRule.Context,
        feeratePerVbyte: Long,
        reserved: Set<OutPoint>,
    ): Result {
        require(members.isNotEmpty()) { "nobody to pay" }
        val selected = members.map { it.lowercase() }.toSet()
        val owned = state.utxos.map { utxo -> utxo to CoinOwnership.ownerOf(utxo.utxo.outPoint, utxo.derivation.change, utxo.amount.sat, context.records) }
        val spendable = owned.filter { (utxo, _) -> utxo.isConfirmed && utxo.utxo.outPoint !in reserved }

        val coins = spendable.filter { (utxo, owner) -> CoinOwnership.sharesOf(owner, utxo.amount.sat).keys.any { it in selected } }
        selected.sorted().forEach { member ->
            if (coins.none { (utxo, owner) -> member in CoinOwnership.sharesOf(owner, utxo.amount.sat) }) {
                val held = owned.any { (utxo, owner) -> utxo.isConfirmed && utxo.utxo.outPoint in reserved && member in CoinOwnership.sharesOf(owner, utxo.amount.sat) }
                return if (held) Result.AlreadyWaiting(member) else Result.NothingToPay(member)
            }
        }

        val ownedBy = mutableMapOf<HexKey, Long>()
        coins.forEach { (utxo, owner) -> CoinOwnership.sharesOf(owner, utxo.amount.sat).forEach { (member, sat) -> ownedBy[member] = (ownedBy[member] ?: 0) + sat } }
        val payees = ownedBy.keys.sorted()
        val scripts = payees.associateWith { member -> context.scripts[member] ?: return Result.NoOnchainAddress(member) }

        val inputs = coins.map { it.first }
        val fee = feeOf(inputs, payees.map { scripts.getValue(it) }, feeratePerVbyte)
        return assemble(state.account, inputs, payees.map { it to scripts.getValue(it) }, ownedBy, fee, context)
    }

    /**
     * [checked], a payout, again at [feeratePerVbyte]: the same coins and the same members, the
     * larger fee split again. A new proposal, with new signatures, because a retry is a new session.
     */
    fun bump(checked: GroupSpend.Checked, context: PayoutRule.Context, feeratePerVbyte: Long): Result {
        val payout = PayoutRule.check(checked, context) as? PayoutRule.Result.Payout
            ?: return Result.Refused("is not a payout")
        val fee = Satoshi(feeratePerVbyte * ((checked.weight + 3) / 4))
        if (fee <= checked.fee) return Result.NotHigher
        val ownedBy = payout.members.associate { it.member to it.owned }
        val outputs = payout.members.sortedBy { it.outputIndex }.map { it.member to it.script }
        val coins = checked.inputs.map { it.txOut to it.derivation }
        return assembleFrom(checked.account, checked.tx.txIn.map { it.outPoint }, coins, outputs, ownedBy, fee, context, checked.tx.txIn.map { it.sequence })
    }

    private fun assemble(
        account: GroupWalletDerivation.Account,
        inputs: List<GroupUtxo>,
        outputs: List<Pair<HexKey, ByteVector>>,
        ownedBy: Map<HexKey, Long>,
        fee: Satoshi,
        context: PayoutRule.Context,
    ): Result = assembleFrom(
        account,
        inputs.map { it.utxo.outPoint },
        inputs.map { it.utxo.txOut to it.derivation },
        outputs, ownedBy, fee, context,
        sequences = inputs.map { GroupSpend.MAX_SEQUENCE },
    )

    private fun assembleFrom(
        account: GroupWalletDerivation.Account,
        outPoints: List<OutPoint>,
        coins: List<Pair<TxOut, GroupWalletDerivation.Derived>>,
        outputs: List<Pair<HexKey, ByteVector>>,
        ownedBy: Map<HexKey, Long>,
        fee: Satoshi,
        context: PayoutRule.Context,
        sequences: List<Long>,
    ): Result {
        val shares = PayoutFees.split(ownedBy, fee.sat)
        val txOut = outputs.map { (member, script) ->
            val paid = ownedBy.getValue(member) - shares.getValue(member)
            if (paid < GroupSpendBuilder.DUST.sat) return Result.BelowDust(member)
            TxOut(Satoshi(paid), script)
        }
        val tx = Transaction(
            version = 2,
            txIn = outPoints.mapIndexed { index, outPoint -> TxIn(outPoint, ByteVector.empty, sequences[index]) },
            txOut = txOut,
            lockTime = 0,
        )
        val psbt = GroupSpend.psbtOf(tx, coins, change = emptyMap(), account)
        val checked = when (val result = GroupSpend.check(psbt, account)) {
            is GroupSpend.Result.Ok -> result.checked
            is GroupSpend.Result.Refused -> return Result.Refused(result.reason)
        }
        return when (val rule = PayoutRule.check(checked, context)) {
            is PayoutRule.Result.Payout -> Result.Built(psbt, rule, fee)
            is PayoutRule.Result.Refused -> Result.Refused(rule.reason)
        }
    }

    /** The fee at [feeratePerVbyte] for spending [coins] to [scripts]: exact, every signature being 64 bytes. */
    private fun feeOf(coins: List<GroupUtxo>, scripts: List<ByteVector>, feeratePerVbyte: Long): Satoshi {
        val tx = Transaction(
            version = 2,
            txIn = coins.map { TxIn(it.utxo.outPoint, ByteVector.empty, GroupSpend.MAX_SEQUENCE) },
            txOut = scripts.map { TxOut(Satoshi(0), it) },
            lockTime = 0,
        )
        return Satoshi(feeratePerVbyte * ((GroupSpend.witnessed(tx, List(coins.size) { ByteVector64.Zeroes }).weight() + 3) / 4))
    }
}
