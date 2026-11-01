package com.machankura.dedun.compose.managers

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.ByteVector64
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.ScriptFlags
import fr.acinq.bitcoin.ScriptTree
import fr.acinq.bitcoin.ScriptWitness
import fr.acinq.bitcoin.SigHash
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxId
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import fr.acinq.bitcoin.XonlyPublicKey
import fr.acinq.bitcoin.byteVector32
import fr.acinq.bitcoin.crypto.dkg.chill.ChillDKG
import fr.acinq.bitcoin.crypto.dkg.chill.ParticipantFinalizeResult
import fr.acinq.bitcoin.crypto.frost.IndividualNonce
import fr.acinq.bitcoin.crypto.frost.SecretNonce
import fr.acinq.bitcoin.crypto.frost.Session
import fr.acinq.bitcoin.crypto.frost.TweakCache
import fr.acinq.lightning.CltvExpiryDelta
import fr.acinq.lightning.crypto.ChannelKeys
import fr.acinq.lightning.crypto.CommitmentPublicKeys
import fr.acinq.lightning.transactions.Scripts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The Lightning wallet's basepoints, pinned, and spent by FROST the way a closing channel's outputs
 * would be.
 *
 * The spends are built with lightning-kmp's own `Scripts.Taproot`, so a leaf this test signs is the leaf
 * a real commitment carries; the judge is the script interpreter. Several per-commitment points are
 * used because a parity mistake in a plain tweak passes half the time.
 */
class GroupLightningDerivationTest {
    private val participants = 3
    private val threshold = 2

    private val dkg: List<ParticipantFinalizeResult> = run {
        val hostSecretKeys = (1..participants).map { PrivateKey(ByteVector32(ByteArray(32) { i -> (it * 5 + i).toByte() })) }
        val hostPublicKeys = hostSecretKeys.map { ChillDKG.hostPublicKey(it) }
        val step1 = hostSecretKeys.mapIndexed { i, key -> ChillDKG.participantStep1(key, hostPublicKeys, threshold, ByteVector32(ByteArray(32) { (i + 3).toByte() })) }
        val coordinator1 = ChillDKG.coordinatorStep1(step1.map { it.message }, hostPublicKeys, threshold)
        val step2 = hostSecretKeys.mapIndexed { i, key -> ChillDKG.participantStep2(key, step1[i].state, coordinator1.message, ByteVector32(ByteArray(32) { (i + 17).toByte() })) }
        val certificate = ChillDKG.coordinatorFinalize(coordinator1.state, step2.map { it.certEqSignature }, threshold).certificate
        (0 until participants).map { i -> ChillDKG.participantFinalize(step2[i].state, certificate, participants, threshold) }
            .onEach { assertTrue(it.fault.isOk) }
    }
    private val k: PublicKey = dkg[0].thresholdPublicKey!!
    private val basepoints = GroupLightningDerivation.basepoints(k.toString(), Chain.Regtest, wallet = 0)

    private fun key(seed: Int) = PrivateKey(ByteVector32(ByteArray(32) { (seed + it).toByte() }))

    /** A FROST signature by members 0 and 2, under [cache], over [message]. */
    private fun frost(message: ByteVector32, cache: TweakCache, seed: Int): ByteVector64 {
        val signers = listOf(0, 2)
        val nonces = signers.map { id ->
            SecretNonce.generate(ByteVector32(ByteArray(32) { (seed * 16 + id).toByte() }), dkg[id].secretShare!!, dkg[0].publicShares[id], cache.tweakedPublicKey, message, null)
        }
        val session = Session.create(IndividualNonce.aggregate(nonces.map { it.second }).right!!, signers.map { it.toUInt() }, signers.map { dkg[0].publicShares[it] }, participants, threshold, cache, message)
        val partials = signers.mapIndexed { i, id -> session.sign(nonces[i].first, dkg[id].secretShare!!, id.toUInt()).right!! }
        return session.aggregateSigs(partials).right!!
    }

    /** Spend a leaf of a NUMS-keyed output the way the group sweeps one. */
    private fun spendsUnderFrost(script: List<fr.acinq.bitcoin.ScriptElt>, leaf: ScriptTree.Leaf, tree: ScriptTree, csv: Long, cache: TweakCache, seed: Int): Boolean {
        val output = TxOut(Satoshi(50_000), script)
        val funding = Transaction(2, listOf(TxIn(OutPoint(TxId(ByteVector32(ByteArray(32) { 7 })), 0), 0xffffffffL)), listOf(output), 0)
        val unsigned = Transaction(2, listOf(TxIn(OutPoint(funding, 0), csv)), listOf(TxOut(Satoshi(49_000), Script.pay2wpkh(key(90).publicKey()))), 0)
        val sighash = unsigned.hashForSigningTaprootScriptPath(0, listOf(output), SigHash.SIGHASH_DEFAULT, leaf.hash())
        val sig = frost(sighash, cache, seed)
        val signed = unsigned.updateWitness(0, Script.witnessScriptPathPay2tr(Scripts.Taproot.NUMS_POINT.xOnly(), leaf, ScriptWitness(listOf(sig)), tree))
        return runCatching { Transaction.correctlySpends(signed, mapOf(OutPoint(funding, 0) to output), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS) }.isSuccess
    }

    private fun commitmentKeys(remotePayment: PublicKey = key(1).publicKey(), localDelayed: PublicKey = key(2).publicKey()) =
        CommitmentPublicKeys(localDelayed, remotePayment, key(3).publicKey(), key(4).publicKey(), key(5).publicKey())

    @Test
    fun `a quorum spends to_remote under the payment basepoint`() {
        val keys = commitmentKeys(remotePayment = basepoints.payment.publicKey)
        val leaf = Scripts.Taproot.toRemoteScriptTree(keys)
        assertTrue(spendsUnderFrost(Scripts.Taproot.toRemote(keys), leaf, leaf, 1, GroupLightningDerivation.leafCache(basepoints.payment, null), seed = 1))
    }

    @Test
    fun `a quorum spends to_local under the delayed basepoint plus each commitment's tweak`() {
        val delay = CltvExpiryDelta(144)
        for (n in 0 until 8) {
            val pcp = key(40 + n).publicKey()
            val leafKey = ChannelKeys.remotePerCommitmentPublicKey(basepoints.delayedPayment.publicKey, pcp)
            val tweak = Crypto.sha256(pcp.value + basepoints.delayedPayment.publicKey.value).byteVector32()
            val keys = commitmentKeys(localDelayed = leafKey)
            val tree = Scripts.Taproot.toLocalScriptTree(keys, delay)
            val cache = GroupLightningDerivation.leafCache(basepoints.delayedPayment, tweak)
            assertEquals(leafKey.xOnly(), cache.tweakedPublicKey, "commitment $n")
            assertTrue(spendsUnderFrost(Scripts.Taproot.toLocal(keys, delay), tree.localDelayed, tree.scriptTree, delay.toLong(), cache, seed = 10 + n), "commitment $n")
            // The untweaked basepoint is not the leaf key.
            assertTrue(!spendsUnderFrost(Scripts.Taproot.toLocal(keys, delay), tree.localDelayed, tree.scriptTree, delay.toLong(), basepoints.delayedPayment.cache, seed = 30 + n))
        }
    }

    @Test
    fun `the derivation is pinned`() {
        // A fixed K, so the tag, the byte order of the chain hash and the order of the HMAC's inputs are
        // frozen here. Changing any of them moves every group's basepoints: a protocol fork.
        val fixed = PrivateKey(ByteVector32(ByteArray(32) { 0x2a })).publicKey().toString()
        val regtest = GroupLightningDerivation.basepoints(fixed, Chain.Regtest, 0)
        val mainnet = GroupLightningDerivation.basepoints(fixed, Chain.Mainnet, 0)
        val next = GroupLightningDerivation.basepoints(fixed, Chain.Regtest, 1)
        assertEquals("020c73decf91066ea570ede057f2ef4521914a4902ceb12c124a54132f8c0cc319", regtest.payment.publicKey.toString())
        assertEquals("02c7d1b23df4878458422d042342d16b157f7628381e9977efc85d1be6d470eb14", regtest.delayedPayment.publicKey.toString())
        assertEquals("03fc539a36e31dc5940a34e06be922aeb65dd8b6a72bc414d34c8d0f88c44ca5e4", mainnet.payment.publicKey.toString())
        assertEquals("03c5901c78139d459aef3ed252431090c3d12a4e14b0f094c89284a8d4d58931d4", mainnet.delayedPayment.publicKey.toString())
        assertEquals("0309dac3e80cd059749ff10add118a4d85cb90688021f54be3be55de782c4391c6", next.payment.publicKey.toString())
        assertEquals("02b8fe5fe20854fb244ae7f95b0827a9d9c2a3b974cef24c8e0d92a72cd3f66dfe", next.delayedPayment.publicKey.toString())
    }

    @Test
    fun `the basepoints are unrelated to every other key the group holds`() {
        val wallet = GroupWalletDerivation.account(k.toString(), Chain.Regtest)
        val others = setOf(
            k.xOnly(),
            SharedKeyDerivation.derive(k.toString()).publicKey,
            wallet.extendedPublicKey.publicKey.xOnly(),
            GroupWalletDerivation.derive(wallet, GroupWalletDerivation.RECEIVE, 0).internalKey,
            GroupWalletDerivation.derive(wallet, GroupWalletDerivation.CHANGE, 0).internalKey,
        )
        assertTrue(basepoints.payment.publicKey.xOnly() !in others)
        assertTrue(basepoints.delayedPayment.publicKey.xOnly() !in others)
        assertNotEquals(basepoints.payment.publicKey, basepoints.delayedPayment.publicKey)
        assertNotEquals(basepoints.payment.publicKey, GroupLightningDerivation.basepoints(k.toString(), Chain.Mainnet, 0).payment.publicKey)
        assertNotEquals(basepoints.payment.publicKey, GroupLightningDerivation.basepoints(k.toString(), Chain.Regtest, 1).payment.publicKey)
        assertFails { GroupLightningDerivation.basepoints(k.toString(), Chain.Regtest, 0x80000000L) }
    }
}
