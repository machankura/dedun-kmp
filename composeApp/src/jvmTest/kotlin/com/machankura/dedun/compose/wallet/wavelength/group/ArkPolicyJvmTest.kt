package com.machankura.dedun.compose.wallet.wavelength.group

import com.machankura.dedun.compose.network.serialization.CommonJson
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.OP_1
import fr.acinq.bitcoin.OP_PUSHDATA
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.SigHash
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxId
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import fr.acinq.lightning.crypto.IcebergSigner
import fr.acinq.secp256k1.Hex
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase 7 of `docs/group-wavelength-wallet.md`. The requests come from the fork: `round/group_policy_fixtures_test.go`
 * builds a round, a transfer, a join and an exit with the daemon's own builders and records what its callback signer asks.
 * So each allowed case is a real shape, and each refusal below changes one thing about one.
 */
class ArkPolicyJvmTest {
    @Serializable
    private data class Fixtures(
        val operator: String,
        @SerialName("sweep_key") val sweepKey: String,
        @SerialName("sweep_delay") val sweepDelay: Long,
        @SerialName("exit_delay") val exitDelay: Long,
        @SerialName("tree_key") val treeKey: String,
        @SerialName("onchain_script") val onchainScript: String,
        @SerialName("owner_key") val ownerKey: String,
        @SerialName("vtxo_script") val vtxoScript: String,
        @SerialName("tree_sign") val treeSign: ArkRequest,
        val forfeit: ArkRequest,
        val boarding: ArkRequest,
        @SerialName("join_proof") val joinProof: ArkRequest,
        @SerialName("exit_sweep") val exitSweep: ArkRequest,
        @SerialName("oor_ark") val oorArk: ArkRequest,
        @SerialName("oor_checkpoint") val oorCheckpoint: ArkRequest,
        @SerialName("indexer_message") val indexerMessage: ArkRequest,
    )

    private val fixtures: Fixtures = CommonJson.decodeFromString(
        Fixtures.serializer(),
        javaClass.getResource("/group-wavelength-policy-fixtures.json")!!.readText(),
    )

    private val group = IcebergSigner.keygen(4, 2, ByteVector32(Hex.decode("5a".repeat(32))))
    private val keys = GroupArkKeys(group.groupPublicKey, Chain.Regtest, 0)
    private val operator = PublicKey.fromHex(fixtures.operator)
    private val treeKey = PublicKey.fromHex(fixtures.treeKey)
    private val onchain = ByteVector(Hex.decode(fixtures.onchainScript))

    /** The VTXO the fixtures forfeit, and what the group's ledger says it is worth. */
    private val spent = "c0".padEnd(64, '0').let { Hex.encode(Hex.decode(it).reversedArray()) } + ":2"

    private fun context(
        known: Map<String, Long> = mapOf(spent to 40_000),
        maxRoundFeeSat: Long = 2_000,
        maxOnchainFeeSat: Long = 5_000,
        isTreeKey: (PublicKey) -> Boolean = { it == treeKey },
        isOnchain: (ByteVector) -> Boolean = { it == onchain },
        authorised: Map<ByteVector, Long> = emptyMap(),
        capSat: Long? = null,
        operatorKey: PublicKey = operator,
        exitDelay: Long = fixtures.exitDelay,
        unspentSat: Long = 40_000,
        pendingFromSat: Map<String, Long> = emptyMap(),
        reservedFor: Map<String, String> = emptyMap(),
        notedRounds: Map<String, String> = emptyMap(),
        treeKeyRounds: Map<String, String> = emptyMap(),
        minSweepDelay: Long = 0,
    ) = ArkPolicy.Context(
        keys, operatorKey, exitDelay, maxRoundFeeSat, maxOnchainFeeSat, isTreeKey, isOnchain, known, authorised, emptySet(),
        capSat, unspentSat, pendingFromSat, reservedFor, notedRounds, treeKeyRounds, minSweepDelay,
    )

    private fun allowed(request: ArkRequest, what: ArkPolicy.What, context: ArkPolicy.Context = context(), session: PublicKey? = null) {
        val verdict = ArkPolicy.check(context, request, session)
        assertIs<ArkPolicy.Verdict.Allow>(verdict, "$what: $verdict")
        assertEquals(what, verdict.what)
        assertEquals(request.digest ?: verdict.message.toHex(), verdict.message.toHex(), "the digest this device computed is the daemon's")
    }

    private fun refused(request: ArkRequest, why: String, context: ArkPolicy.Context = context(), session: PublicKey? = null) {
        val verdict = ArkPolicy.check(context, request, session)
        assertIs<ArkPolicy.Verdict.Refuse>(verdict, "expected a refusal for: $why")
        assertTrue(verdict.reason.contains(why), "expected '$why', got '${verdict.reason}'")
    }

    private fun ByteVector32.toHex() = Hex.encode(toByteArray())

    /** A request with its round's note changed, and the daemon's digest no longer claimed. */
    private fun ArkRequest.withRound(change: (NotedRound) -> NotedRound): ArkRequest = copy(
        note = note!!.copy(material = CommonJson.encodeToJsonElement(NotedRound.serializer(), change(CommonJson.decodeFromJsonElement(NotedRound.serializer(), note.material)))),
    )

    private fun ArkRequest.withTransfer(change: (NotedTransfer) -> NotedTransfer): ArkRequest = copy(
        note = note!!.copy(material = CommonJson.encodeToJsonElement(NotedTransfer.serializer(), change(CommonJson.decodeFromJsonElement(NotedTransfer.serializer(), note.material)))),
    )

    /** The request's transaction with an output changed, so that its digest is what a thief would be asking for. */
    private fun ArkRequest.payingInstead(script: ByteVector, value: Long? = null): ArkRequest {
        val tx = Transaction.read(this.tx!!)
        val out = tx.txOut.first()
        val changed = tx.copy(txOut = listOf(TxOut(value?.let(::Satoshi) ?: out.amount, script)) + tx.txOut.drop(1))
        return copy(tx = Transaction.write(changed).let(Hex::encode), digest = null)
    }

    private val thief = ByteVector(Hex.decode("5120" + "ee".repeat(32)))
    private val anchor = ByteVector(Script.write(Script.pay2anchor))
    private val stranger = PublicKey.fromHex("0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798")

    /** The tree signature as members see it: the transport fills in the session's cosigners and sweep root, which the daemon only names once. */
    private val treeSign: ArkRequest = fixtures.treeSign.let { request ->
        val round = CommonJson.decodeFromJsonElement(NotedRound.serializer(), request.note!!.material)
        val tree = round.trees.first { t -> t.nodes.any { it.digest == request.digest } }
        request.copy(signers = tree.nodes.first { it.digest == request.digest }.coSigners, scriptRoot = tree.sweepRoot)
    }

    private val commitmentId: String = Transaction.read(CommonJson.decodeFromJsonElement(NotedRound.serializer(), fixtures.forfeit.note!!.material).commitmentTx).txid.toString()

    private fun hex(tx: Transaction) = Hex.encode(Transaction.write(tx))

    private fun scriptOf(cosigners: List<PublicKey>, sweepRoot: ByteVector32) =
        ByteVector(Script.write(listOf(OP_1, OP_PUSHDATA(GroupArkSigner.treeCache(cosigners, sweepRoot).second))))

    /** A round this file builds whole, for the shapes the daemon wouldn't: batch, root, then a leaf paying a VTXO beside a sibling paying someone else. */
    private inner class Built(
        rootCosigners: List<PublicKey> = listOf(operator, treeKey),
        siblingCosigners: List<PublicKey> = listOf(operator, treeKey),
        sweepDelay: Long = 1_000,
        batchValue: Long = 50_000,
        vtxos: (NotedVtxo) -> List<NotedVtxo> = { listOf(it) },
    ) {
        private val sweepKey = operator
        private val sweepRoot = ArkScripts.leafHash(ArkScripts.timeoutLeaf(sweepKey, sweepDelay))
        private val owner = keys.key(GroupArkDerivation.Slot.VTXO_OWNER, 0, 5)
        private val vtxoScript = ArkScripts.standardScript(owner, operator, fixtures.exitDelay)
        private val leafCosigners = listOf(operator, treeKey)
        private val boarding = ArkScripts.standardScript(keys.key(GroupArkDerivation.Slot.BOARDING, 0, 0), operator, fixtures.exitDelay)

        val commitment = Transaction(3, listOf(TxIn(OutPoint(TxId("b0".repeat(32)), 0), 0xffffffffL)), listOf(TxOut(Satoshi(batchValue), scriptOf(rootCosigners, sweepRoot))), 0)
        val root = Transaction(
            3, listOf(TxIn(OutPoint(commitment, 0), 0xffffffffL)),
            listOf(TxOut(Satoshi(30_000), scriptOf(leafCosigners, sweepRoot)), TxOut(Satoshi(20_000), scriptOf(siblingCosigners, sweepRoot))), 0,
        )
        val leaf = Transaction(3, listOf(TxIn(OutPoint(root, 0), 0xffffffffL)), listOf(TxOut(Satoshi(30_000), vtxoScript), TxOut(Satoshi(0), anchor)), 0)
        val sibling = Transaction(3, listOf(TxIn(OutPoint(root, 1), 0xffffffffL)), listOf(TxOut(Satoshi(20_000), thief), TxOut(Satoshi(0), anchor)), 0)

        private fun noted(tx: Transaction, spent: TxOut, cosigners: List<PublicKey>) =
            NotedNode(hex(tx), Hex.encode(tx.hashForSigningTaprootKeyPath(0, listOf(spent), SigHash.SIGHASH_DEFAULT).toByteArray()), cosigners.map { it.toString() })

        val rootNode = noted(root, commitment.txOut[0], rootCosigners)
        val leafNode = noted(leaf, root.txOut[0], leafCosigners)
        val siblingNode = noted(sibling, root.txOut[1], siblingCosigners)

        val round = NotedRound(
            commitmentTx = hex(commitment),
            prevOuts = listOf(ArkOutput(30_000, Hex.encode(boarding.toByteArray()))),
            sweepKey = sweepKey.toString(),
            sweepDelay = sweepDelay,
            trees = listOf(NotedTree(0, ArkOutput(batchValue, Hex.encode(commitment.txOut[0].publicKeyScript.toByteArray())), Hex.encode(sweepRoot.toByteArray()), listOf(rootNode, leafNode, siblingNode))),
            vtxos = vtxos(NotedVtxo(treeKey.toString(), owner.toString(), 30_000, Hex.encode(vtxoScript.toByteArray()))),
        )

        fun signing(node: NotedNode, changed: (NotedRound) -> NotedRound = { it }) = ArkRequest(
            kind = ArkRequest.MUSIG2_SIGN, digest = node.digest, signers = node.coSigners, scriptRoot = round.trees[0].sweepRoot,
            note = ArkNote(ArkNote.ROUND, "built", CommonJson.encodeToJsonElement(NotedRound.serializer(), changed(round))),
        )
    }

    // ---- the real shapes pass ----

    @Test
    fun `the keys and scripts here are the daemon's`() {
        assertEquals(fixtures.ownerKey, keys.key(GroupArkDerivation.Slot.VTXO_OWNER, 0, 5).toString())
        assertEquals(fixtures.vtxoScript, Hex.encode(ArkScripts.standardScript(keys.key(GroupArkDerivation.Slot.VTXO_OWNER, 0, 5), operator, fixtures.exitDelay).toByteArray()))
    }

    @Test
    fun `every shape the daemon builds is allowed, and the digest computed here is the daemon's`() {
        allowed(treeSign, ArkPolicy.What.TREE, session = treeKey)
        allowed(fixtures.forfeit, ArkPolicy.What.FORFEIT)
        allowed(fixtures.boarding, ArkPolicy.What.BOARDING)
        allowed(fixtures.joinProof, ArkPolicy.What.JOIN_PROOF)
        allowed(fixtures.exitSweep, ArkPolicy.What.EXIT_SWEEP)
        allowed(fixtures.oorArk, ArkPolicy.What.TRANSFER)
        allowed(fixtures.oorCheckpoint, ArkPolicy.What.TRANSFER)
        assertIs<ArkPolicy.Verdict.Allow>(ArkPolicy.check(context(), fixtures.indexerMessage))
    }

    // ---- anything else is refused ----

    @Test
    fun `an unknown kind is refused`() {
        refused(fixtures.forfeit.copy(kind = "sign_anything"), "a kind this device doesn't answer")
    }

    @Test
    fun `a digest that isn't the transaction's is refused, whatever the note says`() {
        listOf(fixtures.forfeit, fixtures.boarding, fixtures.exitSweep, fixtures.oorArk).forEach {
            refused(it.copy(digest = "ab".repeat(32)), "a digest that isn't this transaction's")
        }
    }

    @Test
    fun `a key the group doesn't hold is refused`() {
        refused(fixtures.forfeit.copy(family = 7), "a family the group holds no key in")
        refused(fixtures.forfeit.copy(index = 4), "doesn't recognise as this key's")
    }

    @Test
    fun `an exit that pays anyone but the group's onchain wallet is refused`() {
        refused(fixtures.exitSweep.payingInstead(thief), "somewhere other than the group's onchain wallet")
        refused(fixtures.exitSweep, "somewhere other than the group's onchain wallet", context(isOnchain = { false }))
    }

    @Test
    fun `an exit paying over the fee cap is refused`() {
        refused(fixtures.exitSweep.payingInstead(onchain, 10_000), "in fees")
    }

    @Test
    fun `a collaborative spend with nothing to explain it is refused`() {
        refused(fixtures.forfeit.copy(note = null), "nothing explains")
        refused(fixtures.oorArk.copy(note = null), "nothing explains")
    }

    @Test
    fun `a forfeit that pays anything but everything to the penalty is refused`() {
        refused(fixtures.forfeit.payingInstead(thief, 1_000), "other than everything it spends")
    }

    @Test
    fun `a forfeit over someone else's connector is refused`() {
        refused(fixtures.forfeit.withRound { r -> r.copy(forfeits = r.forfeits.map { it.copy(connectorOutpoint = "aa".repeat(32) + ":0") }) }, "other than the one assigned")
    }

    @Test
    fun `a connector that doesn't descend from the commitment is refused`() {
        refused(fixtures.forfeit.withRound { r -> r.copy(forfeits = r.forfeits.map { it.copy(connectorPath = it.connectorPath.drop(1)) }) }, "doesn't descend from this commitment")
    }

    @Test
    fun `a forfeit of a VTXO the ledger doesn't know is refused`() {
        refused(fixtures.forfeit, "doesn't know of", context(known = emptyMap()))
    }

    @Test
    fun `a forfeit for more or less than the ledger says is refused`() {
        refused(fixtures.forfeit, "an amount other than the VTXO's", context(known = mapOf(spent to 39_000)))
    }

    @Test
    fun `a round whose VTXO pays someone else is refused`() {
        refused(fixtures.forfeit.withRound { r -> r.copy(vtxos = r.vtxos.map { it.copy(ownerKey = PublicKey.fromHex("0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798").toString()) }) }, "owned by a key this wallet didn't derive")
    }

    @Test
    fun `a round whose tree doesn't pay the VTXO it promised is refused`() {
        refused(fixtures.forfeit.withRound { r -> r.copy(vtxos = r.vtxos.map { it.copy(amount = it.amount + 1) }) }, "the tree doesn't pay")
        refused(treeSign.withRound { r -> r.copy(vtxos = r.vtxos.map { it.copy(amount = it.amount + 1) }) }, "the tree doesn't pay", session = treeKey)
    }

    @Test
    fun `a tree key the group didn't make is refused`() {
        refused(fixtures.forfeit, "a tree key the group didn't make", context(isTreeKey = { false }))
        refused(treeSign, "a tree key the group didn't make", context(isTreeKey = { false }), session = treeKey)
    }

    @Test
    fun `an operator or an exit delay other than the group agreed is refused`() {
        val other = PublicKey.fromHex("0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798")
        refused(fixtures.forfeit, "doesn't recognise as this key's", context(operatorKey = other))
        refused(fixtures.exitSweep, "doesn't recognise as this key's", context(exitDelay = fixtures.exitDelay + 1))
    }

    @Test
    fun `a tree whose node isn't its cosigners' aggregate is refused`() {
        refused(
            treeSign.withRound { r -> r.copy(trees = r.trees.map { t -> t.copy(nodes = t.nodes.map { n -> n.copy(coSigners = n.coSigners.reversed() + "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798") }) }) },
            "isn't its cosigners' aggregate", session = treeKey,
        )
    }

    @Test
    fun `a tree the operator isn't on is refused`() {
        refused(
            treeSign.withRound { r -> r.copy(trees = r.trees.map { t -> t.copy(nodes = t.nodes.map { n -> n.copy(coSigners = n.coSigners.filter { it != fixtures.operator }) }) }) },
            "the operator isn't on", session = treeKey,
        )
    }

    @Test
    fun `a tree digest that isn't its transaction's is refused`() {
        refused(
            treeSign.withRound { r -> r.copy(trees = r.trees.map { t -> t.copy(nodes = t.nodes.map { n -> n.copy(digest = "cd".repeat(32)) }) }) },
            "isn't its transaction's", session = treeKey,
        )
    }

    @Test
    fun `a tree signature on a digest outside the round, or a session it isn't on, is refused`() {
        refused(treeSign.copy(digest = "cd".repeat(32)), "a digest of nothing in this round's trees", session = treeKey)
        val otherTree: (PublicKey) -> Boolean = { true }
        refused(treeSign, "a node this key isn't on", context(isTreeKey = otherTree), session = keys.key(GroupArkDerivation.Slot.VTXO_OWNER, 0, 5))
        refused(treeSign, "a session this device didn't start")
    }

    @Test
    fun `a tree hanging from nothing in the commitment is refused`() {
        refused(fixtures.forfeit.withRound { r -> r.copy(trees = r.trees.map { it.copy(outputIndex = 9) }) }, "an output the commitment doesn't have")
        refused(fixtures.forfeit.withRound { r -> r.copy(trees = r.trees.map { it.copy(batchOutput = it.batchOutput.copy(value = 1)) }) }, "misstates its batch output")
    }

    @Test
    fun `a sweep root other than the round's is refused`() {
        refused(fixtures.forfeit.withRound { r -> r.copy(sweepDelay = r.sweepDelay + 1) }, "isn't the round's")
    }

    @Test
    fun `a leave the group didn't authorise is refused, and one it did is allowed`() {
        refused(fixtures.forfeit, "a leave to a script nobody authorised", context(isOnchain = { false }))
        allowed(fixtures.forfeit, ArkPolicy.What.FORFEIT, context(isOnchain = { false }, authorised = mapOf(onchain to 9_000)))
        refused(fixtures.forfeit, "a leave to a script nobody authorised", context(isOnchain = { false }, authorised = mapOf(onchain to 8_999)))
    }

    @Test
    fun `a leave the commitment doesn't pay is refused`() {
        refused(fixtures.forfeit.withRound { r -> r.copy(leaves = r.leaves.map { it.copy(value = it.value + 1) }) }, "a leave the commitment doesn't pay")
    }

    @Test
    fun `a round taking more than the agreed fee is refused`() {
        // 40,000 forfeited and 20,000 boarded, against 50,000 and a 9,000 leave: a fee of 1,000.
        refused(fixtures.forfeit, "more than the group agreed to", context(maxRoundFeeSat = 999))
        allowed(fixtures.forfeit, ArkPolicy.What.FORFEIT, context(maxRoundFeeSat = 1_000))
    }

    @Test
    fun `a round that says it pays out more than goes in is refused`() {
        refused(fixtures.forfeit.withRound { r -> r.copy(forfeits = r.forfeits.map { it.copy(vtxoAmount = it.vtxoAmount) }, prevOuts = r.prevOuts.map { it.copy(value = 1) }) }, "pays out more than the group puts in")
    }

    @Test
    fun `a round taking the wallet over its cap is refused`() {
        refused(fixtures.forfeit, "over its cap", context(capSat = 49_999))
        allowed(fixtures.forfeit, ArkPolicy.What.FORFEIT, context(capSat = 50_000))
    }

    @Test
    fun `a VTXO already given to another round or transfer is refused, and to the same commitment under any note id it isn't`() {
        refused(fixtures.forfeit, "already given to another round or transfer", context(reservedFor = mapOf(spent to "another round")))
        refused(fixtures.oorCheckpoint, "already given to another round or transfer", context(reservedFor = mapOf(spent to commitmentId)))
        allowed(fixtures.forfeit, ArkPolicy.What.FORFEIT, context(reservedFor = mapOf(spent to commitmentId)))
        allowed(fixtures.forfeit.copy(note = fixtures.forfeit.note!!.copy(id = "a retry")), ArkPolicy.What.FORFEIT, context(reservedFor = mapOf(spent to commitmentId)))
    }

    // ---- what the independent review found ----

    @Test
    fun `an exit can't pay its value to an anchor, which anyone can spend`() {
        refused(fixtures.exitSweep.payingInstead(anchor), "somewhere other than the group's onchain wallet")
        val tx = Transaction.read(fixtures.exitSweep.tx!!)
        val half = tx.txOut[0].amount.sat / 2
        val split = tx.copy(txOut = listOf(TxOut(Satoshi(half), tx.txOut[0].publicKeyScript), TxOut(Satoshi(half), anchor)))
        refused(fixtures.exitSweep.copy(tx = hex(split), digest = null), "somewhere other than the group's onchain wallet")
    }

    /** The checkpoint a thief would like signed, with an Ark transaction beside it that looks like the real one. */
    private fun decoyCheckpoint(change: (Transaction) -> Transaction): ArkRequest {
        val stolen = change(Transaction.read(fixtures.oorCheckpoint.tx!!))
        return fixtures.oorCheckpoint.copy(tx = hex(stolen), digest = null).withTransfer { t ->
            val ark = Transaction.read(t.ark.tx)
            t.copy(
                ark = t.ark.copy(tx = hex(ark.copy(txIn = ark.txIn.map { it.copy(outPoint = OutPoint(stolen, 0)) }))),
                checkpoints = t.checkpoints.map { it.copy(tx = hex(stolen)) },
            )
        }
    }

    @Test
    fun `a checkpoint paying a thief is refused, however good its Ark transaction looks`() {
        refused(decoyCheckpoint { it.copy(txOut = listOf(TxOut(it.txOut[0].amount, thief)) + it.txOut.drop(1)) }, "doesn't pay its own collaborative leaf")
        refused(decoyCheckpoint { it.copy(txOut = listOf(it.txOut[0], TxOut(Satoshi(1), anchor))) }, "output then anchor")
        refused(decoyCheckpoint { it.copy(txOut = it.txOut + TxOut(Satoshi(1), thief)) }, "output then anchor")
        val twoInputs = decoyCheckpoint { it.copy(txIn = it.txIn + TxIn(OutPoint(TxId("dd".repeat(32)), 0), 0)) }
        refused(twoInputs.copy(prevOuts = twoInputs.prevOuts + twoInputs.prevOuts), "other than one input")
    }

    @Test
    fun `a transfer naming a checkpoint twice, or an Ark transaction with an input of its own, is refused`() {
        refused(fixtures.oorCheckpoint.withTransfer { it.copy(checkpoints = it.checkpoints + it.checkpoints) }, "one checkpoint twice")
        refused(
            fixtures.oorCheckpoint.withTransfer { t ->
                val ark = Transaction.read(t.ark.tx)
                t.copy(ark = t.ark.copy(tx = hex(ark.copy(txIn = ark.txIn + TxIn(OutPoint(TxId("dd".repeat(32)), 0), 0)))))
            },
            "something other than its checkpoints",
        )
    }

    @Test
    fun `a checkpoint's prevouts have to be the transfer's`() {
        val misstated = fixtures.oorCheckpoint.prevOuts.map { it.copy(value = it.value - 1) }
        refused(fixtures.oorCheckpoint.copy(prevOuts = misstated, digest = null), "prevouts other than the transfer's")
    }

    @Test
    fun `a transfer signs in the order a real one does, the Ark transaction first`() {
        val first = assertIs<ArkPolicy.Verdict.Allow>(ArkPolicy.check(context(), fixtures.oorArk))
        val effect = assertNotNull(first.effect)
        assertEquals(listOf(spent), effect.reserves)
        allowed(fixtures.oorCheckpoint, ArkPolicy.What.TRANSFER, context(reservedFor = effect.reserves.associateWith { effect.towards }))
    }

    @Test
    fun `a tree key signs its own VTXO's path, and nothing else`() {
        val built = Built()
        allowed(built.signing(built.leafNode), ArkPolicy.What.TREE, session = treeKey)
        allowed(built.signing(built.rootNode), ArkPolicy.What.TREE, session = treeKey)
        refused(built.signing(built.siblingNode), "off the path to this key's VTXO", session = treeKey)
    }

    @Test
    fun `a tree key replayed with an empty note, or one that has signed for another round, is refused`() {
        val empty = treeSign.withRound { r -> r.copy(vtxos = emptyList(), leaves = emptyList(), forfeits = emptyList(), prevOuts = r.prevOuts.map { it.copy(pkScript = "51") }) }
        refused(empty, "a key no VTXO of this round uses", session = treeKey)
        refused(treeSign, "has signed for another round", context(treeKeyRounds = mapOf(treeKey.toString() to "another")), session = treeKey)
        allowed(treeSign, ArkPolicy.What.TREE, context(treeKeyRounds = mapOf(treeKey.toString() to commitmentId)), session = treeKey)
    }

    @Test
    fun `a session over other cosigners or another sweep root than its node's is refused`() {
        refused(treeSign.copy(signers = treeSign.signers + treeSign.signers.first()), "other cosigners", session = treeKey)
        refused(treeSign.copy(signers = treeSign.signers.drop(1)), "other cosigners", session = treeKey)
        refused(treeSign.copy(signers = emptyList()), "other cosigners", session = treeKey)
        refused(treeSign.copy(scriptRoot = "ab".repeat(32)), "another sweep root", session = treeKey)
        refused(treeSign.copy(scriptRoot = null), "another sweep root", session = treeKey)
    }

    @Test
    fun `a node above a VTXO that its tree key isn't on is refused, as the daemon does`() {
        val built = Built(rootCosigners = listOf(operator, stranger))
        refused(built.signing(built.leafNode), "that its tree key isn't on", session = treeKey)
    }

    @Test
    fun `a tree that doesn't keep its value is refused, as the daemon does`() {
        val built = Built(batchValue = 50_001)
        refused(built.signing(built.leafNode), "doesn't keep the value it spends", session = treeKey)
    }

    @Test
    fun `a batch that can be swept before a VTXO can exit, or sooner than the group agreed, is refused`() {
        val built = Built(sweepDelay = fixtures.exitDelay)
        refused(built.signing(built.leafNode), "before a VTXO can exit", session = treeKey)
        val tiny = Built(sweepDelay = 1_000)
        refused(tiny.signing(tiny.leafNode), "a shorter life", context(minSweepDelay = 1_001), session = treeKey)
        val forever = Built(sweepDelay = 52_561)
        refused(forever.signing(forever.leafNode), "longer than the daemon itself accepts", session = treeKey)
    }

    @Test
    fun `one output is counted once, however many times a note names it`() {
        val built = Built()
        refused(built.signing(built.leafNode) { r -> r.copy(vtxos = r.vtxos + r.vtxos) }, "two VTXOs under one tree key", session = treeKey)
        val otherKey = keys.key(GroupArkDerivation.Slot.VTXO_OWNER, 0, 6)
        val twice = Built(vtxos = { listOf(it, it.copy(signingKey = otherKey.toString())) })
        refused(twice.signing(twice.leafNode), "the tree doesn't pay", context(isTreeKey = { true }), session = treeKey)
        refused(fixtures.forfeit.withRound { r -> r.copy(leaves = r.leaves + r.leaves) }, "a leave the commitment doesn't pay")
        refused(fixtures.forfeit.withRound { r -> r.copy(forfeits = r.forfeits + r.forfeits) }, "forfeited twice")
    }

    @Test
    fun `a round has one account, whichever request first gave it`() {
        val hash = ArkPolicy.noteHash(fixtures.forfeit.note!!)
        allowed(fixtures.forfeit, ArkPolicy.What.FORFEIT, context(notedRounds = mapOf(commitmentId to hash)))
        refused(fixtures.forfeit, "a second account of a round", context(notedRounds = mapOf(commitmentId to "another")))
        refused(treeSign, "a second account of a round", context(notedRounds = mapOf(commitmentId to "another")), session = treeKey)
    }

    @Test
    fun `a key past the window every check looks through is refused, rather than counted as nothing`() {
        refused(fixtures.boarding.copy(index = keys.window), "outside the range this device checks")
        refused(fixtures.boarding.copy(branch = 1), "outside the range this device checks")
        refused(fixtures.forfeit.copy(index = -1), "outside the range this device checks")
    }

    @Test
    fun `what a round means for the ledger comes from what the tree really pays`() {
        val verdict = assertIs<ArkPolicy.Verdict.Allow>(ArkPolicy.check(context(), Built().let { it.signing(it.leafNode) }, treeKey))
        val built = Built()
        assertEquals(mapOf("${built.leaf.txid}:0" to 30_000L), verdict.effect!!.credits)
        assertEquals(built.commitment.txid.toString(), verdict.effect!!.towards)
        assertEquals(listOf(spent), assertIs<ArkPolicy.Verdict.Allow>(ArkPolicy.check(context(), fixtures.forfeit)).effect!!.reserves)
    }

    @Test
    fun `the cap counts the coins a round replaces once, and not its own pending ones again`() {
        allowed(fixtures.forfeit, ArkPolicy.What.FORFEIT, context(capSat = 50_000, unspentSat = 90_000, pendingFromSat = mapOf(commitmentId to 50_000)))
        refused(fixtures.forfeit, "over its cap", context(capSat = 50_000, unspentSat = 90_000))
    }

    @Test
    fun `a boarding key signing anything but its commitment is refused`() {
        val notTheCommitment = fixtures.boarding.copy(tx = fixtures.forfeit.tx, prevOuts = fixtures.forfeit.prevOuts, digest = null)
        refused(notTheCommitment, "a leaf this output doesn't commit to")
    }

    @Test
    fun `a proof of funds that could be a real spend is refused`() {
        val realInput = fixtures.joinProof.copy(note = fixtures.joinProof.note!!.copy(material = CommonJson.encodeToJsonElement(NotedJoin.serializer(), NotedJoin(fixtures.exitSweep.tx!!))))
        refused(realInput, "that spends something real")
    }

    @Test
    fun `a proof that isn't the shape that burns is refused`() {
        refused(fixtures.joinProof.payingInstead(thief), "a proof that pays something")
        refused(fixtures.joinProof.copy(inputIndex = 0, digest = null, prevOuts = fixtures.joinProof.prevOuts.reversed()), "signed on its challenge input")
    }

    @Test
    fun `without its note a proof is only an exit, which pays nowhere the wallet knows`() {
        refused(fixtures.joinProof.copy(note = null), "somewhere other than the group's onchain wallet")
    }

    @Test
    fun `a transfer to a script nobody authorised is refused, from either of its transactions`() {
        fun stolen(request: ArkRequest) = request.withTransfer { t ->
            val ark = Transaction.read(t.ark.tx)
            val changed = ark.copy(txOut = listOf(TxOut(ark.txOut[0].amount, thief)) + ark.txOut.drop(1))
            t.copy(ark = t.ark.copy(tx = Hex.encode(Transaction.write(changed))))
        }
        refused(stolen(fixtures.oorCheckpoint), "a transfer to a script nobody authorised")
        refused(stolen(fixtures.oorArk), "a transfer to a script nobody authorised")
    }

    @Test
    fun `a transfer of an unknown VTXO, or a misstated one, is refused`() {
        refused(fixtures.oorCheckpoint, "doesn't know of", context(known = emptyMap()))
        refused(fixtures.oorCheckpoint, "misstates its VTXO", context(known = mapOf(spent to 39_000)))
    }

    @Test
    fun `a transaction outside the noted transfer is refused`() {
        refused(fixtures.oorCheckpoint.copy(tx = fixtures.exitSweep.tx, digest = null, prevOuts = fixtures.exitSweep.prevOuts), "outside the transfer")
    }

    /** A spend of the on-chain account's first key, which the daemon's own probe shows the shape of. */
    private fun onchainSpend(pays: ByteVector, value: Long): ArkRequest {
        val own = keys.key(GroupArkDerivation.Slot.ONCHAIN, 0, 0)
        val spent = ByteVector(Script.write(Script.pay2tr(own.xOnly(), Crypto.TaprootTweak.KeyPathTweak)))
        val tx = Transaction(2, listOf(TxIn(OutPoint(TxId("11".repeat(32)), 0), 0xfffffffdL)), listOf(TxOut(Satoshi(value), pays)), 0)
        return ArkRequest(
            kind = ArkRequest.INPUT, family = ArkRequest.FAMILY_ONCHAIN, index = 0, tx = Hex.encode(Transaction.write(tx)),
            prevOuts = listOf(ArkOutput(10_000, Hex.encode(spent.toByteArray()))), method = ArkRequest.METHOD_BIP86,
        )
    }

    @Test
    fun `the on-chain account pays the group's wallet or what was authorised, and nobody else`() {
        assertIs<ArkPolicy.Verdict.Allow>(ArkPolicy.check(context(), onchainSpend(onchain, 9_000)))
        refused(onchainSpend(thief, 9_000), "to a script nobody authorised")
        assertIs<ArkPolicy.Verdict.Allow>(ArkPolicy.check(context(authorised = mapOf(thief to 9_000)), onchainSpend(thief, 9_000)))
        refused(onchainSpend(onchain, 1_000), "in fees")
        refused(onchainSpend(onchain, 9_000).copy(method = ArkRequest.METHOD_SCRIPT_PATH), "only by its key path")
        refused(onchainSpend(onchain, 9_000).copy(index = 1), "doesn't own")
    }

    @Test
    fun `a message under any tag but the known ones, or in any form but Schnorr, is refused`() {
        refused(fixtures.indexerMessage.copy(tag = "TapSighash"), "a tag this device doesn't sign")
        refused(fixtures.indexerMessage.copy(schnorr = false), "plain tagged Schnorr")
        refused(fixtures.indexerMessage.copy(tag = "swap-credit-account-auth-v1"), "a tag this device doesn't sign")
        refused(fixtures.indexerMessage.copy(family = -1), "the on-chain account")
    }

    @Test
    fun `an acknowledgement is only for a payment this group asked for`() {
        val hash = ByteVector32("11".repeat(32))
        val swapKey = keys.key(GroupArkDerivation.Slot.OOR_RECEIVE, 0, 0)
        val message = Hex.encode(swapKey.value.toByteArray() + hash.toByteArray() + ByteArray(8) + ByteArray(34))
        val ack = fixtures.indexerMessage.copy(family = 202, index = 0, tag = "out-swap-htlc-ack-v1", message = message)
        refused(ack, "nobody here asked for")
        val asked = ArkPolicy.Context(keys, operator, fixtures.exitDelay, 2_000, 5_000, { false }, { false }, emptyMap(), issuedInvoices = setOf(hash))
        assertIs<ArkPolicy.Verdict.Allow>(ArkPolicy.check(asked, ack))
    }
}
