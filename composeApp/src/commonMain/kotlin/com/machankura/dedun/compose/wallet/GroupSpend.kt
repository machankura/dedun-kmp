package com.machankura.dedun.compose.wallet

import com.machankura.dedun.compose.managers.FrostSigningManager
import com.machankura.dedun.compose.managers.DepositBranch
import com.machankura.dedun.compose.managers.GroupWalletDerivation
import com.machankura.dedun.compose.managers.GuardianManager
import com.machankura.dedun.compose.managers.GuardianDerivation
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.ByteVector64
import fr.acinq.bitcoin.KeyPath
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.ScriptTree
import fr.acinq.bitcoin.ScriptWitness
import fr.acinq.bitcoin.SigHash
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxOut
import fr.acinq.bitcoin.XonlyPublicKey
import fr.acinq.bitcoin.crypto.frost.TweakCache
import fr.acinq.bitcoin.psbt.Input
import fr.acinq.bitcoin.psbt.Psbt
import fr.acinq.bitcoin.psbt.TaprootBip32DerivationPath
import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.utils.sum
import kotlin.io.encoding.Base64

/**
 * A spend from a group's wallet, checked the way every member checks it before
 * putting their share behind it.
 *
 * Nothing here trusts the proposer. A PSBT says which coins it spends, where
 * under the wallet's account each one is, and which outputs are change; every
 * one of those claims is rederived from the account and refused if it does not
 * reproduce the script it is about. The sighashes are then computed here, from
 * the PSBT itself -- the counterpart of rebuilding a proposed event's id from
 * its fields rather than taking the id on the wire.
 *
 * ### Why a PSBT's amounts can be believed without fetching anything
 *
 * Before taproot a signer had to see each spent output's whole transaction to
 * trust its amount, or a proposer could lie it down and turn the difference into
 * fee. BIP341's sighash commits to every spent amount and every spent script, so
 * a lie here produces signatures the network rejects -- not a larger fee. That
 * is what lets a member with no node of their own, and so no way to look a coin
 * up, still sign safely: the worst a lying proposer gets is a transaction nobody
 * can mine.
 *
 * ### Two ways to sign a coin
 *
 * The wallet's own group signs each input on its address's key path. An
 * ancestor of a guarded wallet signs through its leaf instead -- a [Spender.Guardian]
 * -- and then every input's sighash is BIP342's over that leaf, its sequence is
 * the leaf's lock, its signature verifies against the leaf key, and its witness
 * is the leaf and its control block. Everything else above holds unchanged: the
 * coins are still the descendant's, at places under its account, and each claim
 * is still rederived rather than believed. See `docs/group-wallet-guardians.md`.
 */
object GroupSpend {
    /**
     * The most inputs one spend signs: a transaction is a batch, one item per
     * input, so it is the batch cap.
     */
    const val MAX_INPUTS: Int = FrostSigningManager.MAX_BATCH_SIZE

    /**
     * How far down a branch a claimed derivation may point.
     *
     * A bound on work rather than on the wallet: each claim costs one derivation
     * to check, and a proposer must not be able to choose how much work everybody
     * else does. A member with no node has no watched range to bound it by, so it
     * is a constant, and far past any gap a group will leave.
     */
    const val MAX_ADDRESS_INDEX: Long = 100_000L

    /**
     * The highest feerate a spend may pay, in sats per virtual byte.
     *
     * Not a security check -- the approval shows the fee -- but a feerate typed
     * with three zeros too many is the likeliest way for a group to lose money,
     * and refusing it costs a retry.
     */
    const val MAX_FEERATE_SAT_PER_VBYTE: Long = 2_000L

    /** Who signs a spend's inputs. */
    sealed interface Spender {
        /** The wallet's own group, on each address's key path. */
        data object Owner : Spender

        /**
         * An ancestor of a guarded wallet, through its leaf: the guardian at
         * [position] in the account's guardians, whose key is [thresholdPublicKey]
         * walked down [lineage] -- `GuardianDerivation.signingCache`.
         *
         * [thresholdPublicKey] is null for a builder, which needs the leaf and
         * never the key that signs it; [check] refuses without it.
         */
        data class Guardian(
            val position: Int,
            val lineage: List<String>,
            val thresholdPublicKey: HexKey? = null,
            /** The guarded wallet's founding name, for the words a spend is shown in. */
            val name: String? = null,
            /** A member's label for the wallet, where they gave one: one person can have several. */
            val label: String? = null,
        ) : Spender
    }

    /** One input, and what its signature has to be over and verify against. */
    data class SpentInput(
        val index: Int,
        val outPoint: OutPoint,
        val txOut: TxOut,
        val derivation: GroupWalletDerivation.Derived,
        val sighash: ByteVector32,
        /** The leaf a guardian signs through, or null for a key-path input. */
        val leaf: ScriptTree.Leaf? = null,
        /** The key the input's signature verifies against: the address's output key, or the leaf key. */
        val signingKey: XonlyPublicKey = derivation.outputKey,
        /** The cache a session signs this input with. */
        val cache: TweakCache = derivation.cache,
    ) {
        /** The input's witness around [signature]. */
        fun witness(signature: ByteVector64): ScriptWitness = when (leaf) {
            null -> Script.witnessKeyPathPay2tr(signature)
            else -> Script.witnessScriptPathPay2tr(derivation.internalKey, leaf, ScriptWitness(listOf(signature)), derivation.tree!!)
        }
    }

    /** One output, and -- when its claim to be the wallet's own derives -- where under the account it is. */
    data class CheckedOutput(
        val index: Int,
        val txOut: TxOut,
        val change: GroupWalletDerivation.Derived?,
    ) {
        val isChange: Boolean get() = change != null
    }

    data class Checked(
        /** The account the spend was checked against -- and so the network its addresses are on. */
        val account: GroupWalletDerivation.Account,
        val psbt: Psbt,
        val inputs: List<SpentInput>,
        val outputs: List<CheckedOutput>,
        /** `Σ inputs − Σ outputs`, believable for the reason the object's kdoc gives. */
        val fee: Satoshi,
        /**
         * The finished transaction's weight. Exact before signing: a default-sighash
         * signature is always 64 bytes, and a guardian's leaf and control block are
         * known before it signs.
         */
        val weight: Int,
        val spender: Spender = Spender.Owner,
        /**
         * The payments that fund a descendant's guarded wallet, each claim derived
         * again -- [fundingsOf]. Empty until a caller holding the room's guarded
         * wallets has read them, which [check] alone cannot.
         */
        val fundings: List<Funding> = emptyList(),
    ) {
        val tx: Transaction get() = psbt.global.tx

        val payments: List<CheckedOutput> get() = outputs.filterNot { it.isChange }

        /** What leaves the group: every output that is not its own change. */
        val amountSent: Satoshi get() = payments.map { it.txOut.amount }.sum()

        /** Sats per virtual byte, rounded down. */
        val feeratePerVbyte: Long get() = fee.sat / vbytes(weight)

        /** Every spent output, in input order: what every sighash and `correctlySpends` take. */
        val spentOutputs: List<TxOut> get() = inputs.map { it.txOut }

        /** [tx] with each input's witness around its signature, in input order. */
        fun witnessed(signatures: List<ByteVector64>): Transaction =
            signatures.foldIndexed(tx) { index, partial, signature -> partial.updateWitness(index, inputs[index].witness(signature)) }
    }

    sealed interface Result {
        data class Ok(val checked: Checked) : Result
        data class Refused(val reason: String) : Result
    }

    /** A payment into a descendant's guarded wallet, at [derived] under its account -- the claim, derived again. */
    data class Funding(
        val outputIndex: Int,
        val wallet: GuardianManager.GuardedWallet,
        val derived: GroupWalletDerivation.Derived,
    )

    sealed interface FundingReading {
        data class Ok(val fundings: List<Funding>) : FundingReading
        data class Refused(val reason: String) : FundingReading
    }

    /**
     * Which of [checked]'s payments fund one of [guarded] -- the descendants'
     * guarded wallets the room holds -- read off each payment's derivation and
     * derived again, the way change is. `docs/guarded-wallet-funding.md`, D4:
     *
     *  - a claim under a wallet in [guarded] that derives to the payment's script
     *    is a funding;
     *  - a claim under a wallet in [guarded] that does not -- another index,
     *    another branch, another key -- refuses the spend, so no proposer can put
     *    a descendant's name on a payment to an address it does not own;
     *  - a claim under any other account is not this device's to check, and the
     *    payment is read as the bare address it is.
     *
     * Fingerprints are four bytes, so every held wallet sharing one is tried
     * before a claim is refused.
     */
    fun fundingsOf(checked: Checked, guarded: List<GuardianManager.GuardedWallet>): FundingReading {
        val fundings = checked.payments.mapNotNull { payment ->
            val paths = checked.psbt.outputs[payment.index].taprootDerivationPaths
            val (internalKey, path, candidates) = paths.entries.firstNotNullOfOrNull { (internalKey, path) ->
                guarded.filter { it.chain == checked.account.chain && isOurs(path, it.account) }
                    .takeIf { it.isNotEmpty() }
                    ?.let { Triple(internalKey, path, it) }
            } ?: return@mapNotNull null

            val (branch, index) = path.keyPath.path.takeIf { it.size == 2 && path.leaves.isEmpty() }
                ?: return FundingReading.Refused("output ${payment.index} claims a place in a guarded wallet that is not one")
            if (branch != GroupWalletDerivation.RECEIVE || index !in 0L..MAX_ADDRESS_INDEX) {
                return FundingReading.Refused("output ${payment.index} claims $branch/$index, which is not a receive address")
            }
            candidates.firstNotNullOfOrNull { wallet ->
                GroupWalletDerivation.derive(wallet.account, branch, index)
                    .takeIf { it.pubkeyScript == payment.txOut.publicKeyScript && it.internalKey == internalKey }
                    ?.let { Funding(payment.index, wallet, it) }
            } ?: return FundingReading.Refused(
                "output ${payment.index} claims $branch/$index of ${candidates.first().displayName ?: "a subgroup"}'s guarded wallet, which is not where it pays"
            )
        }
        return FundingReading.Ok(fundings)
    }

    /**
     * The checks a member makes before signing, in the order `docs/group-chain-wallet.md`
     * numbers them, against [account] -- which comes from the room's verified
     * statement and never from the proposal -- as [spender] would sign it.
     */
    fun check(psbt: Psbt, account: GroupWalletDerivation.Account, spender: Spender = Spender.Owner): Result {
        val tx = psbt.global.tx

        // A guardian's leaf, and what it locks: every one of its inputs carries
        // exactly that lock, and it signs as the key its walk reaches.
        val guardian = spender as? Spender.Guardian
        val delay = guardian?.let { account.guardians.getOrNull(it.position)?.delay ?: return Result.Refused("names a guardian this wallet does not have") }
        if (guardian != null && guardian.thresholdPublicKey == null) return Result.Refused("has no key to sign a guardian's leaf with")

        // 4. Bounded, before any per-input work.
        if (tx.txIn.isEmpty()) return Result.Refused("spends nothing")
        if (tx.txIn.size > MAX_INPUTS) return Result.Refused("spends ${tx.txIn.size} coins, more than the $MAX_INPUTS one spend may sign")
        if (tx.txOut.isEmpty()) return Result.Refused("pays nothing")
        if (tx.version != 2L) return Result.Refused("is version ${tx.version}, not 2")
        if (tx.lockTime != 0L) return Result.Refused("has a lock time")
        if (tx.txIn.any { it.sequence > MAX_SEQUENCE }) return Result.Refused("cannot be replaced: a sequence is above ${MAX_SEQUENCE}")
        if (delay != null && tx.txIn.any { it.sequence != delay }) return Result.Refused("has an input whose sequence is not its leaf's lock of $delay blocks")
        if (tx.txIn.map { it.outPoint }.toSet().size != tx.txIn.size) return Result.Refused("spends one coin twice")

        // 2 and 3. Every input is the group's, at a key path, under the default sighash.
        val claimed = psbt.inputs.mapIndexed { index, input ->
            val witness = input as? Input.WitnessInput.PartiallySignedWitnessInput
                ?: return Result.Refused("input $index is not an unsigned segwit input")
            if (input.sighashType != null && input.sighashType != SigHash.SIGHASH_DEFAULT) {
                return Result.Refused("input $index asks for sighash ${input.sighashType}; only the default is signed")
            }
            // Script-path fields -- a leaf script, a merkle root, a script
            // signature -- are ones this library does not parse, so they land
            // here. Any of them means the coin is not the plain key-path output
            // this wallet's addresses are.
            if (input.unknown.isNotEmpty()) return Result.Refused("input $index carries fields this wallet does not sign with")
            if (input.taprootKeySignature != null) return Result.Refused("input $index is already signed")

            // A coin may be on a member's deposit branch as well as on receive or change: it
            // is the group's all the same, since its derivation has to reproduce the coin
            // below. docs/member-deposits.md, Phase 2.
            val derivation = claimedDerivation(input.taprootDerivationPaths, account, memberBranches = true)
                ?: return Result.Refused("input $index does not name a place under this wallet's account")
            if (derivation.pubkeyScript != witness.txOut.publicKeyScript) {
                return Result.Refused("input $index claims ${derivation.change}/${derivation.index}, which is not the coin it spends")
            }
            if (input.taprootInternalKey != null && input.taprootInternalKey != derivation.internalKey) {
                return Result.Refused("input $index names an internal key its derivation does not reach")
            }
            Triple(index, witness.txOut, derivation)
        }

        val spentOutputs = claimed.map { it.second }
        val inputs = claimed.map { (index, txOut, derivation) ->
            if (guardian == null) {
                SpentInput(
                    index = index,
                    outPoint = tx.txIn[index].outPoint,
                    txOut = txOut,
                    derivation = derivation,
                    sighash = tx.hashForSigningTaprootKeyPath(index, spentOutputs, SigHash.SIGHASH_DEFAULT),
                )
            } else {
                val leaf = GuardianDerivation.leaves(account.guardians, account.chain, account.account, derivation.change, derivation.index)[guardian.position]
                val signing = GuardianDerivation.signingCache(
                    guardian.thresholdPublicKey!!, guardian.lineage, account.chain, account.account, derivation.change, derivation.index,
                )
                // The leaf in the descendant's address, and the key this group's
                // walk reaches, have to be one key -- or this group would sign for
                // a leaf it cannot spend.
                if (leaf.script != ByteVector(Script.write(GuardianDerivation.leafScript(signing.leafKey, delay!!)))) {
                    return Result.Refused("input $index's leaf is not this group's to sign")
                }
                SpentInput(
                    index = index,
                    outPoint = tx.txIn[index].outPoint,
                    txOut = txOut,
                    derivation = derivation,
                    sighash = tx.hashForSigningTaprootScriptPath(index, spentOutputs, SigHash.SIGHASH_DEFAULT, leaf.hash()),
                    leaf = leaf,
                    signingKey = signing.leafKey,
                    cache = signing.cache,
                )
            }
        }

        // 5. Change is change only if it derives.
        val outputs = psbt.outputs.mapIndexed { index, output ->
            val txOut = tx.txOut[index]
            val ours = output.taprootDerivationPaths.values.filter { isOurs(it, account) }
            val change = if (ours.isEmpty()) {
                null
            } else {
                val derivation = claimedDerivation(output.taprootDerivationPaths, account)
                    ?: return Result.Refused("output $index claims a place under this wallet's account that is not one")
                if (derivation.pubkeyScript != txOut.publicKeyScript) {
                    return Result.Refused("output $index claims ${derivation.change}/${derivation.index}, which is not where it pays")
                }
                derivation
            }
            CheckedOutput(index = index, txOut = txOut, change = change)
        }

        // 6. A sane fee.
        val fee = spentOutputs.map { it.amount }.sum() - tx.txOut.map { it.amount }.sum()
        if (fee.sat <= 0) return Result.Refused("pays out more than it spends")
        val weight = inputs.fold(tx) { partial, input -> partial.updateWitness(input.index, input.witness(ByteVector64.Zeroes)) }.weight()
        if (fee.sat > MAX_FEERATE_SAT_PER_VBYTE * vbytes(weight)) {
            return Result.Refused("pays a fee of ${fee.sat} sats, over $MAX_FEERATE_SAT_PER_VBYTE sats per byte")
        }

        return Result.Ok(Checked(account = account, psbt = psbt, inputs = inputs, outputs = outputs, fee = fee, weight = weight, spender = spender))
    }

    /**
     * The change indices under [account] that [psbt]'s outputs claim: what an
     * open spend holds, so the next one pays its change somewhere else. Claims,
     * not checked derivations -- a spend this device would refuse still keeps
     * its index from being handed out twice, and holding one too many costs
     * nothing.
     */
    fun claimedChangeIndices(psbt: Psbt, account: GroupWalletDerivation.Account): Set<Long> =
        psbt.outputs
            .flatMap { it.taprootDerivationPaths.values }
            .filter { isOurs(it, account) }
            .mapNotNull { path -> path.keyPath.path.takeIf { it.size == 2 && it[0] == GroupWalletDerivation.CHANGE }?.get(1) }
            .toSet()

    /**
     * The receive indices under [account] -- a descendant's guarded wallet --
     * that [psbt]'s outputs claim to fund: what an open funding holds, so the
     * next one pays a different address. Claims, as [claimedChangeIndices] are,
     * for the same reason. See `docs/guarded-wallet-funding.md`.
     */
    fun claimedFundingIndices(psbt: Psbt, account: GroupWalletDerivation.Account): Set<Long> =
        psbt.outputs
            .flatMap { it.taprootDerivationPaths.values }
            .filter { isOurs(it, account) }
            .mapNotNull { path -> path.keyPath.path.takeIf { it.size == 2 && it[0] == GroupWalletDerivation.RECEIVE }?.get(1) }
            .toSet()

    /**
     * The one derivation under [account] a PSBT field names, or null when it names
     * none, several, or one this wallet will not walk.
     *
     * Keyed by internal key in the PSBT, per BIP371; the key is checked against
     * the derivation rather than trusted, since it is the proposer's word.
     *
     * Receive and change, and -- with [memberBranches], for a coin being spent -- a
     * member's deposit branch (`DepositBranch`). Never for an output: change goes to
     * the change branch, and a member's branch is for what members pay in.
     */
    private fun claimedDerivation(
        paths: Map<fr.acinq.bitcoin.XonlyPublicKey, TaprootBip32DerivationPath>,
        account: GroupWalletDerivation.Account,
        memberBranches: Boolean = false,
    ): GroupWalletDerivation.Derived? {
        val (internalKey, path) = paths.entries.singleOrNull { isOurs(it.value, account) }
            ?.toPair()
            ?: return null
        if (path.leaves.isNotEmpty()) return null
        val (change, index) = path.keyPath.path.takeIf { it.size == 2 } ?: return null
        val known = change == GroupWalletDerivation.RECEIVE || change == GroupWalletDerivation.CHANGE ||
            (memberBranches && DepositBranch.isMemberBranch(change))
        if (!known) return null
        if (index !in 0L..MAX_ADDRESS_INDEX) return null

        return GroupWalletDerivation.derive(account, change, index).takeIf { it.internalKey == internalKey }
    }

    /**
     * Whether [path] names [account] as its master key.
     *
     * Compared as an unsigned 32-bit value. bitcoin-kmp reads a taproot
     * derivation's fingerprint back from a serialised PSBT as a *signed* int, so
     * an account whose fingerprint has its top bit set -- half of them -- would
     * write `3578966523` and read back `-716000773`, and every member but the
     * proposer would refuse every spend from that wallet as not its own.
     */
    private fun isOurs(path: TaprootBip32DerivationPath, account: GroupWalletDerivation.Account): Boolean =
        (path.masterKeyFingerprint and 0xFFFFFFFFL) == account.fingerprint

    /**
     * The PSBT a proposer sends: [tx] with every input's spent output and
     * derivation, and every change output's derivation, so that a signer is
     * *told* what is change -- and [check] is how they decide whether to believe it.
     *
     * A [funding] output -- one paying a descendant's guarded wallet -- carries
     * its derivation the same way, under the descendant's account rather than
     * this one, so it is never mistaken for change and a signer holding that
     * wallet can derive it again. See `docs/guarded-wallet-funding.md`.
     */
    fun psbtOf(
        tx: Transaction,
        spent: List<Pair<TxOut, GroupWalletDerivation.Derived>>,
        change: Map<Int, GroupWalletDerivation.Derived>,
        account: GroupWalletDerivation.Account,
        funding: Map<Int, Pair<GroupWalletDerivation.Account, GroupWalletDerivation.Derived>> = emptyMap(),
    ): Psbt {
        require(spent.size == tx.txIn.size) { "one spent output per input" }

        val withInputs = spent.foldIndexed(Psbt(tx)) { index, psbt, (txOut, derivation) ->
            psbt.updateWitnessInput(
                outPoint = tx.txIn[index].outPoint,
                txOut = txOut,
                taprootInternalKey = derivation.internalKey,
                taprootDerivationPaths = mapOf(derivation.internalKey to pathOf(derivation, account)),
            ).right ?: throw IllegalArgumentException("could not describe input $index")
        }

        val outputs = change.mapValues { (_, derivation) -> account to derivation } + funding
        return outputs.entries.fold(withInputs) { psbt, (index, claim) ->
            val (owner, derivation) = claim
            psbt.updateWitnessOutput(
                outputIndex = index,
                taprootInternalKey = derivation.internalKey,
                taprootDerivationPaths = mapOf(derivation.internalKey to pathOf(derivation, owner)),
            ).right ?: throw IllegalArgumentException("could not describe output $index")
        }
    }

    private fun pathOf(derivation: GroupWalletDerivation.Derived, account: GroupWalletDerivation.Account) =
        TaprootBip32DerivationPath(
            leaves = emptyList(),
            masterKeyFingerprint = account.fingerprint,
            keyPath = KeyPath(listOf(derivation.change, derivation.index)),
        )

    /** [tx] with a key-path witness on every input, in input order. */
    fun witnessed(tx: Transaction, signatures: List<ByteVector64>): Transaction =
        signatures.foldIndexed(tx) { index, partial, signature ->
            partial.updateWitness(index, Script.witnessKeyPathPay2tr(signature))
        }

    /**
     * The witness a guardian puts on [derivation]'s coin around [signature]: its
     * leaf in that address, and the control block that proves the leaf is there.
     * What a builder weighs a guardian spend by, before anybody signs.
     */
    fun guardianWitness(
        account: GroupWalletDerivation.Account,
        derivation: GroupWalletDerivation.Derived,
        position: Int,
        signature: ByteVector64 = ByteVector64.Zeroes,
    ): ScriptWitness {
        val leaf = GuardianDerivation.leaves(account.guardians, account.chain, account.account, derivation.change, derivation.index)[position]
        return Script.witnessScriptPathPay2tr(derivation.internalKey, leaf, ScriptWitness(listOf(signature)), derivation.tree!!)
    }

    fun encode(psbt: Psbt): String = Base64.Default.encode(Psbt.write(psbt).toByteArray())

    fun decode(content: String): Psbt? =
        runCatching { Base64.Default.decode(content.trim()) }.getOrNull()
            ?.let { Psbt.read(it).right }

    /** Every sequence at or below this signals BIP125 replaceability, so a stuck spend can be bumped by a new proposal. */
    const val MAX_SEQUENCE: Long = 0xfffffffdL

    private fun vbytes(weight: Int): Long = (weight.toLong() + 3) / 4
}
