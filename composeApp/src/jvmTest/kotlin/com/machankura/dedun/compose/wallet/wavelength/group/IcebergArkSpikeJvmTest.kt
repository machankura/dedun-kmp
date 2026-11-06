package com.machankura.dedun.compose.wallet.wavelength.group

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.ByteVector64
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.KeyPath
import fr.acinq.bitcoin.OP_CHECKSIG
import fr.acinq.bitcoin.OP_CHECKSIGVERIFY
import fr.acinq.bitcoin.OP_PUSHDATA
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey
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
import fr.acinq.bitcoin.crypto.iceberg.Share
import fr.acinq.bitcoin.crypto.musig2.AggregatedNonce
import fr.acinq.bitcoin.crypto.musig2.IndividualNonce
import fr.acinq.bitcoin.crypto.musig2.KeyAggCache
import fr.acinq.bitcoin.crypto.musig2.Musig2
import fr.acinq.bitcoin.crypto.musig2.Session
import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.crypto.IcebergGroup
import fr.acinq.lightning.crypto.IcebergSigner
import fr.acinq.secp256k1.Hex
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Phase 0, spikes 2 and 3 of `docs/group-wavelength-wallet.md`.
 *
 * Spike 2 asks whether an Iceberg group can make a plain BIP-340 signature, with no MuSig2 cosigner
 * beside it, under a BIP32 child of its key: what a VTXO's owner key, a boarding key and the `tr`
 * account all need. Spike 3 asks whether a share hashed from another share's seeds is a working key of
 * its own in a tree's MuSig2, where a tweak cannot reach: what a VTXO's tree key needs.
 *
 * Bitcoin's own script interpreter decides each one, with negative controls, because a check that cannot
 * fail proves nothing. Groups come from the trusted dealer because only the shares' shape matters here.
 */
class IcebergArkSpikeJvmTest {
    private fun key(hex: String) = PrivateKey(ByteVector32(Hex.decode(hex.padStart(64, '0'))))
    private fun label(tag: String) = Crypto.sha256(tag.encodeToByteArray()).let(::ByteVector32)
    private fun group(n: Int, t: Int) = IcebergSigner.keygen(n, t, ByteVector32(Hex.decode("5a".repeat(32))))

    /** No cosigner: 33 zero bytes twice is how MuSig2's extended encoding writes infinity. */
    private val noCosigner = AggregatedNonce(ByteArray(66))
    private val operator = key("0be7a70")

    private fun outputTx(script: List<ScriptElt>) = Transaction(
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

    /** A signature from [group] alone under [cache], which aggregates `[group's key]` and any tweaks after it. */
    private fun signAlone(group: IcebergGroup, cache: KeyAggCache, message: ByteVector32, sessionId: ByteVector32): ByteVector64 {
        val roundOne = IcebergSigner.roundOne(group, (1..group.quorum).toList(), sessionId)
        val aggregate = IndividualNonce.aggregate(listOf(roundOne.publicNonce)).right!!
        val partial = IcebergSigner.roundTwo(group, (1..group.t).toList(), sessionId, roundOne, cache, message, noCosigner).right!!.partialSig
        return Session.create(aggregate, message, cache).aggregateSigs(listOf(partial)).right!!
    }

    /** The BIP32 step a daemon holding only `[xpub]/0/index` makes, as a tweak on [cache]. */
    private fun childCache(cache: KeyAggCache, chainCode: ByteVector32, path: List<Long>): Pair<KeyAggCache, PublicKey> {
        val root = cache.tweak(ByteVector32.Zeroes, isXonly = false).right!!.second
        var extended = DeterministicWallet.ExtendedPublicKey(root.value, chainCode, 0, KeyPath.empty, 0L)
        var current = cache
        var currentKey = root
        for (index in path) {
            val child = extended.derivePublicKey(index)
            val tweak = tweakBetween(currentKey, child.publicKey, extended, index)
            val (nextCache, nextKey) = current.tweak(tweak, isXonly = false).right!!
            assertEquals(child.publicKey, nextKey, "the cache's child is the one an xpub derives")
            current = nextCache
            currentKey = nextKey
            extended = child
        }
        return current to currentKey
    }

    private fun tweakBetween(parent: PublicKey, child: PublicKey, extended: DeterministicWallet.ExtendedPublicKey, index: Long): ByteVector32 {
        val data = extended.publickeybytes.toByteArray() + byteArrayOf((index ushr 24).toByte(), (index ushr 16).toByte(), (index ushr 8).toByte(), index.toByte())
        val tweak = ByteVector32(Crypto.hmac512(extended.chaincode.toByteArray(), data).take(32).toByteArray())
        assertEquals(child, parent + PrivateKey(tweak).publicKey(), "the tweak is the BIP32 step")
        return tweak
    }

    /** A VTXO's collaborative leaf as `StandardVTXOTemplate` compiles it: owner, then operator. */
    private fun collabLeaf(owner: XonlyPublicKey) = ScriptTree.Leaf(
        listOf(OP_PUSHDATA(owner), OP_CHECKSIGVERIFY, OP_PUSHDATA(operator.publicKey().xOnly()), OP_CHECKSIG)
    )

    private val nums = XonlyPublicKey(ByteVector32(Hex.decode("50929b74c1a04954b78b4b6035e97a5e078a5a0f28ec96d547bfee9ace803ac0")))

    private fun spendCollab(group: IcebergGroup, cache: KeyAggCache, owner: XonlyPublicKey, sessionId: ByteVector32): Boolean {
        val leaf = collabLeaf(owner)
        val coin = outputTx(Script.pay2tr(nums, leaf as ScriptTree))
        val spend = spendOf(coin)
        val leafHash = leaf.hash()
        val message = Transaction.hashForSigningTaprootScriptPath(spend, 0, listOf(coin.txOut[0]), SigHash.SIGHASH_DEFAULT, leafHash)
        val ownerSig = signAlone(group, cache, message, sessionId)
        val operatorSig = spend.signInputTaprootScriptPath(operator, 0, listOf(coin.txOut[0]), SigHash.SIGHASH_DEFAULT, leafHash)
        val witness = Script.witnessScriptPathPay2tr(nums, leaf, ScriptWitness(listOf(operatorSig, ownerSig)), leaf)
        return spends(spend.updateWitness(0, witness), coin)
    }

    @Test
    fun `spike 2 - a group signs a VTXO's collaborative leaf alone, under a BIP32 child, and bitcoin accepts it`() {
        val g = group(4, 2)
        val chainCode = label("chain code")
        val (_, root) = KeyAggCache.create(listOf(g.groupPublicKey))
        val (cache, child) = childCache(root, chainCode, listOf(0L, 7L))
        assertTrue(Iceberg.keyAggregationCheck(root, listOf(g.groupPublicKey), g.groupPublicKey))
        assertTrue(spendCollab(g, cache, child.xOnly(), label("collab")))
    }

    @Test
    fun `spike 2 - a BIP86 key path, as the tr account spends`() {
        val g = group(4, 2)
        val (_, root) = KeyAggCache.create(listOf(g.groupPublicKey))
        val (childCacheNoTr, child) = childCache(root, label("chain code"), listOf(0L, 3L))
        val tweak = child.xOnly().tweak(Crypto.TaprootTweak.KeyPathTweak)
        val (cache, _) = childCacheNoTr.tweak(tweak, isXonly = true).right!!
        val coin = outputTx(Script.pay2tr(child.xOnly(), Crypto.TaprootTweak.KeyPathTweak))
        val spend = spendOf(coin)
        val message = Transaction.hashForSigningTaprootKeyPath(spend, 0, listOf(coin.txOut[0]), SigHash.SIGHASH_DEFAULT)
        val sig = signAlone(g, cache, message, label("bip86"))
        assertTrue(spends(spend.updateWitness(0, Script.witnessKeyPathPay2tr(sig)), coin))
    }

    @Test
    fun `spike 2 control - the wrong child's cache spends nothing`() {
        val g = group(4, 2)
        val (_, root) = KeyAggCache.create(listOf(g.groupPublicKey))
        val (_, child) = childCache(root, label("chain code"), listOf(0L, 7L))
        val (wrongCache, _) = childCache(root, label("chain code"), listOf(0L, 8L))
        assertFalse(spendCollab(g, wrongCache, child.xOnly(), label("wrong child")))
    }

    @Test
    fun `spike 2 control - another group's shares spend nothing`() {
        val g = group(4, 2)
        val other = IcebergSigner.keygen(4, 2, ByteVector32(Hex.decode("6b".repeat(32))))
        val (_, root) = KeyAggCache.create(listOf(g.groupPublicKey))
        val (cache, child) = childCache(root, label("chain code"), listOf(0L, 7L))
        // The other group's shares under this cache: the coefficient is looked up by its own key, which the cache lacks.
        val attempt = runCatching { spendCollab(other, cache, child.xOnly(), label("other group")) }
        assertFalse(attempt.getOrDefault(false))
    }

    @Test
    fun `spike 2 fallback - a public cosigner works as well, with no change to the library`() {
        val g = group(4, 2)
        val public = key("d0d0")
        val keys = listOf(g.groupPublicKey, public.publicKey())
        val (_, cache) = KeyAggCache.create(keys)
        val owner = cache.tweak(ByteVector32.Zeroes, isXonly = false).right!!.second.xOnly()
        val leaf = collabLeaf(owner)
        val coin = outputTx(Script.pay2tr(nums, leaf as ScriptTree))
        val spend = spendOf(coin)
        val message = Transaction.hashForSigningTaprootScriptPath(spend, 0, listOf(coin.txOut[0]), SigHash.SIGHASH_DEFAULT, leaf.hash())

        val sessionId = label("public cosigner")
        val roundOne = IcebergSigner.roundOne(g, (1..g.quorum).toList(), sessionId)
        val (publicSecret, publicNonce) = Musig2.generateNonce(label("public nonce"), Either.Left(public), keys, null, null)
        val aggregate = IndividualNonce.aggregate(listOf(roundOne.publicNonce, publicNonce)).right!!
        val session = Session.create(aggregate, message, cache)
        val partial = IcebergSigner.roundTwo(g, (1..g.t).toList(), sessionId, roundOne, cache, message, IcebergSigner.cosignerAggregatedNonce(listOf(publicNonce)).right!!).right!!.partialSig
        val ownerSig = session.aggregateSigs(listOf(partial, session.sign(publicSecret, public).right!!)).right!!
        val operatorSig = spend.signInputTaprootScriptPath(operator, 0, listOf(coin.txOut[0]), SigHash.SIGHASH_DEFAULT, leaf.hash())
        val witness = Script.witnessScriptPathPay2tr(nums, leaf, ScriptWitness(listOf(operatorSig, ownerSig)), leaf)
        assertTrue(spends(spend.updateWitness(0, witness), coin))
    }

    /** D5: every seed a member holds goes through a keyed hash, and the header stays. */
    private fun childShare(share: Share, child: Int): Share {
        val bytes = share.toByteArray()
        val seeds = bytes.drop(4).chunked(32).flatMap { seed ->
            Crypto.taggedHash(seed.toByteArray() + byteArrayOf((child ushr 24).toByte(), (child ushr 16).toByte(), (child ushr 8).toByte(), child.toByte()), "dedun/group-wavelength/tree-seed/v1").toByteArray().asList()
        }
        return Share(bytes.take(4).toByteArray() + seeds)
    }

    private fun childGroup(parent: IcebergGroup, child: Int): IcebergGroup {
        val shares = parent.shares.map { childShare(it, child) }
        val caches = shares.map { Iceberg.shareCache(it) }
        val publicShares = shares.zip(caches).map { (s, c) -> Iceberg.publicShare(s, c) }
        val key = Iceberg.groupPublicKey(publicShares.take(parent.quorum), parent.n, parent.t).right!!
        return IcebergGroup(parent.n, parent.t, key, shares, publicShares, caches)
    }

    private fun <T> subsets(items: List<T>, size: Int): List<List<T>> = when {
        size == 0 -> listOf(emptyList())
        items.size < size -> emptyList()
        else -> subsets(items.drop(1), size - 1).map { listOf(items.first()) + it } + subsets(items.drop(1), size)
    }

    /** A tree node's key path, as `Node.NewSignerSession` makes one: the keys, then the sweep root as a taproot tweak. */
    private fun treeSpend(
        g: IcebergGroup,
        contributors: List<Int>,
        signers: List<Int>,
        groupKeyInTree: PublicKey = g.groupPublicKey,
        sessionId: ByteVector32,
    ): Boolean {
        val keys = listOf(groupKeyInTree, operator.publicKey())
        val sweepLeaf = ScriptTree.Leaf(listOf(OP_PUSHDATA(operator.publicKey().xOnly()), OP_CHECKSIG))
        val internal = Musig2.aggregateKeys(keys)
        val coin = outputTx(Script.pay2tr(internal, sweepLeaf as ScriptTree))
        val spend = spendOf(coin)
        val message = Transaction.hashForSigningTaprootKeyPath(spend, 0, listOf(coin.txOut[0]), SigHash.SIGHASH_DEFAULT)
        val cache = IcebergSigner.keyAggCacheFor(keys, internal.tweak(Crypto.TaprootTweak.ScriptPathTweak(sweepLeaf.hash()))).right!!

        val roundOne = IcebergSigner.roundOne(g, contributors, sessionId)
        val (operatorSecret, operatorNonce) = Musig2.generateNonce(label("operator nonce"), Either.Left(operator), keys, null, null)
        val shares = signers.map { k ->
            Iceberg.partialSign(g.shares[k - 1], g.caches[k - 1], sessionId, roundOne.contributions, g.groupPublicKey, cache, message, IcebergSigner.cosignerAggregatedNonce(listOf(operatorNonce)).right!!).right!!
        }
        val groupPartial = Iceberg.aggregatePartialSignatures(shares, g.n, g.t).right!!
        val session = Session.create(IndividualNonce.aggregate(listOf(roundOne.publicNonce, operatorNonce)).right!!, message, cache)
        val sig = session.aggregateSigs(listOf(groupPartial, session.sign(operatorSecret, operator).right!!)).right!!
        return spends(spend.updateWitness(0, Script.witnessKeyPathPay2tr(sig)), coin)
    }

    @Test
    fun `spike 3 - a child's public shares lie on one polynomial, from every quorum, at 2-of-4 and 3-of-7`() {
        for ((n, t) in listOf(4 to 2, 7 to 3)) {
            val parent = group(n, t)
            val child = childGroup(parent, 1)
            assertNotEquals(parent.groupPublicKey, child.groupPublicKey)
            assertNotEquals(child.groupPublicKey, childGroup(parent, 2).groupPublicKey, "each child is a key of its own")
            subsets(child.publicShares, parent.quorum).forEach { quorum ->
                assertEquals(child.groupPublicKey, Iceberg.groupPublicKey(quorum, n, t).right, "every quorum agrees on the child's key")
            }
        }
    }

    @Test
    fun `spike 3 - a child key signs a tree node beside a stock operator, and bitcoin accepts it`() {
        for ((n, t) in listOf(4 to 2, 7 to 3)) {
            val child = childGroup(group(n, t), 5)
            val quorum = (1..child.quorum).toList()
            assertTrue(treeSpend(child, quorum, quorum.take(t), sessionId = label("tree $n $t")), "$t-of-$n")
        }
    }

    @Test
    fun `spike 3 - any quorum can sign, and a member who sat round one out still can`() {
        val parent = group(4, 2)
        subsets((1..4).toList(), 3).forEachIndexed { i, contributors ->
            val absent = (1..4).first { it !in contributors }
            val child = childGroup(parent, 100 + i)
            assertTrue(treeSpend(child, contributors, contributors.take(2), sessionId = label("quorum $i")))
            assertTrue(treeSpend(childGroup(parent, 200 + i), contributors, listOf(absent, contributors.first()), sessionId = label("absent $i")), "member $absent signs after sitting out round one")
        }
    }

    @Test
    fun `spike 3 control - a parent's shares do not sign for its child, nor a child's for its sibling`() {
        val parent = group(4, 2)
        val child = childGroup(parent, 1)
        val sibling = childGroup(parent, 2)
        val parentInChildsSlot = parent.copy(groupPublicKey = child.groupPublicKey)
        val siblingInChildsSlot = sibling.copy(groupPublicKey = child.groupPublicKey)
        assertFalse(runCatching { treeSpend(parentInChildsSlot, listOf(1, 2, 3), listOf(1, 2), sessionId = label("parent")) }.getOrDefault(false))
        assertFalse(runCatching { treeSpend(siblingInChildsSlot, listOf(1, 2, 3), listOf(1, 2), sessionId = label("sibling")) }.getOrDefault(false))
    }

    @Test
    fun `spike 3 - a member holds no seed of a subset it belongs to, so t-1 of them miss one`() {
        // The secrecy argument is the DKG's: a coalition of t-1 is itself one of the subsets, and that subset's seed
        // is held by everybody else. Hashing a seed leaves that as it was: nothing in a coalition can compute the child
        // of a seed none of it holds. Here, the count that argument rests on.
        for ((n, t) in listOf(4 to 2, 7 to 3)) {
            val g = group(n, t)
            val held = g.shares.map { (it.toByteArray().size - 4) / 32 }
            val expected = (1 until t).fold(1L) { acc, i -> acc * (n - i) / i }.toInt()
            assertEquals(List(n) { expected }, held)
            val coalition = g.shares.take(t - 1).flatMap { it.toByteArray().drop(4).chunked(32) }.toSet()
            val everySeed = g.shares.flatMap { it.toByteArray().drop(4).chunked(32) }.toSet()
            assertTrue(coalition.size < everySeed.size, "$t-of-$n: a coalition of ${t - 1} misses at least one seed")
        }
    }

    // ---- D5's fallback: a BIP32 child of the group's key, signing with the parent's shares ----

    private val n = BigInteger("fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141", 16)
    private fun scalar(bytes: ByteArray) = BigInteger(1, bytes).mod(n)
    private fun parity(key: PublicKey) = if (key.isEven()) BigInteger.ONE else n - BigInteger.ONE

    /**
     * Passes the child key to the library as `group_pk`, so it takes the child's coefficient and nonce binding, and
     * adds `e * a * g * gacc * tweak` to the group's partial signature afterwards. Written out from BIP-327, so this
     * is what a coordinator would have to compute.
     */
    private fun treeSpendWithInnerTweak(g: IcebergGroup, tweak: ByteVector32, sessionId: ByteVector32): Boolean {
        val childKey = g.groupPublicKey + PrivateKey(tweak).publicKey()
        val keys = listOf(childKey, operator.publicKey())
        val sweepLeaf = ScriptTree.Leaf(listOf(OP_PUSHDATA(operator.publicKey().xOnly()), OP_CHECKSIG))
        val internal = Musig2.aggregateKeys(keys)
        val coin = outputTx(Script.pay2tr(internal, sweepLeaf as ScriptTree))
        val spend = spendOf(coin)
        val message = Transaction.hashForSigningTaprootKeyPath(spend, 0, listOf(coin.txOut[0]), SigHash.SIGHASH_DEFAULT)
        val taprootTweak = internal.tweak(Crypto.TaprootTweak.ScriptPathTweak(sweepLeaf.hash()))
        val (_, plainCache) = KeyAggCache.create(keys)
        val q0 = plainCache.tweak(ByteVector32.Zeroes, isXonly = false).right!!.second
        val (cache, q1) = plainCache.tweak(taprootTweak, isXonly = true).right!!

        val contributions = (1..g.quorum).map { Iceberg.generateNonce(g.shares[it - 1], g.caches[it - 1], sessionId) }
        val groupNonce = Iceberg.aggregateNonces(contributions, g.n, g.t, childKey).right!!
        val (operatorSecret, operatorNonce) = Musig2.generateNonce(label("operator nonce inner"), Either.Left(operator), keys, null, null)
        val cosigner = IcebergSigner.cosignerAggregatedNonce(listOf(operatorNonce)).right!!
        val shares = (1..g.t).map { Iceberg.partialSign(g.shares[it - 1], g.caches[it - 1], sessionId, contributions, childKey, cache, message, cosigner).right!! }
        val groupPartial = Iceberg.aggregatePartialSignatures(shares, g.n, g.t).right!!

        val aggNonce = IndividualNonce.aggregate(listOf(groupNonce, operatorNonce)).right!!
        val session = Session.create(aggNonce, message, cache)

        val aggBytes = aggNonce.toByteArray()
        val b = scalar(Crypto.taggedHash(aggBytes + q1.xOnly().value.toByteArray() + message.toByteArray(), "MuSig/noncecoef").toByteArray())
        val r1 = PublicKey(aggBytes.copyOfRange(0, 33))
        val r2 = PublicKey(aggBytes.copyOfRange(33, 66))
        val r = r1 + r2 * PrivateKey(ByteVector32(b.toByteArray().let { it.takeLast(32).toByteArray().let { t -> ByteArray(32 - t.size) + t } }))
        val e = scalar(Crypto.taggedHash(r.xOnly().value.toByteArray() + q1.xOnly().value.toByteArray() + message.toByteArray(), "BIP0340/challenge").toByteArray())
        val list = Crypto.taggedHash(keys.flatMap { it.value.toByteArray().asList() }.toByteArray(), "KeyAgg list")
        val a = scalar(Crypto.taggedHash(list.toByteArray() + childKey.value.toByteArray(), "KeyAgg coefficient").toByteArray()).let { coef ->
            if (keys.first() == childKey) coef else BigInteger.ONE
        }
        val gacc = parity(q0)
        val correction = e * a % n * parity(q1) % n * gacc % n * scalar(tweak.toByteArray()) % n
        val corrected = (scalar(groupPartial.toByteArray()) + correction).mod(n)
        val correctedBytes = corrected.toByteArray().takeLast(32).toByteArray().let { ByteArray(32 - it.size) + it }

        val sig = session.aggregateSigs(listOf(ByteVector32(correctedBytes), session.sign(operatorSecret, operator).right!!)).right!!
        return spends(spend.updateWitness(0, Script.witnessKeyPathPay2tr(sig)), coin)
    }

    @Test
    fun `spike 3 fallback - what the library and a coordinator's correction do with a BIP32 child in the tree`() {
        val g = group(4, 2)
        val tweak = ByteVector32(Hex.decode("07".repeat(32)))
        assertTrue(treeSpendWithInnerTweak(g, tweak, label("inner tweak")))
    }
}
