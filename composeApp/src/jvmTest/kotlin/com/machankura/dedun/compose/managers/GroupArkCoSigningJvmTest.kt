package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.GroupArkPendingRound
import com.machankura.dedun.compose.database.model.GroupArkVtxo
import com.machankura.dedun.compose.database.model.IcebergKeySession
import com.machankura.dedun.compose.database.model.IcebergLabelAssignment
import com.machankura.dedun.compose.network.serialization.CommonJson
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthEvents
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthOperatorStatementEvent
import com.machankura.dedun.compose.wallet.wavelength.group.ArkNote
import com.machankura.dedun.compose.wallet.wavelength.group.ArkOutput
import com.machankura.dedun.compose.wallet.wavelength.group.ArkRequest
import com.machankura.dedun.compose.wallet.wavelength.group.ArkResponse
import com.machankura.dedun.compose.wallet.wavelength.group.ArkWire
import com.machankura.dedun.compose.wallet.wavelength.group.GroupArkAuth
import com.machankura.dedun.compose.wallet.wavelength.group.GroupArkDerivation
import com.machankura.dedun.compose.wallet.wavelength.group.GroupArkKeys
import com.machankura.dedun.compose.wallet.wavelength.group.GroupArkSigner
import com.machankura.dedun.compose.wallet.wavelength.group.ArkScripts
import com.machankura.dedun.compose.wallet.wavelength.group.NotedNode
import com.machankura.dedun.compose.wallet.wavelength.group.NotedSig
import com.machankura.dedun.compose.wallet.wavelength.group.NotedTransfer
import com.machankura.dedun.compose.wallet.wavelength.group.NotedTx
import com.machankura.dedun.compose.wallet.wavelength.group.NotedRound
import com.machankura.dedun.compose.wallet.wavelength.group.NotedTree
import com.machankura.dedun.compose.wallet.wavelength.group.NotedVtxo
import androidx.room3.immediateTransaction
import androidx.room3.useWriterConnection
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.utils.RandomInstance
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.ByteVector64
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.OP_1
import fr.acinq.bitcoin.OP_PUSHDATA
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.SigHash
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxId
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import fr.acinq.bitcoin.crypto.musig2.IndividualNonce
import fr.acinq.bitcoin.crypto.musig2.Musig2
import fr.acinq.bitcoin.crypto.musig2.Session
import fr.acinq.bitcoin.utils.Either
import fr.acinq.secp256k1.Hex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Phase 6 of `docs/group-wavelength-wallet.md`: four devices, one operator, and signatures that have to satisfy bitcoin's own
 * interpreter, through the same pipe a daemon's requests take.
 */
class GroupArkCoSigningJvmTest {
    private var fixture = fixture()

    private fun fixture(onDisk: Boolean = false) = GroupLightningFixture(onDisk = onDisk).also { f ->
        val inherited = f.dispatch
        f.dispatch = { to, event ->
            when (event.kind) {
                in GroupWavelengthEvents.CO_SIGNING -> GroupArkCoSigner.process(to.db, to.room, event, to.publicKey)
                else -> inherited(to, event)
            }
        }
    }
    private val arkOperator = PrivateKey(ByteVector32("0be7a700".padEnd(64, '0')))
    private val allKinds = GroupWavelengthEvents.CO_SIGNING

    @AfterTest
    fun close() = fixture.close()

    private inner class Group(val all: List<GroupLightningFixture.Device>, val wallet: GroupWavelengthWalletManager.Wallet, val transport: GroupArkTransport, val keys: GroupArkKeys, val key: String) {
        val operator get() = all[0]
    }

    private suspend fun group(): Group {
        val all = fixture.group()
        fixture.runCeremony(all, purpose = IcebergKeySession.PURPOSE_WAVELENGTH)
        val session = GroupWavelengthWalletManager.proposeWallet(all[0].db, all[0].room, all[0].publicKey, all[0].privateKey, fixture.chain, arkOperator.publicKey().toString())
        fixture.signSession(all, all[0], session.id)
        val wallet = assertNotNull(GroupWavelengthWalletManager.current(all[0].db, fixture.roomId, fixture.chain))
        val nominated = GroupWavelengthOperatorManager.nominateSelf(all[0].db, all[0].room, all[0].publicKey, fixture.chain)
        fixture.signSession(all, all[0], nominated.id)
        val term = assertNotNull(GroupWavelengthOperatorManager.current(all[3].db, fixture.roomId, fixture.chain)).also { assertEquals(all[0].publicKey.lowercase(), it.publicKey) }.term

        val seed = assertNotNull(all[0].db.groupWavelengthSeedDao().get(fixture.roomId, wallet.statement.identity))
        val auth = GroupArkAuth.of(ByteVector32(seed.seed), fixture.chain)
        val transport = GroupArkTransport(all[0].db, all[0].room, all[0].publicKey, wallet, term, auth, timeout = 3.seconds)
        transport.provision(plainTo = 12, treeTo = 4)
        pump(all)
        val keys = GroupArkKeys(PublicKey.fromHex(wallet.statement.icebergKey), fixture.chain, 0)
        return Group(all, wallet, transport, keys, GroupArkCoSigner.walletKey(fixture.roomId, fixture.chain, 0))
    }

    private suspend fun pump(all: List<GroupLightningFixture.Device>) = fixture.pump(all, allKinds)

    /** Asks the transport as the daemon would, moving the room's messages until it answers. */
    private suspend fun answered(group: Group, request: ArkRequest, on: List<GroupLightningFixture.Device> = group.all): Result<ArkResponse> = kotlinx.coroutines.coroutineScope {
        val answer = async(Dispatchers.Default) { runCatching { group.transport.answer(request) } }
        while (!answer.isCompleted) {
            pump(on)
            delay(50)
        }
        answer.await()
    }

    private fun coin(script: ByteVector, value: Long) = Transaction(2, listOf(TxIn(OutPoint(TxId(ByteVector32(RandomInstance.bytes(32))), 0), 0)), listOf(TxOut(Satoshi(value), script)), 0)

    /** A sweep of a VTXO the group owns, by its exit leaf, to the group's onchain wallet or wherever [to] says. */
    private suspend fun sweep(group: Group, index: Long, to: ByteVector? = null): Triple<ArkRequest, Transaction, ByteVector> {
        val owner = group.keys.key(GroupArkDerivation.Slot.VTXO_OWNER, 0, index)
        val delay = group.wallet.statement.policy.exitDelay
        val leaf = ArkScripts.timeoutLeaf(owner, delay)
        val script = ArkScripts.standardScript(owner, arkOperator.publicKey(), delay)
        val funding = coin(script, 40_000)
        val destination = to ?: GroupWalletDerivation.derive(fixture.account, GroupWalletDerivation.RECEIVE, index).pubkeyScript
        val tx = Transaction(2, listOf(TxIn(OutPoint(funding, 0), delay)), listOf(TxOut(Satoshi(39_000), destination)), 0)
        val request = ArkRequest(
            kind = ArkRequest.INPUT, family = 44, index = index, tx = Hex.encode(Transaction.write(tx)),
            prevOuts = listOf(ArkOutput(40_000, Hex.encode(script.toByteArray()))), method = ArkRequest.METHOD_SCRIPT_PATH,
            leafScript = Hex.encode(leaf.toByteArray()), leafVersion = Script.TAPROOT_LEAF_TAPSCRIPT,
        )
        return Triple(request, funding, leaf)
    }

    @Test
    fun `the group signs a VTXO's exit to its own wallet, and bitcoin accepts the signature`() = runBlocking<Unit> {
        val group = group()
        val (request, funding, leaf) = sweep(group, 3)
        val response = answered(group, request).getOrThrow()

        val tx = Transaction.read(request.tx!!)
        val message = tx.hashForSigningTaprootScriptPath(0, listOf(funding.txOut[0]), SigHash.SIGHASH_DEFAULT, ArkScripts.leafHash(leaf))
        val owner = group.keys.key(GroupArkDerivation.Slot.VTXO_OWNER, 0, 3)
        assertTrue(Crypto.verifySignatureSchnorr(message, ByteVector64(response.signature!!), owner.xOnly()), "a BIP-340 signature under the owner key")
    }

    @Test
    fun `a request that moves money elsewhere gets no signature from anybody, and each device says so`() = runBlocking<Unit> {
        val group = group()
        val (request, _, _) = sweep(group, 3, to = ByteVector(Hex.decode("5120" + "ee".repeat(32))))
        val answer = answered(group, request)
        assertTrue(answer.isFailure, "the operator gets nothing")
        group.all.forEach {
            assertEquals(1, fixture.lines(it, ChatMessage.TYPE_WAVELENGTH_REFUSED).size, "device ${it.index} says it refused")
        }
    }

    @Test
    fun `an old term's operator gets no answer`() = runBlocking<Unit> {
        val group = group()
        val nominated = GroupWavelengthOperatorManager.nominateSelf(group.all[1].db, group.all[1].room, group.all[1].publicKey, fixture.chain)
        fixture.signSession(group.all, group.all[1], nominated.id)
        val (request, _, _) = sweep(group, 3)
        assertTrue(answered(group, request).isFailure)
        val label = Hex.encode(GroupArkCoSigner.label(group.wallet.statement, ArkWire.Domain(), 0).toByteArray())
        group.all.drop(1).forEach { assertEquals(null, it.db.icebergCoSigningDao().ledgerFor(group.key, label), "device ${it.index} never considered it") }
    }

    /** A batch from the operator, sent to [targets] alone, as a hostile coordinator would. */
    private suspend fun sendTo(group: Group, targets: List<GroupLightningFixture.Device>, batch: ArkWire.SigningBatch) {
        val content = CommonJson.encodeToString(ArkWire.SigningBatch.serializer(), batch)
        val queued = GroupLightningWalletManager.queue(group.operator.db, group.operator.room, group.operator.publicKey, GroupWavelengthEvents.SIGNING_REQUEST, content)
        val event = Event(queued.id, queued.publicKey, queued.createdAt.epochSeconds, queued.kind, queued.tags, queued.content, "")
        targets.forEach { d ->
            d.received.add(queued.id)
            d.db.marmotInnerEventDao().upsert(queued.copy(chatRoomId = fixture.roomId))
            GroupArkCoSigner.process(d.db, d.room, event, d.publicKey)
        }
    }

    /** A batch that reached [target] while it was restoring, so it is in the room and hasn't been looked at. */
    private suspend fun fileOnly(group: Group, target: GroupLightningFixture.Device, batch: ArkWire.SigningBatch) {
        val content = CommonJson.encodeToString(ArkWire.SigningBatch.serializer(), batch)
        val queued = GroupLightningWalletManager.queue(group.operator.db, group.operator.room, group.operator.publicKey, GroupWavelengthEvents.SIGNING_REQUEST, content)
        target.received.add(queued.id)
        target.db.marmotInnerEventDao().upsert(queued.copy(chatRoomId = fixture.roomId))
    }

    private suspend fun floorsKnown(group: Group) {
        // Everybody learns how far the group has answered before the hostile part.
        val (request, _, _) = sweep(group, 1)
        answered(group, request).getOrThrow()
    }

    @Test
    fun `an operator that shows two members two messages under one label gets no share from anybody`() = runBlocking<Unit> {
        val group = group()
        floorsKnown(group)
        val counter = 1L
        val contributions = group.all[0].db.icebergCoSigningDao().noncesFor(group.key, counter).sortedBy { it.contribution }.map { it.contribution }
        val first = sweep(group, 4).first
        val second = sweep(group, 5).first
        val term = assertNotNull(GroupWavelengthOperatorManager.current(group.all[0].db, fixture.roomId, fixture.chain)).term
        val label = GroupArkCoSigner.label(group.wallet.statement, ArkWire.Domain(), counter).let { Hex.encode(it.toByteArray()) }

        sendTo(group, group.all.take(2), ArkWire.SigningBatch(group.key, term, listOf(ArkWire.SigningItem("a", ArkWire.Domain(), counter, first, contributions))))
        sendTo(group, group.all.drop(2), ArkWire.SigningBatch(group.key, term, listOf(ArkWire.SigningItem("b", ArkWire.Domain(), counter, second, contributions))))
        pump(group.all)

        val dao = { d: GroupLightningFixture.Device -> d.db.icebergCoSigningDao() }
        group.all.forEach { d ->
            assertEquals(0, dao(d).sharesFor(group.key, "a").size + dao(d).sharesFor(group.key, "b").size, "device ${d.index} holds no share")
            assertTrue(dao(d).ledgerFor(group.key, label)?.signedAt == null, "device ${d.index} did not sign")
            assertTrue(fixture.lines(d, ChatMessage.TYPE_WAVELENGTH_EQUIVOCATION).isNotEmpty(), "device ${d.index} raises the alarm")
        }
    }

    @Test
    fun `a device answers one label for one message only, even asked twice`() = runBlocking<Unit> {
        val group = group()
        floorsKnown(group)
        val counter = 2L
        val contributions = group.all[0].db.icebergCoSigningDao().noncesFor(group.key, counter).sortedBy { it.contribution }.map { it.contribution }
        val term = assertNotNull(GroupWavelengthOperatorManager.current(group.all[0].db, fixture.roomId, fixture.chain)).term
        val first = sweep(group, 4).first
        sendTo(group, listOf(group.all[1]), ArkWire.SigningBatch(group.key, term, listOf(ArkWire.SigningItem("a", ArkWire.Domain(), counter, first, contributions))))
        sendTo(group, listOf(group.all[1]), ArkWire.SigningBatch(group.key, term, listOf(ArkWire.SigningItem("b", ArkWire.Domain(), counter, sweep(group, 5).first, contributions))))
        val echoes = group.all[1].db.marmotInnerEventDao().getByChatRoomAndKinds(fixture.roomId, listOf(GroupWavelengthEvents.ECHO)).filter { it.publicKey == group.all[1].publicKey }
        assertEquals(1, echoes.count { it.content.contains("\"a\"") || it.content.contains("\"b\"") }, "one echo, for the first")
    }

    @Test
    fun `a device that hasn't heard how far the group has answered signs nothing, and then a restored one can't answer an old label`() = runBlocking<Unit> {
        val group = group()
        // Its first batch ever, as after a restore: no ledger, no floor, and the share kept.
        val restored = group.all[3]
        val noFloor = sweep(group, 2).first
        val term = assertNotNull(GroupWavelengthOperatorManager.current(group.all[0].db, fixture.roomId, fixture.chain)).term
        val contributions = group.all[0].db.icebergCoSigningDao().noncesFor(group.key, 0).sortedBy { it.contribution }.map { it.contribution }
        val label = GroupArkCoSigner.label(group.wallet.statement, ArkWire.Domain(), 0).let { Hex.encode(it.toByteArray()) }

        // While it doesn't know, it answers nothing at all.
        sendTo(group, listOf(restored), ArkWire.SigningBatch(group.key, term, listOf(ArkWire.SigningItem("old", ArkWire.Domain(), 0, noFloor, contributions))))
        assertEquals(0, restored.db.icebergCoSigningDao().sharesFor(group.key, "old").size)

        // Told by t others how far they have answered, its floor is above the label it was asked about.
        repeat(2) { i ->
            val other = group.all[i]
            val message = ArkWire.HighWater(group.key, asking = false, highest = 0)
            val queued = GroupLightningWalletManager.queue(other.db, other.room, other.publicKey, GroupWavelengthEvents.HIGH_WATER, CommonJson.encodeToString(ArkWire.HighWater.serializer(), message))
            GroupArkCoSigner.process(restored.db, restored.room, Event(queued.id, queued.publicKey, queued.createdAt.epochSeconds, queued.kind, queued.tags, queued.content, ""), restored.publicKey)
        }
        assertEquals(1L, restored.db.groupArkDao().floor(group.key)?.floor)
        assertEquals(null, restored.db.icebergCoSigningDao().ledgerFor(group.key, label), "nothing answered under an old label after its restore")
        assertEquals(0, restored.db.icebergCoSigningDao().sharesFor(group.key, "old").size)
    }

    /** A one-leaf round: a commitment paying a tree node that pays the group's new VTXO, funded by a boarding output of the group's. */
    private suspend fun round(group: Group, treeKey: PublicKey, owner: PublicKey, boarding: PublicKey): Pair<NotedRound, ByteVector32> {
        val delay = group.wallet.statement.policy.exitDelay
        val op = arkOperator.publicKey()
        val sweepKey = PrivateKey(ByteVector32("51".repeat(32))).publicKey()
        val sweepRoot = ArkScripts.leafHash(ArkScripts.timeoutLeaf(sweepKey, 1000))
        val cosigners = listOf(treeKey, op)
        val (_, outputKey) = GroupArkSigner.treeCache(cosigners, sweepRoot)
        val batchScript = ByteVector(Script.write(listOf(OP_1, OP_PUSHDATA(outputKey))))
        val vtxoScript = ArkScripts.standardScript(owner, op, delay)

        val boardingScript = ArkScripts.standardScript(boarding, op, group.wallet.statement.policy.boardingDelay)
        val prevOuts = listOf(ArkOutput(50_000, Hex.encode(boardingScript.toByteArray())))
        val commitment = Transaction(3, listOf(TxIn(OutPoint(TxId(ByteVector32("b0".repeat(32))), 0), 0xffffffffL)), listOf(TxOut(Satoshi(50_000), batchScript)), 0)
        val leaf = Transaction(3, listOf(TxIn(OutPoint(commitment, 0), 0xffffffffL)), listOf(TxOut(Satoshi(50_000), vtxoScript), TxOut(Satoshi(0), ByteVector(Script.write(Script.pay2anchor)))), 0)
        val digest = leaf.hashForSigningTaprootKeyPath(0, commitment.txOut, SigHash.SIGHASH_DEFAULT)

        return NotedRound(
            commitmentTx = Hex.encode(Transaction.write(commitment)),
            prevOuts = prevOuts,
            sweepKey = sweepKey.toString(),
            sweepDelay = 1000,
            trees = listOf(
                NotedTree(
                    outputIndex = 0,
                    batchOutput = ArkOutput(50_000, Hex.encode(batchScript.toByteArray())),
                    sweepRoot = Hex.encode(sweepRoot.toByteArray()),
                    nodes = listOf(NotedNode(Hex.encode(Transaction.write(leaf)), Hex.encode(digest.toByteArray()), cosigners.map { it.toString() })),
                ),
            ),
            vtxos = listOf(NotedVtxo(treeKey.toString(), owner.toString(), 50_000, Hex.encode(vtxoScript.toByteArray()))),
        ) to digest
    }

    @Test
    fun `a tree key the group made signs a node beside a stock operator, the group remembers the VTXO, and bitcoin accepts it`() = runBlocking<Unit> {
        val group = group()
        val allocated = group.transport.answer(ArkRequest(kind = ArkRequest.NEXT_TREE_KEY))
        val treeKey = PublicKey.fromHex(allocated.pubKey!!)
        val owner = group.keys.key(GroupArkDerivation.Slot.VTXO_OWNER, 0, 0)
        val boarding = group.keys.key(GroupArkDerivation.Slot.BOARDING, 0, 0)
        val (noted, digest) = round(group, treeKey, owner, boarding)
        val tree = noted.trees.single()
        val note = ArkNote(ArkNote.ROUND, "round", CommonJson.encodeToJsonElement(NotedRound.serializer(), noted))

        val created = group.transport.answer(
            ArkRequest(kind = ArkRequest.MUSIG2_CREATE, family = 45, index = allocated.index, signers = tree.nodes.single().coSigners, scriptRoot = tree.sweepRoot),
        )
        val groupNonce = IndividualNonce(created.publicNonce!!)

        // The stock operator, as the Ark operator would take part.
        val cosigners = tree.nodes.single().coSigners.map { PublicKey.fromHex(it) }
        val sorted = fr.acinq.lightning.transactions.Scripts.sort(cosigners)
        val (operatorSecret, operatorNonce) = Musig2.generateNonce(ByteVector32("77".repeat(32)), Either.Left(arkOperator), sorted, null, null)
        val combined = IndividualNonce.aggregate(listOf(groupNonce, operatorNonce)).right!!
        group.transport.answer(ArkRequest(kind = ArkRequest.MUSIG2_NONCE, sessionId = created.sessionId, combinedNonce = Hex.encode(combined.toByteArray())))

        val response = answered(group, ArkRequest(kind = ArkRequest.MUSIG2_SIGN, sessionId = created.sessionId, digest = Hex.encode(digest.toByteArray()), note = note)).getOrThrow()

        val (cache, outputKey) = GroupArkSigner.treeCache(cosigners, ByteVector32(tree.sweepRoot))
        val session = Session.create(combined, digest, cache)
        val signature = session.aggregateSigs(listOf(ByteVector32(response.signature!!), session.sign(operatorSecret, arkOperator).right!!)).right!!
        assertTrue(Crypto.verifySignatureSchnorr(digest, signature, outputKey), "the tree node's key path is spent")

        val commitment = Transaction.read(noted.commitmentTx).txid.toString()
        group.all.forEach { d ->
            val known = d.db.groupArkDao().vtxos(group.key)
            assertEquals(listOf(50_000L), known.map { it.amountSat }, "device ${d.index} remembers the VTXO it signed for")
            assertEquals(emptyMap<String, Long>(), contextOn(d, group).knownVtxos, "device ${d.index} won't spend it until it has seen it settle")
            assertEquals(mapOf(commitment to 50_000L), contextOn(d, group).pendingFromSat)
            GroupArkCoSigner.settled(d.db, group.key, commitment)
            assertEquals(listOf(50_000L), contextOn(d, group).knownVtxos.values.toList(), "device ${d.index} spends it once it has")
            assertEquals(50_000L, contextOn(d, group).unspentSat)
        }
    }

    /** A transfer of a coin the group holds to another of its own keys: the Ark transaction's request, and the checkpoint's, co-signed by the operator or not. */
    private inner class Transfer(group: Group, index: Long = 3) {
        private val operator = arkOperator.publicKey()
        private val delay = group.wallet.statement.policy.exitDelay
        private val owner = group.keys.key(GroupArkDerivation.Slot.VTXO_OWNER, 0, index)
        private val leaf = ArkScripts.collabLeaf(owner, operator)
        private val standard = ArkScripts.standardScript(owner, operator, delay)
        private val anchor = ByteVector(Script.write(Script.pay2anchor))
        private val funding = coin(standard, 40_000)
        private val checkpointScript = ArkScripts.checkpointScript(operator, delay, leaf)
        private val checkpoint = Transaction(3, listOf(TxIn(OutPoint(funding, 0), 0xffffffffL)), listOf(TxOut(Satoshi(40_000), checkpointScript), TxOut(Satoshi(0), anchor)), 0)
        private val ark = Transaction(
            3, listOf(TxIn(OutPoint(checkpoint, 0), 0xffffffffL)),
            listOf(TxOut(Satoshi(40_000), ArkScripts.standardScript(group.keys.key(GroupArkDerivation.Slot.VTXO_OWNER, 0, index + 1), operator, delay)), TxOut(Satoshi(0), anchor)), 0,
        )
        private val family = 44
        val spent = "${funding.txid}:0"
        val made = "${ark.txid}:0"
        val arkId = ark.txid.toString()
        private val index = index

        private fun note(cosigned: Boolean): ArkNote {
            val sigs = if (cosigned) {
                val digest = ark.hashForSigningTaprootScriptPath(0, listOf(TxOut(Satoshi(40_000), checkpointScript)), SigHash.SIGHASH_DEFAULT, ArkScripts.leafHash(leaf))
                listOf(NotedSig(0, Hex.encode(Crypto.signSchnorr(digest, arkOperator, null).toByteArray())))
            } else {
                emptyList()
            }
            val transfer = NotedTransfer(
                NotedTx(Hex.encode(Transaction.write(ark)), listOf(ArkOutput(40_000, Hex.encode(checkpointScript.toByteArray()))), sigs),
                listOf(NotedTx(Hex.encode(Transaction.write(checkpoint)), listOf(ArkOutput(40_000, Hex.encode(standard.toByteArray()))))),
            )
            return ArkNote(ArkNote.OOR, arkId, CommonJson.encodeToJsonElement(NotedTransfer.serializer(), transfer))
        }

        private fun request(tx: Transaction, prevOut: ByteVector, cosigned: Boolean) = ArkRequest(
            kind = ArkRequest.INPUT, family = family, index = index, tx = Hex.encode(Transaction.write(tx)),
            prevOuts = listOf(ArkOutput(40_000, Hex.encode(prevOut.toByteArray()))), method = ArkRequest.METHOD_SCRIPT_PATH,
            leafScript = Hex.encode(leaf.toByteArray()), leafVersion = Script.TAPROOT_LEAF_TAPSCRIPT, note = note(cosigned),
        )

        val arkRequest get() = request(ark, checkpointScript, cosigned = false)
        val checkpointRequest get() = request(checkpoint, standard, cosigned = true)
        val uncosignedCheckpoint get() = request(checkpoint, standard, cosigned = false)
    }

    private suspend fun holds(d: GroupLightningFixture.Device, group: Group, outpoint: String, settled: Boolean = true) =
        d.db.groupArkDao().upsertVtxo(GroupArkVtxo(group.key, outpoint, 40_000, origin = if (settled) null else "ff".repeat(32), settled = settled))

    private suspend fun vtxoOn(d: GroupLightningFixture.Device, group: Group, outpoint: String) =
        d.db.groupArkDao().vtxos(group.key).firstOrNull { it.outpoint == outpoint }

    @Test
    fun `a transfer settles on every device when the checkpoint's signature is complete, and not before`() = runBlocking<Unit> {
        val group = group()
        val transfer = Transfer(group)
        group.all.forEach { holds(it, group, transfer.spent) }

        answered(group, transfer.arkRequest).getOrThrow()
        pump(group.all)
        group.all.forEach { d ->
            assertEquals(false, vtxoOn(d, group, transfer.made)?.settled, "device ${d.index} has signed the Ark transaction, which hands nothing over")
            assertEquals(false, vtxoOn(d, group, transfer.spent)?.spent)
        }

        assertTrue(answered(group, transfer.uncosignedCheckpoint).isFailure, "no checkpoint goes without the operator's co-signature")
        pump(group.all)
        group.all.forEach { assertEquals(false, vtxoOn(it, group, transfer.made)?.settled) }

        answered(group, transfer.checkpointRequest).getOrThrow()
        repeat(3) { pump(group.all) }
        group.all.forEach { d ->
            assertEquals(true, vtxoOn(d, group, transfer.made)?.settled, "device ${d.index} can spend what it made")
            assertEquals(true, vtxoOn(d, group, transfer.spent)?.spent, "and what it replaced is gone")
            assertEquals(mapOf(transfer.made to 40_000L), contextOn(d, group).knownVtxos)
            assertEquals(emptyList(), d.db.groupArkDao().checkpoints(group.key), "and nothing is waited on")
        }
    }

    @Test
    fun `shares that don't verify settle nothing, so no member can talk a device into it`() = runBlocking<Unit> {
        val group = group()
        floorsKnown(group)
        val transfer = Transfer(group)
        group.all.forEach { holds(it, group, transfer.spent) }
        answered(group, transfer.arkRequest).getOrThrow()
        pump(group.all)

        // The checkpoint reaches the last device alone, so it has signed nothing and nobody has answered.
        val last = group.all.last()
        val counter = 200L
        val contributions = contributionsAt(group, 8)
        val item = ArkWire.SigningItem("forged", ArkWire.Domain(), 8, transfer.checkpointRequest, contributions)
        sendTo(group, listOf(last), ArkWire.SigningBatch(group.key, currentTerm(group), items = listOf(item)))
        val filed = last.db.groupArkDao().checkpoints(group.key).single()
        assertEquals(labelAt(group, 8), filed.label, "it checked the checkpoint and filed where it signs")

        group.all.dropLast(1).forEach { from ->
            val garbage = ArkWire.ShareBatch(group.key, listOf(ArkWire.ShareItem("forged", filed.label!!, "11".repeat(32))))
            val event = Event("${counter + from.index}".padStart(64, '0'), from.publicKey, 0, GroupWavelengthEvents.SIGNATURE_SHARES, emptyArray(), CommonJson.encodeToString(ArkWire.ShareBatch.serializer(), garbage), "")
            GroupArkCoSigner.process(last.db, last.room, event, last.publicKey)
        }
        assertEquals(false, vtxoOn(last, group, transfer.made)?.settled)
        assertEquals(false, vtxoOn(last, group, transfer.spent)?.spent)
    }

    @Test
    fun `a device a block behind refuses a transfer, then catches up on it when it sees the block`() = runBlocking<Unit> {
        val group = group()
        floorsKnown(group)
        val transfer = Transfer(group)
        val (behind, others) = group.all.last() to group.all.dropLast(1)
        others.forEach { holds(it, group, transfer.spent) }
        holds(behind, group, transfer.spent, settled = false)

        answered(group, transfer.arkRequest).getOrThrow()
        answered(group, transfer.checkpointRequest).getOrThrow()
        repeat(3) { pump(group.all) }
        others.forEach { assertEquals(true, vtxoOn(it, group, transfer.made)?.settled) }
        assertEquals(null, vtxoOn(behind, group, transfer.made), "it refused, so it knows nothing of what the spend made")
        assertTrue(fixture.lines(behind, ChatMessage.TYPE_WAVELENGTH_REFUSED).any { "doesn't know of" in it.content })

        GroupArkCoSigner.roundSettled(behind.db, GroupArkPendingRound(group.key, "ff".repeat(32), fixture.roomId, "regtest", ""))
        repeat(3) { pump(group.all) }
        assertEquals(true, vtxoOn(behind, group, transfer.spent)?.spent, "having seen the round, it agrees to the spend and sees it done")
        assertEquals(true, vtxoOn(behind, group, transfer.made)?.settled)
    }

    @Test
    fun `catching up says nothing about what it still refuses, having said so once`() = runBlocking<Unit> {
        val group = group()
        floorsKnown(group)
        val theft = sweep(group, 5, to = ByteVector("5120" + "99".repeat(32))).first
        assertTrue(answered(group, theft).isFailure)
        pump(group.all)
        val device = group.all.last()
        val before = fixture.lines(device, ChatMessage.TYPE_WAVELENGTH_REFUSED).size
        assertTrue(before > 0)
        GroupArkCoSigner.roundSettled(device.db, GroupArkPendingRound(group.key, "ff".repeat(32), fixture.roomId, "regtest", ""))
        assertEquals(before, fixture.lines(device, ChatMessage.TYPE_WAVELENGTH_REFUSED).size)
        assertEquals(null, device.db.icebergCoSigningDao().ledgerFor(group.key, labelAt(group, device.db.groupArkDao().highestPlainChecked(group.key)!! + 1)), "and it still refuses")
    }

    private suspend fun contextOn(d: GroupLightningFixture.Device, group: Group) = GroupArkCoSigner.contextOf(d.db, fixture.roomId, group.wallet, group.key, currentTerm(group))

    private suspend fun currentTerm(group: Group) = assertNotNull(GroupWavelengthOperatorManager.current(group.all[0].db, fixture.roomId, fixture.chain)).term

    private suspend fun contributionsAt(group: Group, counter: Long) =
        group.all[0].db.icebergCoSigningDao().noncesFor(group.key, counter).sortedBy { it.contribution }.map { it.contribution }

    private fun labelAt(group: Group, counter: Long, domain: ArkWire.Domain = ArkWire.Domain()) =
        Hex.encode(GroupArkCoSigner.label(group.wallet.statement, domain, counter).toByteArray())

    @Test
    fun `the same message under a label with other nonces is a second challenge, and gets nothing`() = runBlocking<Unit> {
        val group = group()
        floorsKnown(group)
        val counter = 1L
        val request = sweep(group, 4).first
        val contributions = contributionsAt(group, counter)
        val term = currentTerm(group)
        val device = group.all[1]
        sendTo(group, listOf(device), ArkWire.SigningBatch(group.key, term, listOf(ArkWire.SigningItem("a", ArkWire.Domain(), counter, request, contributions))))
        val checked = assertNotNull(device.db.groupArkDao().checked(group.key, labelAt(group, counter)))
        val refusedBefore = fixture.lines(device, ChatMessage.TYPE_WAVELENGTH_REFUSED).size

        sendTo(group, listOf(device), ArkWire.SigningBatch(group.key, term, listOf(ArkWire.SigningItem("b", ArkWire.Domain(), counter, request, contributions.reversed()))))
        sendTo(group, listOf(device), ArkWire.SigningBatch(group.key, term, listOf(ArkWire.SigningItem("c", ArkWire.Domain(), counter, request, contributions, cosignerNonce = "ab".repeat(66)))))

        assertEquals(refusedBefore + 2, fixture.lines(device, ChatMessage.TYPE_WAVELENGTH_REFUSED).size, "the device refuses each")
        assertTrue(fixture.lines(device, ChatMessage.TYPE_WAVELENGTH_EQUIVOCATION).isNotEmpty(), "and raises the alarm")
        assertEquals(checked.item, device.db.groupArkDao().checked(group.key, labelAt(group, counter))?.item, "what it would sign is still what it checked first")
    }

    @Test
    fun `a device counts how far it has checked, in labels and in tree keys apart`() = runBlocking<Unit> {
        val group = group()
        floorsKnown(group)
        val dao = group.all[1].db.groupArkDao()
        assertEquals(0L, dao.highestPlainChecked(group.key))
        assertEquals(null, dao.highestChildChecked(group.key))
    }

    @Test
    fun `a restored device answers no tree key below what the group has reached, and no old term's request once it knows`() = runBlocking<Unit> {
        val group = group()
        floorsKnown(group)
        val restored = group.all[3]
        restored.db.groupArkDao().upsertFloor(com.machankura.dedun.compose.database.model.GroupArkFloor(group.key, null))
        val term = currentTerm(group)

        // From the old term, at a label above any floor: filed in the room, and nothing it should ever answer.
        val old = sweep(group, 6).first
        val stale = ArkWire.SigningBatch(group.key, term - 1, listOf(ArkWire.SigningItem("stale", ArkWire.Domain(), 9, old, contributionsAt(group, 9))))
        fileOnly(group, restored, stale)
        // And for a tree key it won't be asked about again, this term.
        val treeItem = ArkWire.SigningItem("old tree", ArkWire.Domain(1), 0, sweep(group, 7).first, emptyList())
        fileOnly(group, restored, ArkWire.SigningBatch(group.key, term, listOf(treeItem)))

        repeat(2) { i ->
            val other = group.all[i]
            val message = ArkWire.HighWater(group.key, asking = false, highest = 0, highestChild = 1)
            val queued = GroupLightningWalletManager.queue(other.db, other.room, other.publicKey, GroupWavelengthEvents.HIGH_WATER, CommonJson.encodeToString(ArkWire.HighWater.serializer(), message))
            GroupArkCoSigner.process(restored.db, restored.room, Event(queued.id, queued.publicKey, queued.createdAt.epochSeconds, queued.kind, queued.tags, queued.content, ""), restored.publicKey)
        }

        val floor = assertNotNull(restored.db.groupArkDao().floor(group.key))
        assertEquals(1L, floor.floor)
        assertEquals(2L, floor.treeFloor)
        assertEquals(null, restored.db.groupArkDao().checked(group.key, labelAt(group, 9)), "an old term's request is nothing now")
        assertEquals(null, restored.db.groupArkDao().checked(group.key, labelAt(group, 0, ArkWire.Domain(1))), "nor is a tree key below the floor")
        val said = fixture.lines(restored, ChatMessage.TYPE_WAVELENGTH_REFUSED)
        assertTrue(said.any { it.content.contains("tree key from before") }, "and it says so")
    }

    @Test
    fun `one member disagreeing raises the alarm and stops nothing, since n of at least 3t-2 can't have two quorums`() = runBlocking<Unit> {
        val group = group()
        floorsKnown(group)
        val liar = group.all[3]
        val label = labelAt(group, 1)
        val garbage = ArkWire.EchoBatch(group.key, listOf(ArkWire.EchoItem("x", label, "ee".repeat(32))))
        val queued = GroupLightningWalletManager.queue(liar.db, liar.room, liar.publicKey, GroupWavelengthEvents.ECHO, CommonJson.encodeToString(ArkWire.EchoBatch.serializer(), garbage))
        group.all.take(3).forEach { GroupArkCoSigner.process(it.db, it.room, Event(queued.id, queued.publicKey, queued.createdAt.epochSeconds, queued.kind, queued.tags, queued.content, ""), it.publicKey) }

        val (request, funding, leaf) = sweep(group, 3)
        val response = answered(group, request).getOrThrow()
        val message = Transaction.read(request.tx!!).hashForSigningTaprootScriptPath(0, listOf(funding.txOut[0]), SigHash.SIGHASH_DEFAULT, ArkScripts.leafHash(leaf))
        assertTrue(Crypto.verifySignatureSchnorr(message, ByteVector64(response.signature!!), group.keys.key(GroupArkDerivation.Slot.VTXO_OWNER, 0, 3).xOnly()))
        group.all.take(3).forEach { assertTrue(fixture.lines(it, ChatMessage.TYPE_WAVELENGTH_EQUIVOCATION).isNotEmpty(), "device ${it.index} says so") }
    }

    @Test
    fun `a member's bad share is left out, and the rest still make the signature`() = runBlocking<Unit> {
        val group = group()
        floorsKnown(group)
        val cheat = group.all[1]
        val inherited = fixture.dispatch
        fixture.dispatch = { to, event ->
            val tampered = if (event.kind == GroupWavelengthEvents.SIGNATURE_SHARES && event.pubKey == cheat.publicKey) {
                val batch = CommonJson.decodeFromString(ArkWire.ShareBatch.serializer(), event.content)
                val spoiled = batch.copy(items = batch.items.map { it.copy(share = it.share.dropLast(2) + if (it.share.endsWith("00")) "01" else "00") })
                Event(event.id, event.pubKey, event.createdAt, event.kind, event.tags, CommonJson.encodeToString(ArkWire.ShareBatch.serializer(), spoiled), event.sig)
            } else {
                event
            }
            inherited(to, tampered)
        }
        val (request, funding, leaf) = sweep(group, 3)
        val response = answered(group, request).getOrThrow()
        val message = Transaction.read(request.tx!!).hashForSigningTaprootScriptPath(0, listOf(funding.txOut[0]), SigHash.SIGHASH_DEFAULT, ArkScripts.leafHash(leaf))
        assertTrue(Crypto.verifySignatureSchnorr(message, ByteVector64(response.signature!!), group.keys.key(GroupArkDerivation.Slot.VTXO_OWNER, 0, 3).xOnly()))
    }

    @Test
    fun `nonces nobody published get silence, and too few of them, or one twice, are refused`() = runBlocking<Unit> {
        val group = group()
        floorsKnown(group)
        val device = group.all[1]
        val term = currentTerm(group)
        val contributions = contributionsAt(group, 1)
        val forged = contributions.drop(1) + contributions.first().reversed()
        sendTo(group, listOf(device), ArkWire.SigningBatch(group.key, term, listOf(ArkWire.SigningItem("forged", ArkWire.Domain(), 1, sweep(group, 4).first, forged))))
        sendTo(group, listOf(device), ArkWire.SigningBatch(group.key, term, listOf(ArkWire.SigningItem("few", ArkWire.Domain(), 2, sweep(group, 4).first, contributionsAt(group, 2).take(2)))))
        sendTo(group, listOf(device), ArkWire.SigningBatch(group.key, term, listOf(ArkWire.SigningItem("twice", ArkWire.Domain(), 3, sweep(group, 4).first, contributionsAt(group, 3).let { it + it.first() }))))
        // Nonces nobody published look like ones still on their way, so that's a wait and nothing's said. It still gets no answer, below.
        assertEquals(2, fixture.lines(device, ChatMessage.TYPE_WAVELENGTH_REFUSED).count { it.content.contains("too little of round one") })
        (1L..3L).forEach { assertEquals(null, device.db.groupArkDao().checked(group.key, labelAt(group, it))) }
    }

    @Test
    fun `round one that never reached two members is asked for and said again, and the group still signs`() = runBlocking<Unit> {
        // As MLS loses a message that arrives behind one of the receiver's own (docs/mls-skipped-keys.md), which is what happened to
        // the first mainnet wallet: two of four never had the operator's round one, so only two could echo, and a signature needs three.
        val operatorKey = fixture.members[0]
        val inherited = fixture.dispatch
        val lost = mutableSetOf<Int>()
        fixture.dispatch = { to, event ->
            if (event.kind == GroupWavelengthEvents.PROVISION && event.pubKey == operatorKey && to.index in setOf(1, 2) && lost.add(to.index)) Unit
            else inherited(to, event)
        }
        val group = group()
        group.all.subList(1, 3).forEach { d ->
            assertTrue(d.db.icebergCoSigningDao().noncesFor(group.key, 0).none { it.participant == operatorKey }, "device ${d.index} never had the operator's round one")
            assertTrue(d.db.groupArkDao().treeShares(group.key).none { it.participant == operatorKey }, "nor its tree keys")
        }
        // As the runner does before its first signature, or a device learning the floor mid-request would find label 0 behind it.
        group.transport.askFloors()
        pump(group.all)

        val (request, funding, leaf) = sweep(group, 3)
        val response = answered(group, request).getOrThrow()
        val message = Transaction.read(request.tx!!).hashForSigningTaprootScriptPath(0, listOf(funding.txOut[0]), SigHash.SIGHASH_DEFAULT, ArkScripts.leafHash(leaf))
        assertTrue(Crypto.verifySignatureSchnorr(message, ByteVector64(response.signature!!), group.keys.key(GroupArkDerivation.Slot.VTXO_OWNER, 0, 3).xOnly()))

        val operator = group.operator.db
        group.all.subList(1, 3).forEach { d ->
            assertEquals(operator.icebergCoSigningDao().noncesOf(group.key, operatorKey, 0, 12), d.db.icebergCoSigningDao().noncesOf(group.key, operatorKey, 0, 12), "device ${d.index} has every label's, as first published")
            assertEquals(operator.groupArkDao().treeShares(group.key).filter { it.participant == operatorKey }.toSet(), d.db.groupArkDao().treeShares(group.key).filter { it.participant == operatorKey }.toSet(), "and every tree key's")
        }
    }

    @Test
    fun `a message filed inside the inbound transaction and the device's own work beside it never wait on each other`() = runBlocking<Unit> {
        // What stopped every write on the first mainnet operator once signatures flowed both ways: the inbound path holds the writer
        // and then wants the co-signer, and the operator's own requests took the co-signer and then wanted the writer. Only on disk:
        // in memory, reads share the writer's one connection, so the work beside it never got as far as the co-signer.
        fixture.close()
        fixture = fixture(onDisk = true)
        val group = group()
        val device = group.operator
        val asker = group.all[1]
        fun askFor(wanted: ArkWire.Wanted): Event {
            val content = CommonJson.encodeToString(ArkWire.Provision.serializer(), ArkWire.Provision(group.key, 0, emptyList(), emptyList(), listOf(wanted)))
            return Event(RandomInstance.bytes(32).toHexKey(), asker.publicKey, 0, GroupWavelengthEvents.PROVISION, emptyArray(), content, "")
        }
        val inTransaction = CompletableDeferred<Unit>()
        // Its own scope, and only waited on: a deadlock is two coroutines that won't be cancelled, and joining them would hang the suite.
        val scope = CoroutineScope(Dispatchers.Default)
        val inbound = scope.launch {
            device.db.useWriterConnection { it.immediateTransaction {
                inTransaction.complete(Unit)
                delay(500) // long enough for the work beside it to get as far as it can
                GroupArkCoSigner.process(device.db, device.room, askFor(ArkWire.Wanted(device.publicKey, childFrom = 0)), device.publicKey)
            } }
        }
        inTransaction.await()
        val beside = scope.launch {
            GroupArkCoSigner.process(device.db, device.room, askFor(ArkWire.Wanted(device.publicKey, plainFrom = 0)), device.publicKey)
        }
        val finished = withTimeoutOrNull(10.seconds) { inbound.join(); beside.join() }
        assertNotNull(finished, "the inbound transaction and the work beside it waited on each other")
        val said = device.db.marmotInnerEventDao().getByChatRoomAndKinds(fixture.roomId, listOf(GroupWavelengthEvents.PROVISION))
            .filter { it.publicKey == device.publicKey && it.content.contains("\"answers\"") }
        assertEquals(2, said.size, "both asks were answered")
    }

    @Test
    fun `a member lying about how far the group has got can skip it ahead a little, and not out of reach`() {
        assertEquals(6L, GroupArkCoSigner.floorFrom(listOf(5, 1L shl 40), 2, 4_096))
        assertEquals(6L, GroupArkCoSigner.floorFrom(listOf(5, 5, 1L shl 40), 2, 4_096))
        assertEquals(8L, GroupArkCoSigner.floorFrom(listOf(5, 7), 2, 4_096))
        assertEquals(4_001L, GroupArkCoSigner.floorFrom(listOf(0, 4_000), 2, 4_096))
        assertEquals(0L, GroupArkCoSigner.floorFrom(listOf(-1, -1), 2, 4_096))
    }

    @Test
    fun `a mark nobody asked for, or one past any real group, is thrown away`() = runBlocking<Unit> {
        val group = group()
        floorsKnown(group)
        val device = group.all[3]
        val before = device.db.groupArkDao().floor(group.key)
        suspend fun markFrom(from: GroupLightningFixture.Device, highest: Long, child: Long) {
            val message = ArkWire.HighWater(group.key, asking = false, highest = highest, highestChild = child)
            val queued = GroupLightningWalletManager.queue(from.db, from.room, from.publicKey, GroupWavelengthEvents.HIGH_WATER, CommonJson.encodeToString(ArkWire.HighWater.serializer(), message))
            GroupArkCoSigner.process(device.db, device.room, Event(queued.id, queued.publicKey, queued.createdAt.epochSeconds, queued.kind, queued.tags, queued.content, ""), device.publicKey)
        }
        // This device knows how far it may answer from, so nobody has anything to tell it.
        markFrom(group.all[0], 1L shl 30, 1L shl 30)
        assertEquals(before, device.db.groupArkDao().floor(group.key))
        assertFalse(device.db.groupArkDao().marks(group.key).any { it.highest == 1L shl 30 })

        // Asking again, it won't take a number that would wrap the floor round to nothing.
        device.db.groupArkDao().upsertFloor(com.machankura.dedun.compose.database.model.GroupArkFloor(group.key, null))
        markFrom(group.all[0], Long.MAX_VALUE, 0)
        markFrom(group.all[1], -5, 0)
        assertFalse(device.db.groupArkDao().marks(group.key).any { it.highest == Long.MAX_VALUE || it.highest == -5L })
    }

    @Test
    fun `labels are a wallet's own, on any key`() = runBlocking<Unit> {
        val group = group()
        val statement = group.wallet.statement
        val label = GroupArkCoSigner.label(statement, ArkWire.Domain(), 0)
        assertTrue(label != GroupArkCoSigner.label(statement.copy(wallet = 1), ArkWire.Domain(), 0))
        assertTrue(label != GroupArkCoSigner.label(statement.copy(chain = fr.acinq.bitcoin.Chain.Testnet3), ArkWire.Domain(), 0))
        assertTrue(label != GroupArkCoSigner.label(statement, ArkWire.Domain(0), 0))
    }

    @Test
    fun `a tree key's label can't sign a plain request, nor a plain label a tree one`() = runBlocking<Unit> {
        val group = group()
        floorsKnown(group)
        val device = group.all[1]
        val domain = ArkWire.Domain(0)
        val item = ArkWire.SigningItem("crossed", domain, 0, sweep(group, 4).first, emptyList())
        sendTo(group, listOf(device), ArkWire.SigningBatch(group.key, currentTerm(group), listOf(item)))
        assertEquals(null, device.db.groupArkDao().checked(group.key, labelAt(group, 0, domain)))
        assertTrue(fixture.lines(device, ChatMessage.TYPE_WAVELENGTH_REFUSED).any { it.content.contains("another kind of signature") })
    }

    @Test
    fun `the identity answers at once, with no quorum, and nobody is asked`() = runBlocking<Unit> {
        val group = group()
        val before = group.operator.db.marmotInnerEventDao().getByChatRoomAndKinds(fixture.roomId, listOf(GroupWavelengthEvents.SIGNING_REQUEST)).size
        val response = group.transport.answer(ArkRequest(kind = ArkRequest.MESSAGE, family = 6, index = 0, message = "abcd", tag = "mailbox-auth", schnorr = true))
        val digest = Crypto.taggedHash(Hex.decode("abcd"), "mailbox-auth")
        assertTrue(Crypto.verifySignatureSchnorr(digest, ByteVector64(response.signature!!), PublicKey.fromHex(group.wallet.statement.identity).xOnly()))
        assertEquals(before, group.operator.db.marmotInnerEventDao().getByChatRoomAndKinds(fixture.roomId, listOf(GroupWavelengthEvents.SIGNING_REQUEST)).size)
    }

    @Test
    fun `a proof of funds' first input is the round-join key's alone, and it signs nothing else`() = runBlocking<Unit> {
        val group = group()
        val seed = assertNotNull(group.operator.db.groupWavelengthSeedDao().get(fixture.roomId, group.wallet.statement.identity))
        val key = GroupArkAuth.of(ByteVector32(seed.seed), fixture.chain).privateKey(GroupArkAuth.ROUND_JOIN_AUTH, 0)
        val challenge = ByteVector(Script.write(Script.pay2tr(key.xOnlyPublicKey(), Crypto.TaprootTweak.KeyPathTweak)))
        val tx = Transaction(2, listOf(TxIn(OutPoint(TxId("aa".repeat(32)), 0), 0), TxIn(OutPoint(TxId("bb".repeat(32)), 0), 0)), listOf(TxOut(Satoshi(0), ByteVector(byteArrayOf(0x6a)))), 0)
        val prevOuts = listOf(TxOut(Satoshi(0), challenge), TxOut(Satoshi(60_000), ByteVector(Hex.decode("5120" + "cc".repeat(32)))))
        fun ask(outs: List<TxOut> = prevOuts, method: String = ArkRequest.METHOD_BIP86, input: Int = 0) = ArkRequest(
            kind = ArkRequest.INPUT, family = GroupArkAuth.ROUND_JOIN_AUTH.toInt(), tx = Hex.encode(Transaction.write(tx)), inputIndex = input, method = method,
            prevOuts = outs.map { ArkOutput(it.amount.sat, Hex.encode(it.publicKeyScript.toByteArray())) },
        )
        val before = group.operator.db.marmotInnerEventDao().getByChatRoomAndKinds(fixture.roomId, listOf(GroupWavelengthEvents.SIGNING_REQUEST)).size

        val response = group.transport.answer(ask())
        val digest = tx.hashForSigningTaprootKeyPath(0, prevOuts, SigHash.SIGHASH_DEFAULT)
        val output = key.publicKey().xOnly().outputKey(Crypto.TaprootTweak.KeyPathTweak).first
        assertTrue(Crypto.verifySignatureSchnorr(digest, ByteVector64(response.signature!!), output), "under the key the challenge pays")
        assertEquals(before, group.operator.db.marmotInnerEventDao().getByChatRoomAndKinds(fixture.roomId, listOf(GroupWavelengthEvents.SIGNING_REQUEST)).size, "and nobody is asked")

        assertTrue(runCatching { group.transport.answer(ask(outs = listOf(prevOuts[1], prevOuts[0]))) }.isFailure, "a coin isn't its challenge")
        assertTrue(runCatching { group.transport.answer(ask(outs = listOf(TxOut(Satoshi(1), challenge), prevOuts[1]))) }.isFailure, "and a challenge holds nothing")
        assertTrue(runCatching { group.transport.answer(ask(input = 1)) }.isFailure, "the other inputs are the group's")
        assertTrue(runCatching { group.transport.answer(ask(method = ArkRequest.METHOD_SCRIPT_PATH)) }.isFailure, "by its key path only")
    }

    @Test
    fun `an operator statement reads back what it says, and a stranger's isn't believed`() {
        val statement = GroupWavelengthOperatorStatementEvent.Statement(fixture.chain, 2, "ab".repeat(32), 7)
        assertEquals(statement, GroupWavelengthOperatorStatementEvent.read(GroupWavelengthOperatorStatementEvent.template(statement).tags))
        val nobody = statement.copy(operator = null)
        assertEquals(nobody, GroupWavelengthOperatorStatementEvent.read(GroupWavelengthOperatorStatementEvent.template(nobody).tags))
        assertFalse(GroupWavelengthEvents.CO_SIGNING.contains(GroupWavelengthOperatorStatementEvent.KIND))
    }

    @Test
    fun `a request that gets here before what it's built from waits for it, and is answered, with nothing said`() = runBlocking<Unit> {
        val group = group()
        val late = group.all[3]
        val others = group.all.dropLast(1)
        // New labels, which the last device hasn't heard about, signed by the other three.
        group.transport.provision(plainTo = 40, treeTo = 4)
        pump(others)
        group.operator.db.icebergCoSigningDao().insertAssignment(IcebergLabelAssignment(group.key, 20, "", "skip"))
        assertTrue(answered(group, sweep(group, 3).first, on = others).isSuccess)
        val label = GroupArkCoSigner.label(group.wallet.statement, ArkWire.Domain(), 21).let { Hex.encode(it.toByteArray()) }

        // The request reaches it first, as a relay is free to do.
        fixture.pump(group.all, allKinds - GroupWavelengthEvents.PROVISION)
        assertEquals(null, late.db.icebergCoSigningDao().ledgerFor(group.key, label), "it can't answer yet")
        assertEquals(emptyList(), fixture.lines(late, ChatMessage.TYPE_WAVELENGTH_REFUSED), "and it isn't a refusal")

        pump(group.all)
        assertNotNull(late.db.icebergCoSigningDao().ledgerFor(group.key, label), "answered once round one arrived")
        assertEquals(emptyList(), fixture.lines(late, ChatMessage.TYPE_WAVELENGTH_REFUSED))
    }
}
