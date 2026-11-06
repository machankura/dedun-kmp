package com.machankura.dedun.compose.wallet.wavelength.group

import com.machankura.dedun.compose.network.serialization.CommonJson
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.OP_0
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

    sealed class Verdict {
        /** Sign [message], which this device computed. Never the digest the request carried. */
        data class Allow(val what: What, val message: ByteVector32) : Verdict()
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
        /** What the group's authorisations let leave, by script, in sats. */
        val authorisedScripts: Map<ByteVector, Long> = emptyMap(),
        /** The payment hashes this group issued invoices for. */
        val issuedInvoices: Set<ByteVector32> = emptySet(),
        /** The most the group's unspent coins may come to, or null for no cap, and what they come to now. */
        val capSat: Long? = null,
        val unspentSat: Long = 0,
        /** The round each VTXO was last forfeited into, by `txid:vout`, until that round is known to have failed. */
        val forfeitedIn: Map<String, String> = emptyMap(),
    )

    private const val TAG_INDEXER = "wavelength/indexer/v1"
    private const val TAG_SWAP_AUTH = "swap-credit-account-auth-v1"
    private const val TAG_SWAP_ACK = "out-swap-htlc-ack-v1"

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

    private fun tx(hex: String?, what: String): Transaction =
        runCatching { Transaction.read(hex!!) }.getOrElse { refuse("a request with no readable $what") }

    private fun outputsOf(outputs: List<ArkOutput>): List<TxOut> =
        outputs.map { TxOut(Satoshi(it.value), bytes(it.pkScript, "prevout")) }

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
    }

    // ---- messages ----

    /**
     * A fund key signs a message only under a tag that can't be anything else: BIP-340 tags a digest, so none of these is
     * ever a transaction's sighash. They authenticate, and move nothing.
     */
    private fun message(context: Context, request: ArkRequest): Verdict {
        if (!request.schnorr || request.compact || request.doubleHash || !request.tapTweak.isNullOrEmpty()) refuse("a fund key signs only a plain tagged Schnorr message")
        val slot = context.keys.slotOf(request.family) ?: refuse("a message under a family the group holds no key in")
        if (slot == GroupArkDerivation.Slot.ONCHAIN) refuse("a message under the on-chain account")
        val message = bytes(request.message, "message")
        val tag = request.tag ?: refuse("a message with no tag")
        val digest = fr.acinq.bitcoin.Crypto.taggedHash(message.toByteArray(), tag)
        val swapKey = slot == GroupArkDerivation.Slot.OOR_RECEIVE && request.index == 0L
        return when {
            tag == TAG_INDEXER -> Verdict.Allow(What.PROOF, digest)
            tag == TAG_SWAP_AUTH && swapKey -> Verdict.Allow(What.SWAP_AUTH, digest)
            tag == TAG_SWAP_ACK && swapKey -> {
                // receiver key || payment hash || amount || vHTLC script: an acknowledgement for a payment we asked for.
                val raw = message.toByteArray()
                if (raw.size < 33 + 32 + 8) refuse("an acknowledgement that doesn't parse")
                val paymentHash = ByteVector32(raw.copyOfRange(33, 65))
                if (paymentHash !in context.issuedInvoices) refuse("an acknowledgement for a payment nobody here asked for")
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
        val own = context.keys.key(slot, request.branch, request.index)
        val leaf = bytes(request.leafScript, "leaf")
        val message = tx.hashForSigningTaprootScriptPath(request.inputIndex, prevOuts, SigHash.SIGHASH_DEFAULT, ArkScripts.leafHash(leaf))
        if (request.digest != null && bytes(request.digest, "digest") != ByteVector(message.toByteArray())) refuse("a digest that isn't this transaction's")
        val note = request.note

        val collab = ArkScripts.collabLeaf(own, context.operator)
        val exit = ArkScripts.timeoutLeaf(own, context.exitDelay)
        val spentScript = prevOuts[request.inputIndex].publicKeyScript
        val standard = ArkScripts.standardScript(own, context.operator, context.exitDelay)

        return when (leaf) {
            exit -> {
                if (spentScript != standard) refuse("a leaf this output doesn't commit to")
                if (note?.kind == ArkNote.JOIN) joinProof(request, note, tx, prevOuts, message)
                else exitSweep(context, tx, prevOuts, message)
            }
            collab -> when (note?.kind) {
                ArkNote.ROUND -> roundInput(context, request, note, tx, prevOuts, spentScript, standard, slot, message)
                ArkNote.OOR -> transferInput(context, request, note, tx, spentScript, standard, leaf, message)
                else -> refuse("a collaborative spend nothing explains")
            }
            else -> refuse("a leaf this device doesn't recognise as this key's")
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
        tx.txOut.forEach { if (!context.isOnchainWallet(it.publicKeyScript) && it.publicKeyScript != ANCHOR) refuse("an exit that pays somewhere other than the group's onchain wallet") }
        val fee = prevOuts.sumOf { it.amount.sat } - tx.txOut.sumOf { it.amount.sat }
        if (fee !in 0..context.maxOnchainFeeSat) refuse("an exit paying $fee sats in fees")
        return Verdict.Allow(What.EXIT_SWEEP, message)
    }

    /** The on-chain account's key path: back to itself, to the group's onchain wallet, or to an authorised script. */
    private fun onchainSpend(context: Context, request: ArkRequest, tx: Transaction, prevOuts: List<TxOut>): Verdict {
        if (request.method != ArkRequest.METHOD_BIP86) refuse("the on-chain account spends only by its key path")
        val slot = GroupArkDerivation.Slot.ONCHAIN
        val own = context.keys.key(slot, request.branch, request.index)
        val spentScript = ByteVector(Script.write(Script.pay2tr(own.xOnly(), fr.acinq.bitcoin.Crypto.TaprootTweak.KeyPathTweak)))
        if (prevOuts[request.inputIndex].publicKeyScript != spentScript) refuse("an on-chain input this account doesn't own")
        val message = tx.hashForSigningTaprootKeyPath(request.inputIndex, prevOuts, SigHash.SIGHASH_DEFAULT)
        if (request.digest != null && bytes(request.digest, "digest") != ByteVector(message.toByteArray())) refuse("a digest that isn't this transaction's")

        var budget = context.authorisedScripts
        tx.txOut.forEach { out ->
            val script = out.publicKeyScript
            val ownAccount = (0L..1L).any { branch ->
                (0L until 64L).any { i -> script == ByteVector(Script.write(Script.pay2tr(context.keys.key(slot, branch, i).xOnly(), fr.acinq.bitcoin.Crypto.TaprootTweak.KeyPathTweak))) }
            }
            when {
                ownAccount || context.isOnchainWallet(script) -> Unit
                (budget[script] ?: -1L) >= out.amount.sat -> budget = budget + (script to budget.getValue(script) - out.amount.sat)
                else -> refuse("an on-chain spend to a script nobody authorised")
            }
        }
        val fee = prevOuts.sumOf { it.amount.sat } - tx.txOut.sumOf { it.amount.sat }
        if (fee !in 0..context.maxOnchainFeeSat) refuse("an on-chain spend paying $fee sats in fees")
        return Verdict.Allow(What.ONCHAIN, message)
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
        checkRound(context, round, commitment)

        if (tx.txid == commitment.txid) {
            if (slot != GroupArkDerivation.Slot.BOARDING) refuse("a commitment input that isn't a boarding output")
            if (prevOuts != outputsOf(round.prevOuts)) refuse("a commitment with prevouts other than the round's")
            return Verdict.Allow(What.BOARDING, message)
        }

        if (slot == GroupArkDerivation.Slot.BOARDING) refuse("a boarding key signing something other than its commitment")
        if (request.inputIndex != 0 || tx.txIn.size != 2) refuse("a forfeit in any shape but VTXO then connector")
        val forfeit = round.forfeits.firstOrNull { it.vtxoOutpoint == outpoint(tx, 0) } ?: refuse("a forfeit of a VTXO the round isn't replacing")
        context.forfeitedIn[forfeit.vtxoOutpoint]?.let { if (it != note.id) refuse("a VTXO already forfeited into another round") }
        if (outpoint(tx, 1) != forfeit.connectorOutpoint) refuse("a forfeit over a connector other than the one assigned")
        if (prevOuts[0].amount.sat != forfeit.vtxoAmount) refuse("a forfeit that misstates its VTXO")
        if (prevOuts[1].amount.sat != forfeit.connectorAmount || prevOuts[1].publicKeyScript != bytes(forfeit.connectorPkScript, "connector")) refuse("a forfeit that misstates its connector")
        checkConnector(commitment, forfeit)

        val outputs = tx.txOut
        if (outputs.size != 2 || outputs[1].publicKeyScript != ANCHOR || outputs[1].amount != Satoshi(0)) refuse("a forfeit in any shape but penalty then anchor")
        if (outputs[0].amount.sat != forfeit.vtxoAmount + forfeit.connectorAmount) refuse("a forfeit that pays other than everything it spends")
        return Verdict.Allow(What.FORFEIT, message)
    }

    /** A tree node's digest, from a session this device holds with a key the group made. */
    private fun treeSignature(context: Context, request: ArkRequest, sessionKey: PublicKey): Verdict {
        val note = request.note?.takeIf { it.kind == ArkNote.ROUND } ?: refuse("a tree signature nothing explains")
        val round = decode(NotedRound.serializer(), note.material)
        val commitment = tx(round.commitmentTx, "commitment")
        checkRound(context, round, commitment)
        if (!context.isTreeKey(sessionKey)) refuse("a tree signature under a key the group didn't make")
        val digest = bytes(request.digest, "digest")
        val node = round.trees.flatMap { it.nodes }.firstOrNull { bytes(it.digest, "digest") == digest } ?: refuse("a digest of nothing in this round's trees")
        if (node.coSigners.none { key(it, "cosigner") == sessionKey }) refuse("a tree signature on a node this key isn't on")
        return Verdict.Allow(What.TREE, ByteVector32(digest.toByteArray()))
    }

    /**
     * The round, again, as if the client's own validation hadn't happened. Each noted tree hangs from the commitment, each
     * node's prevout is its cosigners' aggregate under the sweep root, and each digest is its node's. Each VTXO this wallet
     * asked for is a leaf paying a key this device derived, with a tree key the group made. What goes in comes back, in new
     * VTXOs and leaves the group has said it may pay, less a fee under the statement's cap.
     */
    private fun checkRound(context: Context, round: NotedRound, commitment: Transaction) {
        if (outputsOf(round.prevOuts).size != commitment.txIn.size) refuse("a commitment without every prevout")
        val sweepRoot = ArkScripts.leafHash(ArkScripts.timeoutLeaf(key(round.sweepKey, "sweep key"), round.sweepDelay))

        val leafOutputs = HashMap<ByteVector, MutableList<Pair<Long, List<PublicKey>>>>()
        round.trees.forEach { tree ->
            val batch = commitment.txOut.getOrNull(tree.outputIndex) ?: refuse("a tree hanging from an output the commitment doesn't have")
            if (batch != TxOut(Satoshi(tree.batchOutput.value), bytes(tree.batchOutput.pkScript, "batch output"))) refuse("a tree that misstates its batch output")
            if (bytes(tree.sweepRoot, "sweep root") != ByteVector(sweepRoot.toByteArray())) refuse("a tree whose sweep root isn't the round's")

            val nodes = tree.nodes.map { it to tx(it.tx, "tree transaction") }
            val byId = nodes.associateBy { it.second.txid }
            nodes.forEach { (noted, node) ->
                val spent = node.txIn.singleOrNull()?.outPoint ?: refuse("a tree transaction with other than one input")
                val prevout = if (spent == OutPoint(commitment, tree.outputIndex.toLong())) batch
                else byId[spent.txid]?.second?.txOut?.getOrNull(spent.index.toInt()) ?: refuse("a tree transaction that hangs from nothing")

                val cosigners = noted.coSigners.map { key(it, "cosigner") }
                if (context.operator !in cosigners) refuse("a tree transaction the operator isn't on")
                val (_, outputKey) = GroupArkSigner.treeCache(cosigners, sweepRoot)
                val expected = ByteVector(Script.write(listOf(fr.acinq.bitcoin.OP_1, OP_PUSHDATA(outputKey))))
                if (prevout.publicKeyScript != expected) refuse("a tree output that isn't its cosigners' aggregate")

                val digest = node.hashForSigningTaprootKeyPath(0, listOf(prevout), SigHash.SIGHASH_DEFAULT)
                if (bytes(noted.digest, "digest") != ByteVector(digest.toByteArray())) refuse("a tree digest that isn't its transaction's")

                node.txOut.forEach { out -> leafOutputs.getOrPut(out.publicKeyScript) { mutableListOf() }.add(out.amount.sat to cosigners) }
            }
        }

        // A signing key maps to exactly one VTXO, as `VerifyVTXOPath` has it, and the leaf has to be the one it asked for.
        val signingKeys = round.vtxos.map { it.signingKey }
        if (signingKeys.size != signingKeys.toSet().size) refuse("two VTXOs under one tree key")
        var ownOut = 0L
        round.vtxos.forEach { vtxo ->
            val owner = key(vtxo.ownerKey, "owner key")
            val signing = key(vtxo.signingKey, "signing key")
            if (!context.keys.ownsVtxoKey(owner)) refuse("a VTXO owned by a key this wallet didn't derive")
            if (!context.isTreeKey(signing)) refuse("a VTXO under a tree key the group didn't make")
            val script = ArkScripts.standardScript(owner, context.operator, context.exitDelay)
            if (bytes(vtxo.pkScript, "VTXO script") != script) refuse("a VTXO that doesn't pay its owner, the operator and the agreed delay")
            val leaf = leafOutputs[script]?.firstOrNull { (amount, cosigners) -> amount == vtxo.amount && signing in cosigners }
            if (leaf == null) refuse("a VTXO the tree doesn't pay")
            ownOut += vtxo.amount
        }

        // Leaves: real outputs of the commitment, to the group's own wallet, or within what the group authorised.
        var budget = context.authorisedScripts
        var leavesOut = 0L
        round.leaves.forEach { leave ->
            val script = bytes(leave.pkScript, "leave")
            if (commitment.txOut.none { it.publicKeyScript == script && it.amount.sat == leave.value }) refuse("a leave the commitment doesn't pay")
            when {
                context.isOnchainWallet(script) -> Unit
                (budget[script] ?: -1L) >= leave.value -> budget = budget + (script to budget.getValue(script) - leave.value)
                else -> refuse("a leave to a script nobody authorised")
            }
            leavesOut += leave.value
        }

        // Value: the group's own record of what it forfeits, never the note's, and the boarding inputs it can prove.
        var forfeited = 0L
        round.forfeits.forEach { forfeit ->
            val known = context.knownVtxos[forfeit.vtxoOutpoint] ?: refuse("a forfeit of a VTXO this device doesn't know of")
            if (known != forfeit.vtxoAmount) refuse("a forfeit for an amount other than the VTXO's")
            forfeited += known
        }
        var into = forfeited
        val boardingSlot = GroupArkDerivation.Slot.BOARDING
        outputsOf(round.prevOuts).forEach { prev ->
            val boarding = (0L until 64L).any { i -> prev.publicKeyScript == ArkScripts.standardScript(context.keys.key(boardingSlot, 0, i), context.operator, context.exitDelay) }
            if (boarding) into += prev.amount.sat
        }
        val out = ownOut + leavesOut
        val fee = into - out
        if (fee < 0) refuse("a round that says it pays out more than the group puts in")
        if (fee > context.maxRoundFeeSat) refuse("a round taking $fee sats, more than the group agreed to")

        context.capSat?.let { cap ->
            if (context.unspentSat - forfeited + ownOut > cap) refuse("a round taking the wallet over its cap")
        }
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
    private fun transferInput(context: Context, request: ArkRequest, note: ArkNote, tx: Transaction, spentScript: ByteVector, standard: ByteVector, leaf: ByteVector, message: ByteVector32): Verdict {
        val transfer = decode(NotedTransfer.serializer(), note.material)
        val ark = tx(transfer.ark.tx, "Ark transaction")
        val checkpoints = transfer.checkpoints.map { tx(it.tx, "checkpoint") to outputsOf(it.prevOuts) }
        if (checkpoints.isEmpty()) refuse("a transfer with no checkpoints")

        var into = 0L
        checkpoints.forEach { (checkpoint, prev) ->
            val input = prev.singleOrNull() ?: refuse("a checkpoint with other than one input")
            val outpointKey = outpoint(checkpoint, 0)
            val known = context.knownVtxos[outpointKey] ?: refuse("a checkpoint spending a VTXO this device doesn't know of")
            if (known != input.amount.sat) refuse("a checkpoint that misstates its VTXO")
            if (checkpoint.txOut.size != 2 || checkpoint.txOut[1].publicKeyScript != ANCHOR) refuse("a checkpoint in any shape but output then anchor")
            if (checkpoint.txOut[0].amount != input.amount) refuse("a checkpoint that doesn't carry its VTXO's whole value")
            if (ark.txIn.none { it.outPoint == OutPoint(checkpoint, 0) }) refuse("a checkpoint the Ark transaction doesn't spend")
            into += known
        }
        if (ark.txIn.size != checkpoints.size) refuse("an Ark transaction spending something other than its checkpoints")

        var budget = context.authorisedScripts
        var out = 0L
        ark.txOut.forEach { output ->
            val script = output.publicKeyScript
            if (script == ANCHOR && output.amount == Satoshi(0)) return@forEach
            val ownScript = (0L until 512L).any { i ->
                script == ArkScripts.standardScript(context.keys.key(GroupArkDerivation.Slot.OOR_RECEIVE, 0, i), context.operator, context.exitDelay)
            }
            when {
                ownScript -> Unit
                (budget[script] ?: -1L) >= output.amount.sat -> budget = budget + (script to budget.getValue(script) - output.amount.sat)
                else -> refuse("a transfer to a script nobody authorised")
            }
            out += output.amount.sat
        }
        if (out != into) refuse("a transfer that doesn't move exactly what it spends")

        return when {
            tx.txid == ark.txid -> {
                val checkpoint = checkpoints.firstOrNull { (c, _) -> tx.txIn[request.inputIndex].outPoint == OutPoint(c, 0) }?.first ?: refuse("an Ark input that isn't a checkpoint")
                if (spentScript != checkpoint.txOut[0].publicKeyScript) refuse("an Ark input that misstates its checkpoint")
                if (spentScript != ArkScripts.checkpointScript(context.operator, context.exitDelay, leaf)) refuse("a checkpoint that doesn't commit to this leaf")
                Verdict.Allow(What.TRANSFER, message)
            }
            checkpoints.any { (c, _) -> c.txid == tx.txid } -> {
                if (spentScript != standard) refuse("a leaf this output doesn't commit to")
                Verdict.Allow(What.TRANSFER, message)
            }
            else -> refuse("a transaction outside the transfer that was noted")
        }
    }
}
