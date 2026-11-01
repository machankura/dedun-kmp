package com.machankura.dedun.compose.wallet

import com.machankura.dedun.compose.managers.GroupWalletDerivation
import com.machankura.dedun.compose.text.SatsInput
import fr.acinq.bitcoin.Bitcoin
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector64
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import fr.acinq.bitcoin.psbt.Psbt
import fr.acinq.lightning.utils.sum

/**
 * The spends a member can propose from the group's wallet: everything to one
 * address, which is what Phoenix's final wallet does, or an amount with change.
 *
 * Confirmed coins only, as Phoenix's send-all takes, and never a coin an open
 * proposal is already spending. Two proposals over one coin are not dangerous --
 * only one can confirm, and each has its own nonces -- but the second is a quorum
 * asked for nothing.
 *
 * A guardian building a spend from a descendant's guarded wallet -- a
 * [GroupSpend.Spender.Guardian] -- takes only coins its leaf's lock has already
 * let go of, puts that lock on every input, and weighs each input by its leaf
 * and control block. Its change, if any, goes back to the descendant.
 *
 * A spend can also *fund* a descendant's guarded wallet: a [Destination.Funding]
 * pays the receive address `GroupWalletState.fundingAddress` chose, and the PSBT
 * says so on that output, in the derivation field change already uses, so every
 * signer can derive it again. See `docs/guarded-wallet-funding.md`.
 */
object GroupSpendBuilder {
    /** Phoenix's floor, kept: an output below it is one no node will relay. */
    val DUST: Satoshi = Satoshi(546)

    /** Where a spend's payment goes. */
    sealed interface Destination {
        /** What the member typed: whatever it is, it is not the group's to vouch for. */
        data class Address(val address: String) : Destination

        /**
         * A descendant's guarded wallet, at [derived] under its [account] -- the
         * receive address `GroupWalletState.fundingAddress` chose.
         */
        data class Funding(val account: GroupWalletDerivation.Account, val derived: GroupWalletDerivation.Derived) : Destination
    }

    sealed interface Result {
        data class Built(
            val psbt: Psbt,
            val amount: Satoshi,
            val fee: Satoshi,
            val change: Satoshi?,
            /** What [fee] holds beyond the feerate: change too small to send back, paid to the miner instead. */
            val absorbed: Satoshi = Satoshi(0),
            /** The guarded wallet the payment funds, or null for a payment to a typed address. */
            val funded: Destination.Funding? = null,
        ) : Result
        data object NotAnAddress : Result
        data object NothingToSpend : Result
        /** Electrum has not yet said which change addresses are used, so there is none to name. */
        data object FindingChange : Result
        /** Open spends already pay change to every change address the wallet watches. */
        data object TooManyOpen : Result
        data object NotEnough : Result
        data object BelowDust : Result
        /** A bump has to pay more than the spend it replaces. */
        data object NotHigher : Result
        /** More than [GroupWalletRollout.capFor] lets one spend send. */
        data class OverCap(val cap: Satoshi) : Result
        /** The amount typed is not a number of sats; [input] says why. */
        data class InvalidAmount(val input: SatsInput) : Result
    }

    /**
     * [result] held to [cap]: the composer's gate while the wallet is new. A
     * built spend sending more than the cap is refused here, before a quorum is
     * asked; nothing at acceptance refuses one another build proposes.
     */
    fun capped(result: Result, cap: Satoshi?): Result =
        if (cap != null && result is Result.Built && result.amount > cap) Result.OverCap(cap) else result

    /** Every confirmed, unreserved coin, to [address], less the fee. */
    fun sendAll(
        state: GroupWalletState,
        address: String,
        feeratePerVbyte: Long,
        reserved: Set<OutPoint>,
        spender: GroupSpend.Spender = GroupSpend.Spender.Owner,
    ): Result = sendAll(state, Destination.Address(address), feeratePerVbyte, reserved, spender)

    /** Every confirmed, unreserved coin, to [destination], less the fee. */
    fun sendAll(
        state: GroupWalletState,
        destination: Destination,
        feeratePerVbyte: Long,
        reserved: Set<OutPoint>,
        spender: GroupSpend.Spender = GroupSpend.Spender.Owner,
    ): Result {
        val script = scriptOf(state, destination) ?: return Result.NotAnAddress
        val funded = destination as? Destination.Funding
        val coins = spendable(state, reserved, spender).ifEmpty { return Result.NothingToSpend }

        val total = coins.map { it.amount }.sum()
        val fee = feeOf(state, coins, listOf(script), feeratePerVbyte, spender)
        val amount = total - fee
        if (amount < DUST) return Result.BelowDust

        val tx = transaction(state, coins, listOf(TxOut(amount, script)), spender)
        return Result.Built(psbtOf(state, tx, coins, change = emptyMap(), funded), amount, fee, change = null, funded = funded)
    }

    /**
     * [amount] to [address], largest coins first, with what is left over paid
     * back to the next change address no open spend holds -- or to fees, when
     * it would be dust.
     */
    fun send(
        state: GroupWalletState,
        address: String,
        amount: Satoshi,
        feeratePerVbyte: Long,
        reserved: Set<OutPoint>,
        reservedChange: Set<Long> = emptySet(),
        spender: GroupSpend.Spender = GroupSpend.Spender.Owner,
    ): Result = send(state, Destination.Address(address), amount, feeratePerVbyte, reserved, reservedChange, spender)

    /** [amount] to [destination], as [send] to an address does. */
    fun send(
        state: GroupWalletState,
        destination: Destination,
        amount: Satoshi,
        feeratePerVbyte: Long,
        reserved: Set<OutPoint>,
        reservedChange: Set<Long> = emptySet(),
        spender: GroupSpend.Spender = GroupSpend.Spender.Owner,
    ): Result {
        val script = scriptOf(state, destination) ?: return Result.NotAnAddress
        val funded = destination as? Destination.Funding
        if (amount < DUST) return Result.BelowDust
        val coins = spendable(state, reserved, spender).sortedByDescending { it.amount.sat }.ifEmpty { return Result.NothingToSpend }

        if (!state.changeKnown) return Result.FindingChange
        val changeIndex = state.nextChangeIndex(reservedChange) ?: return Result.TooManyOpen
        val change = GroupWalletDerivation.derive(state.account, GroupWalletDerivation.CHANGE, changeIndex)

        val chosen = mutableListOf<GroupUtxo>()
        for (coin in coins) {
            chosen += coin
            val total = chosen.map { it.amount }.sum()
            val withChange = feeOf(state, chosen, listOf(script, change.pubkeyScript), feeratePerVbyte, spender)
            val leftover = total - amount - withChange

            if (leftover >= DUST) {
                val tx = transaction(state, chosen, listOf(TxOut(amount, script), TxOut(leftover, change.pubkeyScript)), spender)
                return Result.Built(psbtOf(state, tx, chosen, mapOf(1 to change), funded), amount, withChange, leftover, funded = funded)
            }

            // Enough to pay without change: the remainder, under dust, goes to fees.
            val withoutChange = feeOf(state, chosen, listOf(script), feeratePerVbyte, spender)
            if (total - amount >= withoutChange) {
                val tx = transaction(state, chosen, listOf(TxOut(amount, script)), spender)
                return Result.Built(
                    psbtOf(state, tx, chosen, emptyMap(), funded), amount, total - amount, change = null,
                    absorbed = total - amount - withoutChange,
                    funded = funded,
                )
            }
        }
        return Result.NotEnough
    }

    /**
     * [spend] again at [feeratePerVbyte]: the same coins and the same outputs,
     * with the extra fee taken from the change -- or, for a send-all, from the
     * one payment. RBF, which every spend allows; a new proposal, with new
     * signatures, because a retry is a new session.
     */
    fun bump(spend: GroupSpend.Checked, feeratePerVbyte: Long): Result {
        val fee = Satoshi(feeratePerVbyte * ((spend.weight + 3) / 4))
        val extra = fee - spend.fee
        if (extra.sat <= 0) return Result.NotHigher

        val target = spend.outputs.firstOrNull { it.isChange } ?: spend.payments.singleOrNull() ?: return Result.NotEnough
        val reduced = target.txOut.amount - extra
        if (reduced < DUST) return Result.NotEnough

        val tx = spend.tx.copy(txOut = spend.tx.txOut.mapIndexed { index, out -> if (index == target.index) out.copy(amount = reduced) else out })
        // A funding keeps its claim, or the bump would read as a payment to a bare address.
        val psbt = GroupSpend.psbtOf(
            tx,
            spend.inputs.map { it.txOut to it.derivation },
            spend.outputs.mapNotNull { output -> output.change?.let { output.index to it } }.toMap(),
            spend.account,
            funding = spend.fundings.associate { it.outputIndex to (it.wallet.account to it.derived) },
        )
        return Result.Built(
            psbt = psbt,
            amount = if (target.isChange) spend.amountSent else reduced,
            fee = fee,
            change = if (target.isChange) reduced else null,
            funded = spend.fundings.singleOrNull()?.let { Destination.Funding(it.wallet.account, it.derived) },
        )
    }

    /**
     * The coins a new spend may use: confirmed -- for a guardian, as deep as its
     * leaf's lock -- and held by no open spend.
     */
    fun spendable(
        state: GroupWalletState,
        reserved: Set<OutPoint>,
        spender: GroupSpend.Spender = GroupSpend.Spender.Owner,
    ): List<GroupUtxo> {
        val lock = lockOf(state, spender)
        return state.utxos.filter {
            it.isConfirmed && (lock == null || state.confirmations(it) >= lock) && it.utxo.outPoint !in reserved
        }
    }

    /**
     * What [spendable] adds up to: the most a form can offer, before the fee.
     * Less than the wallet's confirmed balance by whatever open spends hold.
     */
    fun spendableBalance(
        state: GroupWalletState,
        reserved: Set<OutPoint>,
        spender: GroupSpend.Spender = GroupSpend.Spender.Owner,
    ): Satoshi = spendable(state, reserved, spender).map { it.amount }.sum()

    /** The relative lock a guardian's inputs carry, or null for the wallet's own group. */
    private fun lockOf(state: GroupWalletState, spender: GroupSpend.Spender): Long? =
        (spender as? GroupSpend.Spender.Guardian)?.let { state.account.guardians[it.position].delay }

    /**
     * What [destination] pays: a typed address's script, or the funded wallet's
     * own derivation -- never a string round trip, so a funding is always an
     * address. Null for text that is not an address on this network, and for a
     * funding on another network.
     */
    private fun scriptOf(state: GroupWalletState, destination: Destination): ByteVector? = when (destination) {
        is Destination.Address -> Bitcoin.addressToPublicKeyScript(state.account.chain.chainHash, destination.address.trim()).right
            ?.let { ByteVector(Script.write(it)) }
        is Destination.Funding -> destination.derived.pubkeyScript.takeIf { destination.account.chain == state.account.chain }
    }

    private fun transaction(state: GroupWalletState, coins: List<GroupUtxo>, outputs: List<TxOut>, spender: GroupSpend.Spender) = Transaction(
        version = 2,
        // Replaceable, so a stuck spend can be bumped by a new proposal -- and a
        // guardian's lock is far below the replaceable ceiling, so its is too.
        txIn = coins.map { TxIn(it.utxo.outPoint, ByteVector.empty, lockOf(state, spender) ?: GroupSpend.MAX_SEQUENCE) },
        txOut = outputs,
        lockTime = 0,
    )

    /**
     * The fee at [feeratePerVbyte] for spending [coins] to [scripts]: exact,
     * because every signature is 64 bytes and a guardian's leaf and control
     * block are known before anybody signs.
     */
    private fun feeOf(
        state: GroupWalletState,
        coins: List<GroupUtxo>,
        scripts: List<ByteVector>,
        feeratePerVbyte: Long,
        spender: GroupSpend.Spender,
    ): Satoshi {
        val tx = transaction(state, coins, scripts.map { TxOut(Satoshi(0), it) }, spender)
        val weight = when (spender) {
            GroupSpend.Spender.Owner -> GroupSpend.witnessed(tx, List(coins.size) { ByteVector64.Zeroes })
            is GroupSpend.Spender.Guardian -> coins.foldIndexed(tx) { index, partial, coin ->
                partial.updateWitness(index, GroupSpend.guardianWitness(state.account, coin.derivation, spender.position))
            }
        }.weight()
        return Satoshi(feeratePerVbyte * ((weight + 3) / 4))
    }

    /** The payment is always output 0, so a funding's claim goes there. */
    private fun psbtOf(
        state: GroupWalletState,
        tx: Transaction,
        coins: List<GroupUtxo>,
        change: Map<Int, GroupWalletDerivation.Derived>,
        funded: Destination.Funding?,
    ) = GroupSpend.psbtOf(
        tx, coins.map { it.utxo.txOut to it.derivation }, change, state.account,
        funding = funded?.let { mapOf(0 to (it.account to it.derived)) }.orEmpty(),
    )
}
