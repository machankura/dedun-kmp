package com.machankura.dedun.compose.wallet.wavelength.group

import com.machankura.dedun.compose.network.serialization.CommonJson
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Script
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
        forfeitedIn: Map<String, String> = emptyMap(),
    ) = ArkPolicy.Context(keys, operatorKey, exitDelay, maxRoundFeeSat, maxOnchainFeeSat, isTreeKey, isOnchain, known, authorised, emptySet(), capSat, unspentSat, forfeitedIn)

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

    // ---- the real shapes pass ----

    @Test
    fun `the keys and scripts here are the daemon's`() {
        assertEquals(fixtures.ownerKey, keys.key(GroupArkDerivation.Slot.VTXO_OWNER, 0, 5).toString())
        assertEquals(fixtures.vtxoScript, Hex.encode(ArkScripts.standardScript(keys.key(GroupArkDerivation.Slot.VTXO_OWNER, 0, 5), operator, fixtures.exitDelay).toByteArray()))
    }

    @Test
    fun `every shape the daemon builds is allowed, and the digest computed here is the daemon's`() {
        allowed(fixtures.treeSign, ArkPolicy.What.TREE, session = treeKey)
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
        refused(fixtures.treeSign.withRound { r -> r.copy(vtxos = r.vtxos.map { it.copy(amount = it.amount + 1) }) }, "the tree doesn't pay", session = treeKey)
    }

    @Test
    fun `a tree key the group didn't make is refused`() {
        refused(fixtures.forfeit, "a tree key the group didn't make", context(isTreeKey = { false }))
        refused(fixtures.treeSign, "a tree key the group didn't make", context(isTreeKey = { false }), session = treeKey)
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
            fixtures.treeSign.withRound { r -> r.copy(trees = r.trees.map { t -> t.copy(nodes = t.nodes.map { n -> n.copy(coSigners = n.coSigners.reversed() + "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798") }) }) },
            "isn't its cosigners' aggregate", session = treeKey,
        )
    }

    @Test
    fun `a tree the operator isn't on is refused`() {
        refused(
            fixtures.treeSign.withRound { r -> r.copy(trees = r.trees.map { t -> t.copy(nodes = t.nodes.map { n -> n.copy(coSigners = n.coSigners.filter { it != fixtures.operator }) }) }) },
            "the operator isn't on", session = treeKey,
        )
    }

    @Test
    fun `a tree digest that isn't its transaction's is refused`() {
        refused(
            fixtures.treeSign.withRound { r -> r.copy(trees = r.trees.map { t -> t.copy(nodes = t.nodes.map { n -> n.copy(digest = "cd".repeat(32)) }) }) },
            "isn't its transaction's", session = treeKey,
        )
    }

    @Test
    fun `a tree signature on a digest outside the round, or a session it isn't on, is refused`() {
        refused(fixtures.treeSign.copy(digest = "cd".repeat(32)), "a digest of nothing in this round's trees", session = treeKey)
        val otherTree: (PublicKey) -> Boolean = { true }
        refused(fixtures.treeSign, "a node this key isn't on", context(isTreeKey = otherTree), session = keys.key(GroupArkDerivation.Slot.VTXO_OWNER, 0, 5))
        refused(fixtures.treeSign, "a session this device didn't start")
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
    fun `a VTXO already forfeited into another round is refused`() {
        refused(fixtures.forfeit, "already forfeited into another round", context(forfeitedIn = mapOf(spent to "another round")))
        allowed(fixtures.forfeit, ArkPolicy.What.FORFEIT, context(forfeitedIn = mapOf(spent to fixtures.forfeit.note!!.id)))
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
