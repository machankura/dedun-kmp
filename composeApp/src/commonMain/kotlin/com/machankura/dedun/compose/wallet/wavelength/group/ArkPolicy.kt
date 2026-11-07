package com.machankura.dedun.compose.wallet.wavelength.group

import com.machankura.dedun.compose.network.serialization.CommonJson
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.OP_0
import fr.acinq.bitcoin.OP_1
import fr.acinq.bitcoin.OP_PUSHDATA
import fr.acinq.bitcoin.OP_RETURN
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.SigHash
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxId
import fr.acinq.bitcoin.TxOut
import fr.acinq.bitcoin.Satoshi
import fr.acinq.secp256k1.Hex
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonElement

/**
 * Whether this device answers a request from the wallet's daemon -- `docs/group-wavelength-wallet.md`, Phase 7, and what
 * stops one member moving the group's money. A signer that signs what it is handed gives whoever runs the daemon the whole
 * wallet, so nothing here takes the operator's word: every digest is recomputed, every script rebuilt from keys this device
 * derived, and an unknown kind is a refusal.
 *
 * Pure: no database, no network, no clock. What the group has decided and what this device remembers arrive in [Context],
 * so the whole of the argument can be read here and reviewed alone.
 *
 * What it doesn't decide: whether the operator is telling the truth about what exists on the Ark. A round's trees are
 * checked for what they pay this wallet, and it is the operator's own co-signature that makes them real.
 */
object ArkPolicy {
    /** What an allowed request is, and so how the co-signer files it. */
    enum class What { PROOF, SWAP_AUTH, SWAP_ACK, TREE, FORFEIT, BOARDING, JOIN_PROOF, EXIT_SWEEP, ONCHAIN, TRANSFER }

    /**
     * What a signature means for this device's own record, which only the policy has worked out. It reserves and it pends, and never
     * says a thing has happened, since a request can be allowed and never submitted.
     */
    data class Effect(
        /** The commitment or Ark transaction this signature goes towards. */
        val towards: String,
        /** Its outputs that are this wallet's VTXOs, by `txid:vout`. */
        val credits: Map<String, Long>,
        /** The VTXOs it gives up, by `txid:vout`. */
        val reserves: List<String>,
        /** A round's note, of which there is only ever one. */
        val noteHash: String? = null,
        /** What it costs in fees, against the group's budget for them. */
        val feeSat: Long = 0,
        /** The Lightning payments it makes. */
        val payments: List<ByteVector32> = emptyList(),
        /** What each of those funds, and the vHTLC's script, which is how its refund is known: a refund's leaf names no hash. */
        val funded: List<Funded> = emptyList(),
        /** The vHTLC a refund brings home, by its script: one of the group's own payments that failed. */
        val refunded: ByteVector? = null,
        /** A round's commitment, whose confirmation is what makes the round so. */
        val commitmentTx: ByteVector? = null,
        /** A transfer's checkpoints, whose signatures being complete are what make the transfer so: it never reaches the chain. */
        val checkpoints: List<String> = emptyList(),
        /** The one of [checkpoints] this signature is on, or null for an Ark transaction's. */
        val signing: String? = null,
        /** A round's batch life in blocks, which with the commitment's height says when its coins can be swept. */
        val sweepDelay: Long? = null,
        /** The approved leaves a round pays, which are a member's payout once it happens. */
        val leaves: List<Leave> = emptyList(),
        /** The Lightning payment a claim brings in, by its hash, and what it keeps: somebody's deposit once it has settled. */
        val claimedHash: ByteVector32? = null,
        val claimedSat: Long = 0,
        /** The boarding coins a round brings in, which are somebody's deposits once it happens. */
        val boarded: List<BoardedCoin> = emptyList(),
    )

    sealed class Verdict {
        /** Sign [message], which this device computed. Never the digest the request carried. */
        data class Allow(val what: What, val message: ByteVector32, val effect: Effect? = null) : Verdict()
        data class Refuse(val reason: String) : Verdict()
    }

    class Context(
        val keys: GroupArkKeys,
        val operator: PublicKey,
        val exitDelay: Long,
        val maxRoundFeeSat: Long,
        val maxOnchainFeeSat: Long,
        /** Whether a tree key is one the group made and allocated for a VTXO. */
        val isTreeKey: (PublicKey) -> Boolean,
        /** Whether a script is an address of the group's onchain wallet, as this device derives them. */
        val isOnchainWallet: (ByteVector) -> Boolean,
        /** The VTXOs this device knows the group holds, by `txid:vout`, with their amounts: its own ledger. */
        val knownVtxos: Map<String, Long>,
        /** What a quorum of the group approved leaving in a round, by the script it pays (`docs/group-wavelength-payouts.md`, W4). */
        val authorisedLeaves: Map<ByteVector, AuthorisedLeave> = emptyMap(),
        /** The commitment this device has already paid each approved leave in, by the approval's id. A leave goes once. */
        val leftIn: Map<String, String> = emptyMap(),
        /** The most the group's unspent coins may come to, or null for no cap, and what they come to now. */
        val capSat: Long? = null,
        val unspentSat: Long = 0,
        /** How much of [unspentSat] is only pending, by what it comes from, so a round isn't counted against itself. */
        val pendingFromSat: Map<String, Long> = emptyMap(),
        /** What this device has already given each VTXO to, by `txid:vout`: a commitment's txid, or an Ark transaction's. */
        val reservedFor: Map<String, String> = emptyMap(),
        /** The one account of each round this device has signed anything under, by commitment txid: its note's hash. */
        val notedRounds: Map<String, String> = emptyMap(),
        /** The one commitment each tree key has signed for, by compressed key. */
        val treeKeyRounds: Map<String, String> = emptyMap(),
        /** The shortest life the group lets a batch of its VTXOs have, in blocks. */
        val minSweepDelay: Long = 0,
        /** What this device has lately agreed to pay in fees, by what it paid them on, and how far that may go. */
        val recentFeesSat: Map<String, Long> = emptyMap(),
        val maxRecentFeesSat: Long? = null,
        /** How long a boarding output waits to exit, which an operator sets apart from a VTXO's. */
        val boardingDelay: Long = exitDelay,
        /** The Lightning payments a quorum of the group has approved and that haven't expired, by payment hash. */
        val approvedPayments: Map<ByteVector32, ApprovedPayment> = emptyMap(),
        /** The Ark transaction this device has already paid each hash in. A payment goes once. */
        val paidIn: Map<ByteVector32, String> = emptyMap(),
    )

    /** A boarding coin going into a round: its outpoint, its script and what it holds. */
    data class BoardedCoin(val outpoint: String, val script: ByteVector, val amountSat: Long)

    /**
     * What the group approved leaving in a round, under the approval [id]: exactly [amountSat] to the script it's keyed by, in a round
     * costing at most [maxFeeSat], since the member it pays is charged that round's fee.
     */
    data class AuthorisedLeave(val id: String, val amountSat: Long, val maxFeeSat: Long)

    /** A Lightning payment's vHTLC as a transfer funds it: its hash, its script and what it holds, which is what the payment cost. */
    data class Funded(val paymentHash: ByteVector32, val script: ByteVector, val amountSat: Long)

    /** An approved leave a round pays: the approval, the script and what it gets. */
    data class Leave(val authorisation: String, val script: ByteVector, val amountSat: Long)

    /** What the group approved: at most [maxSat], and no vHTLC locked for the group past [maxRefundHeight]. */
    data class ApprovedPayment(val maxSat: Long, val maxRefundHeight: Long)

    private const val TAG_INDEXER = "wavelength/indexer/v1"
    private const val TAG_SWAP_AUTH = "swap-credit-account-auth-v1"
    private const val TAG_SWAP_ACK = "out-swap-htlc-ack-v1"
    private const val TAG_MAILBOX_AUTH = "mailbox-auth"

    /** The daemon's `MaxReasonableDelay`: about a year, in blocks. */
    private const val MAX_REASONABLE_DELAY = 52_560L

    /** Consensus's `MAX_MONEY`: nothing a real transaction holds can be more, or less than nothing. */
    private const val MAX_MONEY = 2_100_000_000_000_000L

    /** The anchor every virtual transaction ends in: `OP_1 <4e73>`, for nothing. */
    private val ANCHOR = ByteVector(Script.write(Script.pay2anchor))

    private class Refused(val reason: String) : Exception(reason)

    private fun refuse(reason: String): Nothing = throw Refused(reason)

    private fun <T> decode(serializer: DeserializationStrategy<T>, material: JsonElement): T =
        runCatching { CommonJson.decodeFromJsonElement(serializer, material) }.getOrElse { refuse("a note this device can't read") }

    private fun bytes(hex: String?, what: String): ByteVector =
        runCatching { ByteVector(Hex.decode(hex!!)) }.getOrElse { refuse("a request with no readable $what") }

    private fun key(hex: String, what: String): PublicKey =
        runCatching { PublicKey.fromHex(hex).takeIf { it.isValid() }!! }.getOrElse { refuse("a request with no readable $what") }

    /** A transaction, with the rule on its values that consensus has and a parser doesn't: every sum below relies on it. */
    private fun tx(hex: String?, what: String): Transaction {
        val tx = runCatching { Transaction.read(hex!!) }.getOrElse { refuse("a request with no readable $what") }
        var total = 0L
        tx.txOut.forEach {
            if (it.amount.sat !in 0..MAX_MONEY) refuse("a $what with an impossible value")
            total += it.amount.sat
            if (total > MAX_MONEY) refuse("a $what with an impossible value")
        }
        return tx
    }

    private fun outputsOf(outputs: List<ArkOutput>): List<TxOut> =
        outputs.map {
            if (it.value !in 0..MAX_MONEY) refuse("a note with an impossible value")
            TxOut(Satoshi(it.value), bytes(it.pkScript, "prevout"))
        }

    /** That paying [fee] on [id] keeps this device inside the group's budget. A repeat of the same thing isn't a second fee. */
    private fun checkFeeBudget(context: Context, id: String, fee: Long) {
        val budget = context.maxRecentFeesSat ?: return
        if (context.recentFeesSat.filterKeys { it != id }.values.sum() + fee > budget) refuse("more in fees than the group lets this wallet spend for now")
    }

    private fun outpoint(tx: Transaction, index: Int): String = tx.txIn[index].outPoint.let { "${it.txid}:${it.index}" }

    fun check(context: Context, request: ArkRequest, sessionKey: PublicKey? = null): Verdict = try {
        when (request.kind) {
            ArkRequest.MESSAGE -> message(context, request)
            ArkRequest.INPUT -> input(context, request)
            ArkRequest.MUSIG2_SIGN -> treeSignature(context, request, sessionKey ?: refuse("a tree signature for a session this device didn't start"))
            else -> refuse("a request of a kind this device doesn't answer: ${request.kind}")
        }
    } catch (e: Refused) {
        Verdict.Refuse(e.reason)
    } catch (e: Exception) {
        // Whatever a parser throws at something the operator wrote. Nothing here suspends, so no cancellation gets caught.
        Verdict.Refuse("a request that doesn't parse")
    }

    // ---- messages ----

    /**
     * A fund key signs a message only under a tag that can't be anything else: BIP-340 tags a digest, so none of these is
     * ever a transaction's sighash. They authenticate, and take nothing from the wallet's coins. A swap authorisation is a digest of a
     * request this device can't see, so what it authorises is the swap server's to say, and that includes redeeming credit: the group's
     * credit balance stays at nothing because the daemon's credit is closed (D10), not because of this.
     */
    private fun message(context: Context, request: ArkRequest): Verdict {
        if (!request.schnorr || request.compact || request.doubleHash || !request.tapTweak.isNullOrEmpty()) refuse("a fund key signs only a plain tagged Schnorr message")
        val slot = context.keys.slotOf(request.family) ?: refuse("a message under a family the group holds no key in")
        if (slot == GroupArkDerivation.Slot.ONCHAIN) refuse("a message under the on-chain account")
        val message = bytes(request.message, "message")
        val tag = request.tag ?: refuse("a message with no tag")
        val digest = fr.acinq.bitcoin.Crypto.taggedHash(message.toByteArray(), tag)
        val swapKey = slot == GroupArkDerivation.Slot.OOR_RECEIVE && request.index == 0L && request.branch == 0L
        return when {
            tag == TAG_INDEXER -> Verdict.Allow(What.PROOF, digest)
            tag == TAG_SWAP_AUTH && swapKey -> {
                // account key || request digest || expiry || nonce, as `CreditAccountAuthMessage` writes it.
                val raw = message.toByteArray()
                if (raw.size != 33 + 32 + 8 + 32) refuse("an authorisation that doesn't parse")
                if (!raw.copyOfRange(0, 33).contentEquals(context.keys.key(slot, 0, 0).value.toByteArray())) refuse("an authorisation in another key's name")
                Verdict.Allow(What.SWAP_AUTH, digest)
            }
            tag == TAG_MAILBOX_AUTH && swapKey -> {
                // sender key || recipient: leave to read a swap's mailbox, which is the swap key's and nobody else's to give.
                val raw = message.toByteArray()
                if (raw.size <= 33) refuse("a mailbox authorisation that doesn't parse")
                if (!raw.copyOfRange(0, 33).contentEquals(context.keys.key(slot, 0, 0).value.toByteArray())) refuse("a mailbox authorisation in another key's name")
                Verdict.Allow(What.PROOF, digest)
            }
            tag == TAG_SWAP_ACK && swapKey -> {
                // receiver key || payment hash || amount || vHTLC script. It tells the swap server this wallet has seen a payment to it,
                // which costs nothing: only the claim moves money, and that pays only the group. Whose payment it was is Phase 10's.
                val raw = message.toByteArray()
                if (raw.size < 33 + 32 + 8) refuse("an acknowledgement that doesn't parse")
                if (!raw.copyOfRange(0, 33).contentEquals(context.keys.key(slot, 0, 0).value.toByteArray())) refuse("an acknowledgement in another key's name")
                Verdict.Allow(What.SWAP_ACK, digest)
            }
            else -> refuse("a message under a tag this device doesn't sign: $tag")
        }
    }

    // ---- one input of a transaction ----

    private fun input(context: Context, request: ArkRequest): Verdict {
        val tx = tx(request.tx, "transaction")
        val prevOuts = outputsOf(request.prevOuts)
        if (prevOuts.size != tx.txIn.size || request.inputIndex !in tx.txIn.indices) refuse("a transaction without every prevout")

        return if (request.family == ArkRequest.FAMILY_ONCHAIN) onchainSpend(context, request, tx, prevOuts) else scriptSpend(context, request, tx, prevOuts)
    }

    private fun scriptSpend(context: Context, request: ArkRequest, tx: Transaction, prevOuts: List<TxOut>): Verdict {
        if (request.method != ArkRequest.METHOD_SCRIPT_PATH) refuse("a fund key spends only by a leaf")
        if (request.leafVersion != Script.TAPROOT_LEAF_TAPSCRIPT) refuse("a leaf of another version")
        val slot = context.keys.slotOf(request.family) ?: refuse("an input under a family the group holds no key in")
        // Only keys every check below would count: a boarding output past the window would go in as nothing.
        if (request.branch != 0L || request.index !in 0 until context.keys.window) refuse("a key outside the range this device checks")
        val own = context.keys.key(slot, request.branch, request.index)
        val leaf = bytes(request.leafScript, "leaf")
        val message = tx.hashForSigningTaprootScriptPath(request.inputIndex, prevOuts, SigHash.SIGHASH_DEFAULT, ArkScripts.leafHash(leaf))
        if (request.digest != null && bytes(request.digest, "digest") != ByteVector(message.toByteArray())) refuse("a digest that isn't this transaction's")
        val note = request.note

        val delay = if (slot == GroupArkDerivation.Slot.BOARDING) context.boardingDelay else context.exitDelay
        val collab = ArkScripts.collabLeaf(own, context.operator)
        val exit = ArkScripts.timeoutLeaf(own, delay)
        val spentScript = prevOuts[request.inputIndex].publicKeyScript
        val standard = ArkScripts.standardScript(own, context.operator, delay)

        return when (leaf) {
            exit -> {
                if (spentScript != standard) refuse("a leaf this output doesn't commit to")
                if (note?.kind == ArkNote.JOIN) joinProof(request, note, tx, prevOuts, message)
                else exitSweep(context, tx, prevOuts, message)
            }
            collab -> when (note?.kind) {
                ArkNote.ROUND -> roundInput(context, request, note, tx, prevOuts, spentScript, standard, slot, message)
                ArkNote.OOR -> transferInput(context, request, note, tx, prevOuts, spentScript, standard, leaf, message)
                else -> refuse("a collaborative spend nothing explains")
            }
            else -> {
                val swapKey = slot == GroupArkDerivation.Slot.OOR_RECEIVE && request.index == 0L
                val back = ArkScripts.claimHash(leaf, own) != null || ArkScripts.isRefundLeaf(leaf, own)
                if (!swapKey || !back) refuse("a leaf this device doesn't recognise as this key's")
                claimInput(context, request, note, tx, prevOuts, spentScript, leaf, message, ArkScripts.claimHash(leaf, own))
            }
        }
    }

    /**
     * A proof of funds. Its first input spends a to_spend transaction whose own input is a null outpoint, which can never
     * be mined, so this transaction can't either, whatever it says: that is what makes a signature over a VTXO's exit leaf
     * safe to give. Only the shape stops it being a real spend, so all of the shape is checked.
     */
    private fun joinProof(request: ArkRequest, note: ArkNote, tx: Transaction, prevOuts: List<TxOut>, message: ByteVector32): Verdict {
        val toSpend = tx(decode(NotedJoin.serializer(), note.material).toSpend, "to_spend")
        val nullInput = toSpend.txIn.singleOrNull() ?: refuse("a to_spend with other than one input")
        if (nullInput.outPoint.txid != TxId(ByteVector32.Zeroes) || nullInput.outPoint.index != 0xffffffffL) refuse("a to_spend that spends something real")
        val scriptSig = runCatching { Script.parse(nullInput.signatureScript) }.getOrElse { refuse("a to_spend that doesn't parse") }
        if (scriptSig.size != 2 || scriptSig[0] != OP_0 || (scriptSig[1] as? OP_PUSHDATA)?.data?.size() != 32) refuse("a to_spend that commits to no message")
        val challenge = toSpend.txOut.singleOrNull() ?: refuse("a to_spend with other than one output")
        if (challenge.amount != Satoshi(0)) refuse("a to_spend worth something")

        if (request.inputIndex == 0) refuse("a proof signed on its challenge input")
        val first = tx.txIn[0].outPoint
        if (first != OutPoint(toSpend, 0)) refuse("a proof whose first input isn't the to_spend")
        if (prevOuts[0] != challenge) refuse("a proof that misdescribes its challenge")

        val burn = tx.txOut.singleOrNull() ?: refuse("a proof with other than one output")
        if (burn.amount != Satoshi(0) || runCatching { Script.parse(burn.publicKeyScript) }.getOrNull() != listOf(OP_RETURN)) refuse("a proof that pays something")
        return Verdict.Allow(What.JOIN_PROOF, message)
    }

    /** A VTXO's exit leaf, alone: every output pays the group's onchain wallet, for a fee under the statement's cap. */
    private fun exitSweep(context: Context, tx: Transaction, prevOuts: List<TxOut>, message: ByteVector32): Verdict {
        // An anchor is anyone's to spend, so it is allowed for nothing, and only for nothing.
        tx.txOut.forEach {
            val anchor = it.publicKeyScript == ANCHOR && it.amount == Satoshi(0)
            if (!context.isOnchainWallet(it.publicKeyScript) && !anchor) refuse("an exit that pays somewhere other than the group's onchain wallet")
        }
        val fee = prevOuts.sumOf { it.amount.sat } - tx.txOut.sumOf { it.amount.sat }
        if (fee !in 0..context.maxOnchainFeeSat) refuse("an exit paying $fee sats in fees")
        return Verdict.Allow(What.EXIT_SWEEP, message)
    }

    /**
     * The on-chain account's key path: back to itself or to the group's onchain wallet. A payout's approval doesn't reach here: it pays
     * only a round's leave (`docs/group-wavelength-payouts.md`, W4).
     */
    private fun onchainSpend(context: Context, request: ArkRequest, tx: Transaction, prevOuts: List<TxOut>): Verdict {
        if (request.method != ArkRequest.METHOD_BIP86) refuse("the on-chain account spends only by its key path")
        val slot = GroupArkDerivation.Slot.ONCHAIN
        if (request.branch !in 0..1 || request.index !in 0 until context.keys.window) refuse("a key outside the range this device checks")
        val own = context.keys.key(slot, request.branch, request.index)
        val spentScript = ByteVector(Script.write(Script.pay2tr(own.xOnly(), fr.acinq.bitcoin.Crypto.TaprootTweak.KeyPathTweak)))
        if (prevOuts[request.inputIndex].publicKeyScript != spentScript) refuse("an on-chain input this account doesn't own")
        val message = tx.hashForSigningTaprootKeyPath(request.inputIndex, prevOuts, SigHash.SIGHASH_DEFAULT)
        if (request.digest != null && bytes(request.digest, "digest") != ByteVector(message.toByteArray())) refuse("a digest that isn't this transaction's")

        tx.txOut.forEach { out ->
            val script = out.publicKeyScript
            val ownAccount = (0L..1L).any { branch ->
                (0L until 64L).any { i -> script == ByteVector(Script.write(Script.pay2tr(context.keys.key(slot, branch, i).xOnly(), fr.acinq.bitcoin.Crypto.TaprootTweak.KeyPathTweak))) }
            }
            if (!ownAccount && !context.isOnchainWallet(script)) refuse("an on-chain spend to a script nobody authorised")
        }
        val fee = prevOuts.sumOf { it.amount.sat } - tx.txOut.sumOf { it.amount.sat }
        if (fee !in 0..context.maxOnchainFeeSat) refuse("an on-chain spend paying $fee sats in fees")
        val id = tx.txid.toString()
        checkFeeBudget(context, id, fee)
        return Verdict.Allow(What.ONCHAIN, message, Effect(id, emptyMap(), emptyList(), feeSat = fee))
    }

    // ---- a round ----

    /** A forfeit or a boarding input: what makes either safe is the round, so the round is checked first. */
    private fun roundInput(
        context: Context,
        request: ArkRequest,
        note: ArkNote,
        tx: Transaction,
        prevOuts: List<TxOut>,
        spentScript: ByteVector,
        standard: ByteVector,
        slot: GroupArkDerivation.Slot,
        message: ByteVector32,
    ): Verdict {
        if (spentScript != standard) refuse("a leaf this output doesn't commit to")
        val round = decode(NotedRound.serializer(), note.material)
        val commitment = tx(round.commitmentTx, "commitment")
        val checked = checkRound(context, round, commitment, note)
        val effect = checked.effect

        // Giving anything up is what comes after the tree is signed, and the daemon's own ordering isn't this device's to lean on: a
        // commitment that confirms over a tree its owner can't exit is a batch the operator sweeps at expiry.
        if (!checked.signed) refuse("a round whose tree nobody has shown to be signed")

        if (tx.txid == commitment.txid) {
            if (slot != GroupArkDerivation.Slot.BOARDING) refuse("a commitment input that isn't a boarding output")
            if (prevOuts != outputsOf(round.prevOuts)) refuse("a commitment with prevouts other than the round's")
            return Verdict.Allow(What.BOARDING, message, effect)
        }

        if (slot == GroupArkDerivation.Slot.BOARDING) refuse("a boarding key signing something other than its commitment")
        if (request.inputIndex != 0 || tx.txIn.size != 2) refuse("a forfeit in any shape but VTXO then connector")
        val forfeit = round.forfeits.firstOrNull { it.vtxoOutpoint == outpoint(tx, 0) } ?: refuse("a forfeit of a VTXO the round isn't replacing")
        if (outpoint(tx, 1) != forfeit.connectorOutpoint) refuse("a forfeit over a connector other than the one assigned")
        if (prevOuts[0].amount.sat != forfeit.vtxoAmount) refuse("a forfeit that misstates its VTXO")
        if (prevOuts[1].amount.sat != forfeit.connectorAmount || prevOuts[1].publicKeyScript != bytes(forfeit.connectorPkScript, "connector")) refuse("a forfeit that misstates its connector")
        checkConnector(commitment, forfeit)

        val outputs = tx.txOut
        if (outputs.size != 2 || outputs[1].publicKeyScript != ANCHOR || outputs[1].amount != Satoshi(0)) refuse("a forfeit in any shape but penalty then anchor")
        if (outputs[0].amount.sat != forfeit.vtxoAmount + forfeit.connectorAmount) refuse("a forfeit that pays other than everything it spends")
        return Verdict.Allow(What.FORFEIT, message, effect)
    }

    /**
     * A tree node's digest, from a session this device holds with a key the group made. A tree key signs only the path to its
     * own VTXO, in one round, ever: with the operator's help it could otherwise sign that VTXO away after the round is over.
     */
    private fun treeSignature(context: Context, request: ArkRequest, sessionKey: PublicKey): Verdict {
        val note = request.note?.takeIf { it.kind == ArkNote.ROUND } ?: refuse("a tree signature nothing explains")
        val round = decode(NotedRound.serializer(), note.material)
        val commitment = tx(round.commitmentTx, "commitment")
        val checked = checkRound(context, round, commitment, note)
        if (!context.isTreeKey(sessionKey)) refuse("a tree signature under a key the group didn't make")
        context.treeKeyRounds[sessionKey.toString()]?.let { if (it != commitment.txid.toString()) refuse("a tree key that has signed for another round") }

        val digest = bytes(request.digest, "digest")
        val node = round.trees.flatMap { it.nodes }.firstOrNull { bytes(it.digest, "digest") == digest } ?: refuse("a digest of nothing in this round's trees")
        if (node.coSigners.none { key(it, "cosigner") == sessionKey }) refuse("a tree signature on a node this key isn't on")
        val path = checked.paths[sessionKey.toString()] ?: refuse("a tree signature under a key no VTXO of this round uses")
        if (node.tx !in path) refuse("a tree signature off the path to this key's VTXO")

        // What the shares sign under is rebuilt from these, so they have to be exactly this node's.
        if (request.signers.sorted() != node.coSigners.sorted()) refuse("a session over other cosigners than this node's")
        val tree = round.trees.first { candidate -> node in candidate.nodes }
        if (request.scriptRoot != tree.sweepRoot) refuse("a session under another sweep root than this tree's")
        return Verdict.Allow(What.TREE, ByteVector32(digest.toByteArray()), checked.effect)
    }

    /**
     * What a round comes to: the path to each VTXO by tree key, as the raw transactions on it, whether every node on those paths is
     * shown signed, and what signing for it means.
     */
    private class CheckedRound(val paths: Map<String, Set<String>>, val signed: Boolean, val effect: Effect)

    /**
     * The hash of a round's account: it has one, so a second is either a mistake or an attack. The tree's signatures are left out, since
     * they arrive partway through a round, and add nothing to the account of it.
     */
    fun noteHash(note: ArkNote): String {
        val round = decode(NotedRound.serializer(), note.material)
        val unsigned = round.copy(trees = round.trees.map { tree -> tree.copy(nodes = tree.nodes.map { it.copy(signature = null) }) })
        return Hex.encode(fr.acinq.bitcoin.Crypto.sha256(CommonJson.encodeToString(NotedRound.serializer(), unsigned).encodeToByteArray()))
    }

    /**
     * The round, again, as if the client's own validation hadn't happened, with the rules `VerifyVTXOPath`, `Tree.Verify` and
     * `ValidateDelayParameters` have: each noted tree hangs from the commitment, each node keeps the value of the output it
     * spends and is its cosigners' aggregate under the sweep root, and each digest is its node's. Each VTXO this wallet asked for
     * is its own leaf, paying a key this device derived, with a tree key the group made that is on every node above it. What goes
     * in comes back, each output counted once, in new VTXOs and leaves the group has said it may pay, less a fee under the
     * statement's cap.
     *
     * The operator still names the sweep key, and its own cosigner on the tree, which is a key for this round and not the one the
     * statement fixes. Nothing this device holds says what either should be, and neither needs to: a sweep key can only do at the batch's
     * expiry what the operator can do then anyway, and a node can't be signed any other way without the group's own tree key on it. A retried round may reuse a tree key (`ensureVTXOSigningKeys`), and
     * is refused when it does: a member's coins wait for the next round, which is better than a key that signs twice.
     */
    private fun checkRound(context: Context, round: NotedRound, commitment: Transaction, note: ArkNote): CheckedRound {
        val id = commitment.txid.toString()
        context.notedRounds[id]?.let { if (it != noteHash(note)) refuse("a second account of a round this device already signed under") }
        if (outputsOf(round.prevOuts).size != commitment.txIn.size) refuse("a commitment without every prevout")

        if (context.exitDelay !in 1..MAX_REASONABLE_DELAY || round.sweepDelay > MAX_REASONABLE_DELAY) refuse("a delay longer than the daemon itself accepts")
        if (round.sweepDelay <= context.exitDelay) refuse("a sweep delay that lets the batch go before a VTXO can exit")
        if (round.sweepDelay < context.minSweepDelay) refuse("a batch with a shorter life than the group agreed")
        val sweepRoot = ArkScripts.leafHash(ArkScripts.timeoutLeaf(key(round.sweepKey, "sweep key"), round.sweepDelay))

        if (round.trees.map { it.outputIndex }.toSet().size != round.trees.size) refuse("two trees hanging from one output")
        class Node(val noted: NotedNode, val tx: Transaction, val cosigners: List<PublicKey>, val parent: TxId?, val outputKey: fr.acinq.bitcoin.XonlyPublicKey, val digest: ByteVector32)
        val nodes = HashMap<TxId, Node>()
        round.trees.forEach { tree ->
            val batch = commitment.txOut.getOrNull(tree.outputIndex) ?: refuse("a tree hanging from an output the commitment doesn't have")
            if (batch != TxOut(Satoshi(tree.batchOutput.value), bytes(tree.batchOutput.pkScript, "batch output"))) refuse("a tree that misstates its batch output")
            if (bytes(tree.sweepRoot, "sweep root") != ByteVector(sweepRoot.toByteArray())) refuse("a tree whose sweep root isn't the round's")

            val inTree = tree.nodes.map { it to tx(it.tx, "tree transaction") }
            val byId = inTree.associateBy { it.second.txid }
            val spends = HashSet<OutPoint>()
            inTree.forEach { (noted, node) ->
                val input = node.txIn.singleOrNull() ?: refuse("a tree transaction with other than one input")
                val spent = input.outPoint
                if (!spends.add(spent)) refuse("two tree transactions spending one output")
                // Nothing may hold an exit back past the batch's sweep: `Node.ToTx` writes these, and the sighash commits to them.
                if (node.lockTime != 0L || input.sequence != 0xffffffffL) refuse("a tree transaction that can't be published straight away")
                val fromBatch = spent == OutPoint(commitment, tree.outputIndex.toLong())
                val prevout = if (fromBatch) batch else byId[spent.txid]?.second?.txOut?.getOrNull(spent.index.toInt()) ?: refuse("a tree transaction that hangs from nothing")
                if (node.txOut.sumOf { it.amount.sat } != prevout.amount.sat) refuse("a tree transaction that doesn't keep the value it spends")

                val cosigners = noted.coSigners.map { key(it, "cosigner") }
                if (cosigners.toSet().size != cosigners.size) refuse("a tree transaction naming a cosigner twice")
                val (_, outputKey) = GroupArkSigner.treeCache(cosigners, sweepRoot)
                val expected = ByteVector(Script.write(listOf(OP_1, OP_PUSHDATA(outputKey))))
                if (prevout.publicKeyScript != expected) refuse("a tree output that isn't its cosigners' aggregate")

                val digest = node.hashForSigningTaprootKeyPath(0, listOf(prevout), SigHash.SIGHASH_DEFAULT)
                if (bytes(noted.digest, "digest") != ByteVector(digest.toByteArray())) refuse("a tree digest that isn't its transaction's")
                nodes[node.txid] = Node(noted, node, cosigners, if (fromBatch) null else spent.txid, outputKey, digest)
            }
        }

        // A signing key maps to exactly one VTXO, as `VerifyVTXOPath` has it, and each leaf pays only one of them.
        val signingKeys = round.vtxos.map { it.signingKey }
        if (signingKeys.size != signingKeys.toSet().size) refuse("two VTXOs under one tree key")
        val leavesUsed = HashSet<TxId>()
        val paths = HashMap<String, Set<String>>()
        val credits = HashMap<String, Long>()
        var signed = true
        var ownOut = 0L
        round.vtxos.forEach { vtxo ->
            val owner = key(vtxo.ownerKey, "owner key")
            val signing = key(vtxo.signingKey, "signing key")
            if (!context.keys.ownsVtxoKey(owner)) refuse("a VTXO owned by a key this wallet didn't derive")
            if (!context.isTreeKey(signing)) refuse("a VTXO under a tree key the group didn't make")
            val script = ArkScripts.standardScript(owner, context.operator, context.exitDelay)
            if (bytes(vtxo.pkScript, "VTXO script") != script) refuse("a VTXO that doesn't pay its owner, the operator and the agreed delay")
            val leaf = nodes.values.firstOrNull { node ->
                node.tx.txid !in leavesUsed && signing in node.cosigners && node.tx.txOut.size == 2 &&
                    node.tx.txOut[0] == TxOut(Satoshi(vtxo.amount), script) &&
                    node.tx.txOut[1] == TxOut(Satoshi(0), ANCHOR)
            } ?: refuse("a VTXO the tree doesn't pay")
            leavesUsed += leaf.tx.txid

            val path = HashSet<String>()
            var at: Node? = leaf
            while (at != null) {
                if (signing !in at.cosigners) refuse("a tree transaction above a VTXO that its tree key isn't on")
                path += at.noted.tx
                val signature = at.noted.signature?.let { runCatching { fr.acinq.bitcoin.ByteVector64(it) }.getOrNull() }
                if (signature == null || !fr.acinq.bitcoin.Crypto.verifySignatureSchnorr(at.digest, signature, at.outputKey)) signed = false
                at = at.parent?.let { nodes[it] ?: refuse("a tree transaction that hangs from nothing") }
            }
            paths[signing.toString()] = path
            credits["${leaf.tx.txid}:0"] = vtxo.amount
            ownOut += vtxo.amount
        }

        // Leaves: real outputs of the commitment, each counted once, to the group's own wallet or exactly as a quorum approved, once.
        // Exactly, because the daemon fixes a payout's leave and takes the fee from the change: a smaller one is the difference taken.
        val leavesPaid = HashSet<Int>()
        val approvedLeaves = ArrayList<Leave>()
        var leavesOut = 0L
        round.leaves.forEach { leave ->
            val script = bytes(leave.pkScript, "leave")
            val at = commitment.txOut.indices.firstOrNull { i ->
                i !in leavesPaid && commitment.txOut[i].publicKeyScript == script && commitment.txOut[i].amount.sat == leave.value
            } ?: refuse("a leave the commitment doesn't pay")
            leavesPaid += at
            val approved = context.authorisedLeaves[script]
            when {
                context.isOnchainWallet(script) -> Unit
                approved != null -> {
                    if (leave.value != approved.amountSat) refuse("a leave of ${leave.value} sats where the group approved ${approved.amountSat}")
                    if (approvedLeaves.any { it.authorisation == approved.id }) refuse("one approved leave paid twice in a round")
                    context.leftIn[approved.id]?.let { if (it != id) refuse("an approved leave already paid in another round") }
                    approvedLeaves += Leave(approved.id, script, leave.value)
                }
                else -> refuse("a leave to a script nobody authorised")
            }
            leavesOut += leave.value
        }

        // Value: the group's own record of what it forfeits, never the note's, and the boarding inputs it can prove.
        val forfeitedPoints = round.forfeits.map { it.vtxoOutpoint }
        if (forfeitedPoints.size != forfeitedPoints.toSet().size) refuse("one VTXO forfeited twice")
        var forfeited = 0L
        round.forfeits.forEach { forfeit ->
            val known = context.knownVtxos[forfeit.vtxoOutpoint] ?: refuse("a forfeit of a VTXO this device doesn't know of")
            if (known != forfeit.vtxoAmount) refuse("a forfeit for an amount other than the VTXO's")
            context.reservedFor[forfeit.vtxoOutpoint]?.let { if (it != id) refuse("a VTXO already given to another round or transfer") }
            forfeited += known
        }
        var into = forfeited
        val boarded = ArrayList<BoardedCoin>()
        outputsOf(round.prevOuts).forEachIndexed { index, prev ->
            if (context.keys.isBoardingScript(prev.publicKeyScript, context.operator, context.boardingDelay)) {
                into += prev.amount.sat
                commitment.txIn.getOrNull(index)?.outPoint?.let { boarded += BoardedCoin("${it.txid}:${it.index}", prev.publicKeyScript, prev.amount.sat) }
            }
            if (into > MAX_MONEY) refuse("a note with an impossible value")
        }
        val out = ownOut + leavesOut
        val fee = into - out
        if (fee < 0) refuse("a round that says it pays out more than the group puts in")
        if (fee > context.maxRoundFeeSat) refuse("a round taking $fee sats, more than the group agreed to")
        // The member a leave pays is charged the round's fee, so it can't pass what they were told it might be (W6).
        approvedLeaves.forEach { leave ->
            val limit = context.authorisedLeaves.values.first { it.id == leave.authorisation }.maxFeeSat
            if (fee > limit) refuse("a round taking $fee sats, more than the $limit a payout in it allows")
        }

        context.capSat?.let { cap ->
            if (context.unspentSat - (context.pendingFromSat[id] ?: 0) - forfeited + ownOut > cap) refuse("a round taking the wallet over its cap")
        }
        checkFeeBudget(context, id, fee)
        return CheckedRound(paths, signed, Effect(id, credits, forfeitedPoints, noteHash(note), fee, commitmentTx = ByteVector(Transaction.write(commitment)), sweepDelay = round.sweepDelay, boarded = boarded, leaves = approvedLeaves))
    }

    /** That a forfeit's connector comes from this commitment: the chain of transactions that makes the forfeit conditional. */
    private fun checkConnector(commitment: Transaction, forfeit: NotedForfeit) {
        val path = forfeit.connectorPath.map { tx(it, "connector path") }
        if (path.isEmpty()) refuse("a connector with no path to its commitment")
        val byId = path.associateBy { it.txid }
        val connector = forfeit.connectorOutpoint.split(":")
        val leaf = byId[TxId(connector[0])] ?: refuse("a connector path that doesn't reach the connector")
        var at: Transaction = leaf
        var steps = 0
        while (true) {
            val spent = at.txIn.singleOrNull()?.outPoint ?: refuse("a connector transaction with other than one input")
            if (spent.txid == commitment.txid) break
            at = byId[spent.txid] ?: refuse("a connector that doesn't descend from this commitment")
            if (++steps > path.size) refuse("a connector path that goes round in circles")
        }
    }

    // ---- a transfer ----

    /** A checkpoint or an Ark transaction: both are checked as the one package they only mean something as. */
    private fun transferInput(context: Context, request: ArkRequest, note: ArkNote, tx: Transaction, prevOuts: List<TxOut>, spentScript: ByteVector, standard: ByteVector, leaf: ByteVector, message: ByteVector32): Verdict {
        val transfer = decode(NotedTransfer.serializer(), note.material)
        val ark = tx(transfer.ark.tx, "Ark transaction")
        val arkId = ark.txid.toString()
        val checkpoints = transfer.checkpoints.map { tx(it.tx, "checkpoint") to outputsOf(it.prevOuts) }
        if (checkpoints.isEmpty()) refuse("a transfer with no checkpoints")
        if (checkpoints.map { it.first.txid }.toSet().size != checkpoints.size) refuse("a transfer naming one checkpoint twice")

        var into = 0L
        val spent = HashSet<String>()
        checkpoints.forEach { (checkpoint, prev) ->
            val input = prev.singleOrNull() ?: refuse("a checkpoint with other than one input")
            if (checkpoint.txIn.size != 1) refuse("a checkpoint with other than one input")
            val outpointKey = outpoint(checkpoint, 0)
            if (!spent.add(outpointKey)) refuse("a transfer spending one VTXO twice")
            val known = context.knownVtxos[outpointKey] ?: refuse("a checkpoint spending a VTXO this device doesn't know of")
            if (known != input.amount.sat) refuse("a checkpoint that misstates its VTXO")
            context.reservedFor[outpointKey]?.let { if (it != arkId) refuse("a VTXO already given to another round or transfer") }

            // The checkpoint's output is what a signature over it actually hands on, so it is the shape that matters most.
            val owner = context.keys.vtxoOwnerOf(input.publicKeyScript, context.operator, context.exitDelay) ?: refuse("a checkpoint spending a VTXO this wallet doesn't own")
            val expected = ArkScripts.checkpointScript(context.operator, context.exitDelay, ArkScripts.collabLeaf(owner, context.operator))
            if (checkpoint.txOut.size != 2 || checkpoint.txOut[1] != TxOut(Satoshi(0), ANCHOR)) refuse("a checkpoint in any shape but output then anchor")
            if (checkpoint.txOut[0] != TxOut(input.amount, expected)) refuse("a checkpoint that doesn't pay its own collaborative leaf")
            if (ark.txIn.none { it.outPoint == OutPoint(checkpoint, 0) }) refuse("a checkpoint the Ark transaction doesn't spend")
            into += known
        }
        if (ark.txIn.size != checkpoints.size) refuse("an Ark transaction spending something other than its checkpoints")

        val credits = HashMap<String, Long>()
        val payments = ArrayList<ByteVector32>()
        val funded = ArrayList<Funded>()
        var out = 0L
        ark.txOut.forEachIndexed { i, output ->
            val script = output.publicKeyScript
            if (script == ANCHOR && output.amount == Satoshi(0)) return@forEachIndexed
            val vhtlc = transfer.vhtlcs.map { vhtlcOf(it) }.firstOrNull { ArkScripts.vhtlcScript(it) == script }
            when {
                vhtlc != null -> {
                    checkPayment(context, vhtlc, output.amount.sat, arkId)
                    if (vhtlc.paymentHash in payments) refuse("one Lightning payment paid twice in a transfer")
                    payments += vhtlc.paymentHash
                    funded += Funded(vhtlc.paymentHash, script, output.amount.sat)
                }
                context.keys.vtxoOwnerOf(script, context.operator, context.exitDelay) != null -> credits["$arkId:$i"] = output.amount.sat
                // Never an approved leave's script: a VTXO at a member's onchain key is a coin their wallet can't see (W4).
                else -> refuse("a transfer to a script nobody authorised")
            }
            out += output.amount.sat
        }
        if (out != into) refuse("a transfer that doesn't move exactly what it spends")
        val effect = Effect(arkId, credits, spent.toList(), payments = payments, funded = funded, checkpoints = checkpoints.map { (c, _) -> c.txid.toString() })

        return when {
            tx.txid == ark.txid -> {
                val checkpoint = checkpoints.firstOrNull { (c, _) -> tx.txIn[request.inputIndex].outPoint == OutPoint(c, 0) }?.first ?: refuse("an Ark input that isn't a checkpoint")
                if (spentScript != checkpoint.txOut[0].publicKeyScript) refuse("an Ark input that misstates its checkpoint")
                if (spentScript != ArkScripts.checkpointScript(context.operator, context.exitDelay, leaf)) refuse("a checkpoint that doesn't commit to this leaf")
                Verdict.Allow(What.TRANSFER, message, effect)
            }
            checkpoints.any { (c, _) -> c.txid == tx.txid } -> {
                if (spentScript != standard) refuse("a leaf this output doesn't commit to")
                if (checkpoints.first { (c, _) -> c.txid == tx.txid }.second != prevOuts) refuse("a checkpoint with prevouts other than the transfer's")
                checkCoSigned(context, transfer, ark, checkpoints.map { it.first })
                Verdict.Allow(What.TRANSFER, message, effect.copy(signing = tx.txid.toString()))
            }
            else -> refuse("a transaction outside the transfer that was noted")
        }
    }

    /** A vHTLC from what the operator noted about it, in keys this device has checked are keys. */
    private fun vhtlcOf(noted: NotedVhtlc): ArkScripts.Vhtlc = ArkScripts.Vhtlc(
        sender = key(noted.sender, "vHTLC key"),
        receiver = key(noted.receiver, "vHTLC key"),
        server = key(noted.server, "vHTLC key"),
        paymentHash = ByteVector32(bytes(noted.paymentHash, "payment hash").toByteArray()),
        refundLocktime = noted.refundLocktime,
        claimDelay = noted.claimDelay,
        refundDelay = noted.refundDelay,
        refundWithoutReceiverDelay = noted.refundWithoutReceiverDelay,
    )

    /**
     * That a vHTLC is a payment the group approved. The script has already been rebuilt from these, so there is no leaf anybody hasn't
     * read. What matters then: the group can get it back, by its own swap key and this operator; only a hash a quorum approved, up to what
     * they approved, once; and nothing that ties the money up for longer than they agreed. Who the receiver is, is the proposer's word,
     * as with the group's Lightning wallet: an invoice names a payee, and members approved that invoice.
     */
    private fun checkPayment(context: Context, vhtlc: ArkScripts.Vhtlc, amountSat: Long, arkId: String) {
        val approved = context.approvedPayments[vhtlc.paymentHash] ?: refuse("a Lightning payment the group hasn't approved")
        if (amountSat > approved.maxSat) refuse("a Lightning payment of $amountSat sats, over the ${approved.maxSat} the group approved")
        context.paidIn[vhtlc.paymentHash]?.let { if (it != arkId) refuse("a Lightning payment already made in another transfer") }
        if (vhtlc.sender != context.keys.key(GroupArkDerivation.Slot.OOR_RECEIVE, 0, 0)) refuse("a vHTLC the group can't refund itself")
        if (vhtlc.server != context.operator) refuse("a vHTLC under another operator than the group's")
        if (vhtlc.refundLocktime !in 1..approved.maxRefundHeight) refuse("a vHTLC locked for longer than the group approved")
        if (listOf(vhtlc.claimDelay, vhtlc.refundDelay, vhtlc.refundWithoutReceiverDelay).any { it !in 1..MAX_REASONABLE_DELAY }) refuse("a vHTLC with an unreasonable delay")
    }

    /**
     * A vHTLC coming home: a Lightning payment in, claimed by the group's swap key, or one of its own payments that failed, refunded.
     * Into a VTXO of the group's own is the only thing either may do, and it is safe without knowing anything else about the vHTLC,
     * including who issued the invoice, because a signature commits to where the money goes and this is only ever a signature on a
     * transaction paying the group. BIP-341 holds it to the leaf and the prevout, so a leaf the output doesn't commit to signs nothing
     * that spends.
     */
    private fun claimInput(context: Context, request: ArkRequest, note: ArkNote?, tx: Transaction, prevOuts: List<TxOut>, spentScript: ByteVector, leaf: ByteVector, message: ByteVector32, claimedHash: ByteVector32?): Verdict {
        if (note?.kind != ArkNote.OOR) refuse("a claim nothing explains")
        val transfer = decode(NotedTransfer.serializer(), note.material)
        val ark = tx(transfer.ark.tx, "Ark transaction")
        val noted = transfer.checkpoints.singleOrNull() ?: refuse("a claim with other than one checkpoint")
        val checkpoint = tx(noted.tx, "checkpoint")
        val input = outputsOf(noted.prevOuts).singleOrNull() ?: refuse("a checkpoint with other than one input")
        if (checkpoint.txIn.size != 1) refuse("a checkpoint with other than one input")
        if (ark.txIn.size != 1 || ark.txIn[0].outPoint != OutPoint(checkpoint, 0)) refuse("an Ark transaction spending something other than its checkpoints")

        val expected = ArkScripts.checkpointScript(context.operator, context.exitDelay, leaf)
        if (checkpoint.txOut.size != 2 || checkpoint.txOut[1] != TxOut(Satoshi(0), ANCHOR)) refuse("a checkpoint in any shape but output then anchor")
        if (checkpoint.txOut[0] != TxOut(input.amount, expected)) refuse("a checkpoint that doesn't pay its own claim leaf")

        val arkId = ark.txid.toString()
        val credits = HashMap<String, Long>()
        var out = 0L
        ark.txOut.forEachIndexed { i, output ->
            if (output.publicKeyScript == ANCHOR && output.amount == Satoshi(0)) return@forEachIndexed
            if (context.keys.vtxoOwnerOf(output.publicKeyScript, context.operator, context.exitDelay) == null) refuse("a claim that pays somewhere other than the group's own keys")
            credits["$arkId:$i"] = output.amount.sat
            out += output.amount.sat
        }
        if (out != input.amount.sat) refuse("a claim that doesn't keep what it claims")
        context.capSat?.let { cap -> if (context.unspentSat - (context.pendingFromSat[arkId] ?: 0) + out > cap) refuse("a claim taking the wallet over its cap") }
        // A refund's hash is null, since that's the group's own money coming back. The leaf is what the checkpoint pays and the Ark server co-signs.
        val effect = Effect(
            arkId, credits, emptyList(), checkpoints = listOf(checkpoint.txid.toString()), claimedHash = claimedHash, claimedSat = out,
            refunded = input.publicKeyScript.takeIf { claimedHash == null },
        )

        return when {
            tx.txid == ark.txid -> {
                if (request.inputIndex != 0 || spentScript != expected) refuse("an Ark input that misstates its checkpoint")
                Verdict.Allow(What.TRANSFER, message, effect)
            }
            tx.txid == checkpoint.txid -> {
                if (outputsOf(noted.prevOuts) != prevOuts || request.inputIndex != 0) refuse("a checkpoint with prevouts other than the transfer's")
                checkLeafCoSigned(context, transfer, ark, listOf(expected to leaf))
                Verdict.Allow(What.TRANSFER, message, effect.copy(signing = tx.txid.toString()))
            }
            else -> refuse("a transaction outside the claim that was noted")
        }
    }

    /**
     * That the operator has signed every input of the Ark transaction. A checkpoint hands a VTXO over to something only the operator and
     * its owner can spend together, or the operator alone once the delay has passed. So until the owner holds the operator's half, signing
     * one gives the coin to the operator: it can publish the checkpoint, wait, and take it. The daemon checks this itself, which is no
     * comfort when the daemon is the thing being checked, and a device keeps this signature in its [GroupArkChecked][com.machankura.dedun.compose.database.model.GroupArkChecked]
     * row, so that the next operator can still complete the transfer.
     */
    private fun checkCoSigned(context: Context, transfer: NotedTransfer, ark: Transaction, checkpoints: List<Transaction>) {
        val spent = ark.txIn.map { input -> checkpoints.first { OutPoint(it, 0) == input.outPoint } }
        checkLeafCoSigned(context, transfer, ark, spent.map { checkpoint ->
            val noted = transfer.checkpoints.first { tx(it.tx, "checkpoint").txid == checkpoint.txid }
            val owner = context.keys.vtxoOwnerOf(bytes(noted.prevOuts.single().pkScript, "prevout"), context.operator, context.exitDelay)
                ?: refuse("a checkpoint spending a VTXO this wallet doesn't own")
            checkpoint.txOut[0].publicKeyScript to ArkScripts.collabLeaf(owner, context.operator)
        })
    }

    /** [inputs] are each Ark input's checkpoint script and the leaf that spends it. */
    private fun checkLeafCoSigned(context: Context, transfer: NotedTransfer, ark: Transaction, inputs: List<Pair<ByteVector, ByteVector>>) {
        val amounts = outputsOf(transfer.ark.prevOuts).map { it.amount }
        if (amounts.size != inputs.size) refuse("an Ark transaction without every prevout")
        val prevOuts = inputs.mapIndexed { i, (script, _) -> TxOut(amounts[i], script) }
        inputs.forEachIndexed { i, (_, leaf) ->
            val digest = ark.hashForSigningTaprootScriptPath(i, prevOuts, SigHash.SIGHASH_DEFAULT, ArkScripts.leafHash(leaf))
            val cosigned = transfer.ark.sigs.filter { it.input == i }.any { sig ->
                val signature = runCatching { fr.acinq.bitcoin.ByteVector64(sig.signature) }.getOrNull()
                signature != null && fr.acinq.bitcoin.Crypto.verifySignatureSchnorr(digest, signature, context.operator.xOnly())
            }
            if (!cosigned) refuse("a checkpoint for an Ark transaction the operator hasn't co-signed")
        }
    }
}
