package com.machankura.dedun.compose.wallet.lightning

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.machankura.dedun.compose.managers.GroupSwapInDerivation
import com.machankura.dedun.compose.managers.FrostSigningManager
import com.machankura.dedun.compose.managers.GroupLightningDerivation
import com.machankura.dedun.compose.managers.GroupWalletDerivation
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.ByteVector64
import fr.acinq.bitcoin.OP_CHECKSEQUENCEVERIFY
import fr.acinq.bitcoin.OP_CHECKSIGVERIFY
import fr.acinq.bitcoin.OP_PUSHDATA
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.ScriptTree
import fr.acinq.bitcoin.ScriptWitness
import fr.acinq.bitcoin.SigHash
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxId
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import fr.acinq.bitcoin.XonlyPublicKey
import fr.acinq.bitcoin.crypto.frost.TweakCache
import fr.acinq.lightning.channel.GroupHeldOutputs
import fr.acinq.lightning.transactions.Scripts
import fr.acinq.secp256k1.Hex
import kotlinx.serialization.Serializable

/**
 * Moving what a closed channel left the group into its onchain wallet (`docs/group-lightning-wallet.md`,
 * Phase 13): a FROST spend whose inputs are taproot *script* paths, one leaf of an output a commitment
 * or HTLC transaction pays to the group's basepoints.
 *
 * Every member checks a sweep the way [com.machankura.dedun.compose.wallet.GroupSpend] checks a spend,
 * trusting nothing the proposer computed:
 *
 * - **The key is the group's.** Each input names a basepoint and a tweak; the member derives the leaf key
 *   from its own basepoints ([GroupLightningDerivation.leafCache]) and rebuilds the one leaf it signs --
 *   `<key> OP_CHECKSIGVERIFY <delay> OP_CHECKSEQUENCEVERIFY`, the only shape lightning-kmp gives a
 *   group-held output. A tweak the proposer made up gives a key the group can sign for and nobody else
 *   can, so it costs nothing.
 * - **Everything goes to the onchain wallet.** Every output is a receive address of the wallet the
 *   statement closes to, at an index the member derives.
 * - **What is spent is what the sighash says.** BIP341's script-path sighash commits to every spent
 *   amount and script and to the leaf, so a lie about any of them is a transaction nobody can mine,
 *   not one that pays someone else.
 *
 * The internal key and the control block are the proposer's to get right, for the same reason.
 */
object GroupSweep {
    /** The highest feerate a sweep may pay, in sats per virtual byte: the onchain wallet's own limit. */
    const val MAX_FEERATE_SAT_PER_VBYTE: Long = com.machankura.dedun.compose.wallet.GroupSpend.MAX_FEERATE_SAT_PER_VBYTE

    const val PAYMENT = "payment"
    const val DELAYED = "delayed"

    /**
     * A swap-in coin past its refund delay (`docs/group-lightning-swap-in.md`, Phase 7): spent by its refund
     * leaf, under the depositor's refund key for [Input.swapInIndex], into that depositor's own branch (D9).
     */
    const val SWAP_IN = "swap-in"

    /** What a member derives a swap-in refund's keys from: the wallet's swap-in keys and each depositor's branch. */
    class SwapInRefunds(val keys: GroupSwapInDerivation.Keys, val branches: Map<HexKey, Long>)

    /** One output to sweep, as a proposal carries it. [tweak] is null for an output under the payment basepoint itself. */
    @Serializable
    data class Input(
        val txid: String,
        val index: Long,
        val amountSat: Long,
        val script: String,
        val basepoint: String,
        val tweak: String? = null,
        val leafScript: String,
        val controlBlock: String,
        val csvDelay: Long,
        /** For a [SWAP_IN] input: whose swap-in address it was on, and which. */
        val member: String? = null,
        val swapInIndex: Long? = null,
    )

    /**
     * A sweep proposal: the unsigned transaction, what each input spends, and which address each output is --
     * on the receive branch, or on [branch] when it names one: a swap-in refund's depositor's (D9).
     */
    @Serializable
    data class Sweep(val wallet: Long, val tx: String, val inputs: List<Input>, val receiveIndices: List<Long>, val branch: Long? = null) {
        /** Whether this is a swap-in refund: every input a swap-in coin. */
        val isSwapInRefund: Boolean get() = inputs.isNotEmpty() && inputs.all { it.basepoint == SWAP_IN }
    }

    data class CheckedInput(
        val index: Int,
        val outPoint: OutPoint,
        val txOut: TxOut,
        val leafKey: XonlyPublicKey,
        val cache: TweakCache,
        val leafScript: ByteVector,
        val controlBlock: ByteVector,
        val sighash: ByteVector32,
    )

    data class Checked(val sweep: Sweep, val tx: Transaction, val inputs: List<CheckedInput>, val fee: Satoshi, val weight: Int) {
        val amount: Satoshi get() = Satoshi(tx.txOut.sumOf { it.amount.sat })
        val spentOutputs: List<TxOut> get() = inputs.map { it.txOut }
        val feeratePerVbyte: Long get() = fee.sat / ((weight.toLong() + 3) / 4)
    }

    sealed interface Result {
        data class Ok(val checked: Checked) : Result
        data class Refused(val reason: String) : Result
    }

    /** The one leaf a group-held output is spent by, for [leafKey] after [csvDelay] blocks. */
    fun leafScript(leafKey: XonlyPublicKey, csvDelay: Long): ByteVector =
        ByteVector(Script.write(listOf(OP_PUSHDATA(leafKey), OP_CHECKSIGVERIFY, Scripts.encodeNumber(csvDelay), OP_CHECKSEQUENCEVERIFY)))

    /**
     * Checks [sweep] against the group's [basepoints] and the onchain [account] it must pay, and a swap-in
     * refund also against [swapIns]: without them a member cannot derive a refund key, and refuses one.
     */
    fun check(sweep: Sweep, basepoints: GroupLightningDerivation.Basepoints, account: GroupWalletDerivation.Account, swapIns: SwapInRefunds? = null): Result {
        val tx = runCatching { Transaction.read(sweep.tx) }.getOrElse { return Result.Refused("is not a transaction") }
        if (tx.txIn.isEmpty()) return Result.Refused("spends nothing")
        if (tx.txIn.size != sweep.inputs.size) return Result.Refused("describes ${sweep.inputs.size} inputs of ${tx.txIn.size}")
        if (tx.txIn.size > FrostSigningManager.MAX_BATCH_SIZE) return Result.Refused("spends more than ${FrostSigningManager.MAX_BATCH_SIZE} outputs")
        if (tx.version != 2L) return Result.Refused("is version ${tx.version}, not 2")
        if (tx.lockTime != 0L) return Result.Refused("has a lock time")
        if (tx.txOut.isEmpty() || tx.txOut.size != sweep.receiveIndices.size) return Result.Refused("does not say where every output goes")
        // A swap-in refund is one depositor's coins, back to that depositor's own branch (D9); anything else
        // goes to the receive branch, as every sweep has.
        val refundOf = if (sweep.inputs.any { it.basepoint == SWAP_IN }) {
            if (!sweep.isSwapInRefund) return Result.Refused("mixes swap-in coins with a channel's outputs")
            val members = sweep.inputs.map { it.member?.lowercase() }.toSet()
            val member = members.singleOrNull() ?: return Result.Refused("refunds more than one depositor's coins at once")
            val refunds = swapIns ?: return Result.Refused("refunds swap-in coins this device cannot derive the keys of")
            val branch = refunds.branches[member] ?: return Result.Refused("refunds coins of a member who is no depositor")
            if (sweep.branch != branch) return Result.Refused("refunds a depositor's coins anywhere but their own deposit branch")
            member to branch
        } else {
            if (sweep.branch != null && sweep.branch != GroupWalletDerivation.RECEIVE) return Result.Refused("sweeps a channel's outputs to a branch that is not the receive branch")
            null
        }
        val outputBranch = refundOf?.second ?: GroupWalletDerivation.RECEIVE
        tx.txOut.zip(sweep.receiveIndices).forEach { (out, index) ->
            if (index !in 0..com.machankura.dedun.compose.wallet.GroupSpend.MAX_ADDRESS_INDEX) return Result.Refused("names receive address $index")
            if (out.publicKeyScript != GroupWalletDerivation.derive(account, outputBranch, index).pubkeyScript) {
                return Result.Refused("pays somewhere that is not the group's onchain wallet")
            }
        }

        val spent = sweep.inputs.map { TxOut(Satoshi(it.amountSat), ByteVector(it.script)) }
        val inputs = sweep.inputs.mapIndexed { i, input ->
            val txIn = tx.txIn[i]
            if (txIn.outPoint != OutPoint(TxId(input.txid), input.index)) return Result.Refused("input $i is not the output it describes")
            if (input.csvDelay !in 1..0xFFFF) return Result.Refused("input $i has a delay of ${input.csvDelay}")
            if (txIn.sequence != input.csvDelay) return Result.Refused("input $i does not wait the ${input.csvDelay} blocks its leaf asks")
            val cache = if (input.basepoint == SWAP_IN) {
                // The depositor's refund key for this address, derived here: never the proposer's.
                val (_, branch) = refundOf!!
                val refunds = swapIns!!
                val index = input.swapInIndex ?: return Result.Refused("input $i names no swap-in address")
                if (input.csvDelay != refunds.keys.refundDelay.toLong()) return Result.Refused("input $i waits ${input.csvDelay} blocks, not the swap-in's ${refunds.keys.refundDelay}")
                val address = refunds.keys.address(branch, index)
                if (ByteVector(input.script) != address.protocol.serializedPubkeyScript) return Result.Refused("input $i is not on the depositor's swap-in address $index")
                address.refundKey.cache
            } else {
                val basepoint = when (input.basepoint) {
                    PAYMENT -> basepoints.payment
                    DELAYED -> basepoints.delayedPayment
                    else -> return Result.Refused("input $i names no basepoint of the group's")
                }
                val tweak = input.tweak?.let { runCatching { ByteVector32(it) }.getOrElse { return Result.Refused("input $i has an unreadable tweak") } }
                if (input.basepoint == DELAYED && tweak == null) return Result.Refused("input $i is a delayed output with no per-commitment tweak")
                GroupLightningDerivation.leafCache(basepoint, tweak)
            }
            val leaf = leafScript(cache.tweakedPublicKey, input.csvDelay)
            if (ByteVector(input.leafScript) != leaf) return Result.Refused("input $i is not spent by the group's own leaf")
            val sighash = tx.hashForSigningTaprootScriptPath(i, spent, SigHash.SIGHASH_DEFAULT, ScriptTree.Leaf(leaf.toByteArray().let(Script::parse)).hash())
            CheckedInput(i, txIn.outPoint, spent[i], cache.tweakedPublicKey, cache, leaf, ByteVector(input.controlBlock), sighash)
        }

        val fee = spent.sumOf { it.amount.sat } - tx.txOut.sumOf { it.amount.sat }
        if (fee < 0) return Result.Refused("pays out more than it spends")
        val weight = witnessed(tx, inputs, inputs.map { ByteVector64.Zeroes }).weight()
        val checked = Checked(sweep, tx, inputs, Satoshi(fee), weight)
        if (checked.feeratePerVbyte > MAX_FEERATE_SAT_PER_VBYTE) return Result.Refused("pays ${checked.feeratePerVbyte} sats per vbyte in fees")
        return Result.Ok(checked)
    }

    /** [tx] with each input's witness: the group's signature, the leaf, and the control block. */
    fun witnessed(tx: Transaction, inputs: List<CheckedInput>, signatures: List<ByteVector64>): Transaction =
        inputs.zip(signatures).fold(tx) { acc, (input, signature) ->
            acc.updateWitness(input.index, ScriptWitness(listOf(signature, input.leafScript, input.controlBlock)))
        }

    /** What a proposal carries for [output], as lightning-kmp found it in a published transaction. */
    fun inputOf(output: GroupHeldOutputs.Output): Input {
        val witness = output.witness(ByteVector64.Zeroes)
        return Input(
            txid = output.outPoint.txid.toString(),
            index = output.outPoint.index,
            amountSat = output.txOut.amount.sat,
            script = Hex.encode(output.txOut.publicKeyScript.toByteArray()),
            basepoint = if (output.kind == GroupHeldOutputs.Kind.TO_REMOTE) PAYMENT else DELAYED,
            tweak = output.tweak?.toHex(),
            leafScript = Hex.encode(witness.stack[1].toByteArray()),
            controlBlock = Hex.encode(witness.stack[2].toByteArray()),
            csvDelay = output.csvDelay,
        )
    }

    /**
     * What a refund proposal carries for a swap-in coin at [outPoint] holding [amount], on [address], which
     * is [member]'s: the refund leaf and its control block, as lightning-kmp's own refund witness has them.
     */
    fun refundInputOf(outPoint: OutPoint, amount: Satoshi, address: GroupSwapInDerivation.Address, member: HexKey): Input {
        val witness = address.protocol.witnessRefund(ByteVector64.Zeroes)
        return Input(
            txid = outPoint.txid.toString(),
            index = outPoint.index,
            amountSat = amount.sat,
            script = Hex.encode(address.protocol.serializedPubkeyScript.toByteArray()),
            basepoint = SWAP_IN,
            leafScript = Hex.encode(witness.stack[1].toByteArray()),
            controlBlock = Hex.encode(witness.stack[2].toByteArray()),
            csvDelay = address.protocol.refundDelay.toLong(),
            member = member.lowercase(),
            swapInIndex = address.index,
        )
    }

    /**
     * A sweep of [inputs] to the onchain wallet's receive address [receiveIndex] -- or, with [branch], that
     * branch's address [receiveIndex] -- paying [feeratePerVbyte], or null when there is nothing left after the fee.
     */
    fun build(wallet: Long, inputs: List<Input>, account: GroupWalletDerivation.Account, receiveIndex: Long, feeratePerVbyte: Long, branch: Long? = null): Sweep? {
        if (inputs.isEmpty()) return null
        val to = GroupWalletDerivation.derive(account, branch ?: GroupWalletDerivation.RECEIVE, receiveIndex).pubkeyScript
        val total = inputs.sumOf { it.amountSat }
        fun txPaying(amount: Long) = Transaction(
            version = 2,
            txIn = inputs.map { TxIn(OutPoint(TxId(it.txid), it.index), ByteVector.empty, it.csvDelay) },
            txOut = listOf(TxOut(Satoshi(amount), to)),
            lockTime = 0,
        )
        // Weighed with stand-in signatures: a script-path witness is the same size whatever it signs.
        val draft = txPaying(total).let { tx ->
            inputs.foldIndexed(tx) { i, acc, input ->
                acc.updateWitness(i, ScriptWitness(listOf(ByteVector64.Zeroes, ByteVector(input.leafScript), ByteVector(input.controlBlock))))
            }
        }
        val fee = feeratePerVbyte * ((draft.weight().toLong() + 3) / 4)
        if (total - fee < DUST_SAT) return null
        return Sweep(wallet, Hex.encode(Transaction.write(txPaying(total - fee))), inputs, listOf(receiveIndex), branch)
    }

    /** The smallest output a sweep leaves: anything under is not worth the fee to sweep. */
    private const val DUST_SAT = 330L
}
