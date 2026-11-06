package com.machankura.dedun.compose.wallet.wavelength.group

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.OP_CHECKSIG
import fr.acinq.bitcoin.OP_CHECKSIGVERIFY
import fr.acinq.bitcoin.OP_PUSHDATA
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.ScriptElt
import fr.acinq.bitcoin.ScriptFlags
import fr.acinq.bitcoin.ScriptTree
import fr.acinq.bitcoin.ScriptWitness
import fr.acinq.bitcoin.SigHash
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxId
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import fr.acinq.bitcoin.XonlyPublicKey
import fr.acinq.bitcoin.crypto.iceberg.Iceberg
import fr.acinq.bitcoin.crypto.musig2.IndividualNonce
import fr.acinq.bitcoin.crypto.musig2.Musig2
import fr.acinq.bitcoin.crypto.musig2.Session
import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.crypto.IcebergGroup
import fr.acinq.lightning.crypto.IcebergSigner
import fr.acinq.secp256k1.Hex
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Phase 2 of `docs/group-wavelength-wallet.md`. Every signature goes through bitcoin's own script interpreter, because
 * what matters is that the coin moves. Groups come from the dealer, because only a share's shape matters here.
 */
class GroupArkKeysTest {
    private fun key(hex: String) = PrivateKey(ByteVector32(Hex.decode(hex.padStart(64, '0'))))
    private fun label(tag: String) = ByteVector32(Crypto.sha256(tag.encodeToByteArray()))
    private fun group(n: Int, t: Int) = IcebergSigner.keygen(n, t, ByteVector32(Hex.decode("5a".repeat(32))))
    private val operator = key("0be7a70")
    private val nums = XonlyPublicKey(ByteVector32(Hex.decode("50929b74c1a04954b78b4b6035e97a5e078a5a0f28ec96d547bfee9ace803ac0")))
    private val authSeed = ByteVector32(Hex.decode("a5".repeat(32)))

    private fun <T> subsets(items: List<T>, size: Int): List<List<T>> = when {
        size == 0 -> listOf(emptyList())
        items.size < size -> emptyList()
        else -> subsets(items.drop(1), size - 1).map { listOf(items.first()) + it } + subsets(items.drop(1), size)
    }

    private fun coin(script: List<ScriptElt>) = Transaction(
        2,
        listOf(TxIn(OutPoint(TxId(ByteVector32(Hex.decode("01".repeat(32)))), 0), 0)),
        listOf(TxOut(Satoshi(50_000), Script.write(script))),
        0,
    )

    private fun spendOf(coin: Transaction) = Transaction(
        2,
        listOf(TxIn(OutPoint(coin, 0), 0xfffffffdL)),
        listOf(TxOut(Satoshi(49_000), Script.pay2wpkh(key("de57").publicKey()))),
        0,
    )

    private fun spends(tx: Transaction, coin: Transaction) =
        runCatching { Transaction.correctlySpends(tx, listOf(coin), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS) }.isSuccess

    private val freshLedger get() = run {
        val seen = mutableSetOf<ByteVector32>()
        GroupArkSigner.LabelLedger { label, _ -> seen.add(label) }
    }

    /** The group alone, through [GroupArkSigner], from the contributors and signers named. */
    private suspend fun plain(g: IcebergGroup, contributors: List<Int>, signers: List<Int>, child: GroupArkDerivation.Child, message: ByteVector32, sessionId: ByteVector32) =
        plainUnder(g, contributors, signers, child.cache, message, sessionId)

    private suspend fun plainUnder(g: IcebergGroup, contributors: List<Int>, signers: List<Int>, cache: fr.acinq.bitcoin.crypto.musig2.KeyAggCache, message: ByteVector32, sessionId: ByteVector32) = run {
        val contributions = contributors.map { GroupArkSigner.contribution(g.shares[it - 1], sessionId) }
        val nonce = GroupArkSigner.groupNonce(contributions, g.n, g.t, g.groupPublicKey).right!!
        val shares = signers.map { GroupArkSigner.answer(freshLedger, g.shares[it - 1], sessionId, contributions, g.groupPublicKey, cache, message).right!! }
        GroupArkSigner.plainSignature(shares, g.n, g.t, nonce, cache, message).right!!
    }

    private suspend fun spendCollab(g: IcebergGroup, child: GroupArkDerivation.Child, contributors: List<Int>, signers: List<Int>, sessionId: ByteVector32): Boolean {
        val leaf = ScriptTree.Leaf(listOf(OP_PUSHDATA(child.publicKey.xOnly()), OP_CHECKSIGVERIFY, OP_PUSHDATA(operator.publicKey().xOnly()), OP_CHECKSIG))
        val coin = coin(Script.pay2tr(nums, leaf as ScriptTree))
        val spend = spendOf(coin)
        val message = spend.hashForSigningTaprootScriptPath(0, listOf(coin.txOut[0]), SigHash.SIGHASH_DEFAULT, leaf.hash())
        val ownerSig = plain(g, contributors, signers, child, message, sessionId)
        val operatorSig = spend.signInputTaprootScriptPath(operator, 0, listOf(coin.txOut[0]), SigHash.SIGHASH_DEFAULT, leaf.hash())
        return spends(spend.updateWitness(0, Script.witnessScriptPathPay2tr(nums, leaf, ScriptWitness(listOf(operatorSig, ownerSig)), leaf)), coin)
    }

    @Test
    fun `an owner key signs a VTXO's collaborative leaf from every quorum, at 2-of-4 and 3-of-7`() = runTest {
        for ((n, t) in listOf(4 to 2, 7 to 3)) {
            val g = group(n, t)
            val account = GroupArkDerivation.account(g.groupPublicKey, Chain.Regtest, 0, GroupArkDerivation.Slot.VTXO_OWNER)
            subsets((1..n).toList(), 2 * t - 1).forEachIndexed { i, quorum ->
                assertTrue(spendCollab(g, GroupArkDerivation.child(account, 0, i.toLong()), quorum, quorum.take(t), label("collab $n $t $i")), "$t-of-$n, quorum $quorum")
            }
        }
    }

    @Test
    fun `the on-chain account's key path spends, under BIP86's tweak`() = runTest {
        val g = group(4, 2)
        val child = GroupArkDerivation.child(GroupArkDerivation.account(g.groupPublicKey, Chain.Regtest, 0, GroupArkDerivation.Slot.ONCHAIN), 0, 3)
        val coin = coin(Script.pay2tr(child.publicKey.xOnly(), Crypto.TaprootTweak.KeyPathTweak))
        val spend = spendOf(coin)
        val message = spend.hashForSigningTaprootKeyPath(0, listOf(coin.txOut[0]), SigHash.SIGHASH_DEFAULT)
        val sig = plainUnder(g, listOf(1, 2, 3), listOf(1, 2), GroupArkDerivation.bip86Cache(child), message, label("bip86"))
        assertTrue(spends(spend.updateWitness(0, Script.witnessKeyPathPay2tr(sig)), coin))
    }

    @Test
    fun `slots, networks and wallets are all different keys, and a child is what the xpub derives`() {
        val g = group(4, 2)
        val keys = GroupArkDerivation.Slot.entries.map { GroupArkDerivation.account(g.groupPublicKey, Chain.Mainnet, 0, it).xpub.publicKey }
        assertEquals(keys.size, keys.toSet().size)
        val owner = GroupArkDerivation.account(g.groupPublicKey, Chain.Mainnet, 0, GroupArkDerivation.Slot.VTXO_OWNER)
        assertNotEquals(owner.xpub.publicKey, GroupArkDerivation.account(g.groupPublicKey, Chain.Signet, 0, GroupArkDerivation.Slot.VTXO_OWNER).xpub.publicKey)
        assertNotEquals(owner.xpub.publicKey, GroupArkDerivation.account(g.groupPublicKey, Chain.Mainnet, 1, GroupArkDerivation.Slot.VTXO_OWNER).xpub.publicKey)
        assertEquals(owner.xpub.derivePublicKey(0).derivePublicKey(9).publicKey, GroupArkDerivation.child(owner, 0, 9).publicKey)
    }

    @Test
    fun `a wrong child's cache and another group's shares spend nothing`() = runTest {
        val g = group(4, 2)
        val account = GroupArkDerivation.account(g.groupPublicKey, Chain.Regtest, 0, GroupArkDerivation.Slot.VTXO_OWNER)
        val right = GroupArkDerivation.child(account, 0, 1)
        val wrong = GroupArkDerivation.child(account, 0, 2)
        val leaf = ScriptTree.Leaf(listOf(OP_PUSHDATA(right.publicKey.xOnly()), OP_CHECKSIGVERIFY, OP_PUSHDATA(operator.publicKey().xOnly()), OP_CHECKSIG))
        val coin = coin(Script.pay2tr(nums, leaf as ScriptTree))
        val spend = spendOf(coin)
        val message = spend.hashForSigningTaprootScriptPath(0, listOf(coin.txOut[0]), SigHash.SIGHASH_DEFAULT, leaf.hash())
        val operatorSig = spend.signInputTaprootScriptPath(operator, 0, listOf(coin.txOut[0]), SigHash.SIGHASH_DEFAULT, leaf.hash())
        val withWrong = plain(g, listOf(1, 2, 3), listOf(1, 2), wrong, message, label("wrong child"))
        assertFalse(spends(spend.updateWitness(0, Script.witnessScriptPathPay2tr(nums, leaf, ScriptWitness(listOf(operatorSig, withWrong)), leaf)), coin))

        val other = IcebergSigner.keygen(4, 2, ByteVector32(Hex.decode("6b".repeat(32))))
        val attempt = runCatching { plain(other, listOf(1, 2, 3), listOf(1, 2), right, message, label("other group")) }.getOrNull()
        assertTrue(attempt == null || !spends(spend.updateWitness(0, Script.witnessScriptPathPay2tr(nums, leaf, ScriptWitness(listOf(operatorSig, attempt)), leaf)), coin))
    }

    @Test
    fun `a label is answered once, whatever the message, and the refusal comes before the library`() = runTest {
        val g = group(4, 2)
        val child = GroupArkDerivation.child(GroupArkDerivation.account(g.groupPublicKey, Chain.Regtest, 0, GroupArkDerivation.Slot.VTXO_OWNER), 0, 0)
        val sessionId = label("once")
        val contributions = (1..3).map { GroupArkSigner.contribution(g.shares[it - 1], sessionId) }
        val ledger = freshLedger
        val answer = { message: ByteVector32 -> suspend { GroupArkSigner.answer(ledger, g.shares[0], sessionId, contributions, g.groupPublicKey, child.cache, message) } }
        assertIs<Either.Right<*>>(answer(label("first"))())
        assertIs<GroupArkSigner.LabelAlreadyAnswered>(answer(label("second"))().left)
        assertIs<GroupArkSigner.LabelAlreadyAnswered>(answer(label("first"))().left, "not even the same message: the library can't tell")
    }

    // ---- tree keys ----

    private suspend fun childGroup(parent: IcebergGroup, child: Long): IcebergGroup {
        val shares = parent.shares.map { IcebergChildKeys.share(it, child) }
        val publicShares = parent.shares.map { IcebergChildKeys.publicShare(it, child) }
        val key = IcebergChildKeys.key(publicShares.take(parent.quorum), parent.n, parent.t).right!!
        return IcebergGroup(parent.n, parent.t, key, shares, publicShares, shares.map { Iceberg.shareCache(it) })
    }

    private suspend fun treeSpend(g: IcebergGroup, contributors: List<Int>, signers: List<Int>, sessionId: ByteVector32): Boolean {
        val sweepLeaf = ScriptTree.Leaf(listOf(OP_PUSHDATA(operator.publicKey().xOnly()), OP_CHECKSIG))
        val keys = listOf(g.groupPublicKey, operator.publicKey())
        val (cache, outputKey) = GroupArkSigner.treeCache(keys, sweepLeaf.hash())
        val coin = coin(listOf(fr.acinq.bitcoin.OP_1, OP_PUSHDATA(outputKey)))
        val spend = spendOf(coin)
        val message = spend.hashForSigningTaprootKeyPath(0, listOf(coin.txOut[0]), SigHash.SIGHASH_DEFAULT)

        val contributions = contributors.map { GroupArkSigner.contribution(g.shares[it - 1], sessionId) }
        val groupNonce = GroupArkSigner.groupNonce(contributions, g.n, g.t, g.groupPublicKey).right!!
        val (operatorSecret, operatorNonce) = Musig2.generateNonce(label("operator $sessionId"), Either.Left(operator), fr.acinq.lightning.transactions.Scripts.sort(keys), null, null)
        val cosigner = IndividualNonce.aggregate(listOf(operatorNonce)).right!!
        val shares = signers.map { GroupArkSigner.answer(freshLedger, g.shares[it - 1], sessionId, contributions, g.groupPublicKey, cache, message, cosigner).right!! }
        val groupPartial = GroupArkSigner.treePartialSignature(shares, g.n, g.t).right!!
        val session = Session.create(IndividualNonce.aggregate(listOf(groupNonce, operatorNonce)).right!!, message, cache)
        val sig = session.aggregateSigs(listOf(groupPartial, session.sign(operatorSecret, operator).right!!)).right!!
        return spends(spend.updateWitness(0, Script.witnessKeyPathPay2tr(sig)), coin)
    }

    @Test
    fun `a tree key signs a node beside a stock operator, from any quorum, and a member who sat round one out can too`() = runTest {
        val parent = group(4, 2)
        subsets((1..4).toList(), 3).forEachIndexed { i, contributors ->
            val absent = (1..4).first { it !in contributors }
            assertTrue(treeSpend(childGroup(parent, 100L + i), contributors, contributors.take(2), label("quorum $i")))
            assertTrue(treeSpend(childGroup(parent, 200L + i), contributors, listOf(absent, contributors.first()), label("absent $i")))
        }
        val bigger = childGroup(group(7, 3), 1)
        assertTrue(treeSpend(bigger, listOf(1, 2, 3, 4, 5), listOf(2, 3, 5), label("3-of-7")))
    }

    @Test
    fun `every quorum agrees on a child's key, each child is its own, and members derive the same seeds`() = runTest {
        for ((n, t) in listOf(4 to 2, 7 to 3)) {
            val parent = group(n, t)
            val one = childGroup(parent, 1)
            assertNotEquals(parent.groupPublicKey, one.groupPublicKey)
            assertNotEquals(one.groupPublicKey, childGroup(parent, 2).groupPublicKey)
            subsets(one.publicShares, 2 * t - 1).forEach { assertEquals(one.groupPublicKey, IcebergChildKeys.key(it, n, t).right) }
        }
    }

    @Test
    fun `a parent's shares and a sibling's do not sign for a child`() = runTest {
        val parent = group(4, 2)
        val child = childGroup(parent, 1)
        val sibling = childGroup(parent, 2)
        assertFalse(runCatching { treeSpend(parent.copy(groupPublicKey = child.groupPublicKey), listOf(1, 2, 3), listOf(1, 2), label("parent")) }.getOrDefault(false))
        assertFalse(runCatching { treeSpend(sibling.copy(groupPublicKey = child.groupPublicKey), listOf(1, 2, 3), listOf(1, 2), label("sibling")) }.getOrDefault(false))
    }

    @Test
    fun `the cosigners' order does not change a tree node's key`() {
        val a = key("a1").publicKey()
        val b = key("b2").publicKey()
        val c = operator.publicKey()
        val root = label("sweep")
        assertEquals(GroupArkSigner.treeCache(listOf(a, b, c), root).second, GroupArkSigner.treeCache(listOf(c, a, b), root).second)
    }

    // ---- what the daemon reads ----

    @Test
    fun `BIP380's own vector`() {
        assertEquals("raw(deadbeef)#89f8spxm", BIP380.withChecksum("raw(deadbeef)"))
    }

    @Test
    fun `the authentication seed never prints, and its identity is stable and network-bound`() {
        val auth = GroupArkAuth.of(authSeed, Chain.Regtest)
        assertEquals("<group_ark_auth>", auth.toString())
        assertEquals(auth.identity(), GroupArkAuth.of(authSeed, Chain.Regtest).identity())
        assertNotEquals(auth.identity(), GroupArkAuth.of(authSeed, Chain.Mainnet).identity())
        assertEquals(auth.identity(), auth.privateKey(GroupArkAuth.IDENTITY, 0).publicKey())
        assertTrue(auth.authKeysJson().contains("tprv"))
    }

    /** The same bytes are pinned in the daemon's fork, `walletdesc/group_manifest_test.go`, which parses them and hashes them. */
    @Test
    fun `the manifest is what the daemon writes back, and it is frozen`() {
        val g = group(4, 2)
        val manifest = GroupArkManifest.of(g.groupPublicKey, GroupArkAuth.of(authSeed, Chain.Regtest), Chain.Regtest, 0)
        assertEquals("89e6321901c9e4c1368408d745b2bdd9810d4895acb82938d1b86787cf81abb5", manifest.hash)
        assertTrue(manifest.canonical.contains("/" + "\\u003c0;1\\u003e/*)#kcs89q9g"), "angle brackets escaped, as encoding/json has them")
        assertEquals("ac04f47d", GroupArkAuth.of(authSeed, Chain.Regtest).fingerprint)
        assertTrue(GroupArkAuth.of(authSeed, Chain.Regtest).authKeysJson().contains("[ac04f47d/1017h/1h/21h]tprv8fPKcZrzmdbB8gfvwSzk"))
        val owner = GroupArkDerivation.child(GroupArkDerivation.account(g.groupPublicKey, Chain.Regtest, 0, GroupArkDerivation.Slot.VTXO_OWNER), 0, 5)
        assertEquals("02c7488fe04b5cf02a306d3486a09b76dc90cae2efd97b6c5359cd069e2c844ae6", owner.publicKey.toString())
        val treeKey = GroupArkSigner.treeCache(listOf(g.groupPublicKey, operator.publicKey()), label("sweep")).second
        assertEquals("e2672be8cc169d2843822742abcaf8b6b70a38acfc89769427fcf615319a50c6", Hex.encode(treeKey.value.toByteArray()))
        assertTrue(manifest.canonical.startsWith("{\"families\":{\"202\":"))
    }
}
