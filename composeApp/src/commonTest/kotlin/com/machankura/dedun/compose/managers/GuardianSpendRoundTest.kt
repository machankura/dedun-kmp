package com.machankura.dedun.compose.managers

import fr.acinq.bitcoin.ByteVector
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
import fr.acinq.bitcoin.crypto.dkg.chill.ChillDKG
import fr.acinq.bitcoin.crypto.dkg.chill.ParticipantFinalizeResult
import fr.acinq.bitcoin.crypto.frost.IndividualNonce
import fr.acinq.bitcoin.crypto.frost.SecretNonce
import fr.acinq.bitcoin.crypto.frost.Session
import fr.acinq.bitcoin.crypto.frost.TweakCache
import fr.acinq.secp256k1.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * An ancestor group spending a descendant's coin through its guardian leaf,
 * against real ChillDKG and real FROST, with no database and no manager.
 *
 * The counterpart of `GroupWalletSpendRoundTest` for the script path, and judged
 * the same way: by `correctlySpends`, the script interpreter, which is the only
 * check that asks what the network will -- including whether the coin has sat
 * long enough for the leaf's relative lock.
 *
 * The ceremony is the ancestor's. The descendants' keys are anybody's, since
 * nothing here signs as them: what is under test is that the key a descendant
 * derives from its certificate's grant is the key the ancestor's shares sign as.
 */
class GuardianSpendRoundTest {
    private val participants = 3
    private val threshold = 2

    /** The ancestor's ceremony, as in `GroupWalletSpendRoundTest`. */
    private val dkg: List<ParticipantFinalizeResult> = run {
        val hostSecretKeys = (1..participants).map { PrivateKey(ByteVector32(ByteArray(32) { i -> (it * 5 + i).toByte() })) }
        val hostPublicKeys = hostSecretKeys.map { ChillDKG.hostPublicKey(it) }
        val step1 = hostSecretKeys.mapIndexed { i, key ->
            ChillDKG.participantStep1(key, hostPublicKeys, threshold, ByteVector32(ByteArray(32) { (i + 3).toByte() }))
        }
        val coordinator1 = ChillDKG.coordinatorStep1(step1.map { it.message }, hostPublicKeys, threshold)
        val step2 = hostSecretKeys.mapIndexed { i, key ->
            ChillDKG.participantStep2(key, step1[i].state, coordinator1.message, ByteVector32(ByteArray(32) { (i + 17).toByte() }))
        }
        val certificate = ChillDKG.coordinatorFinalize(coordinator1.state, step2.map { it.certEqSignature }, threshold).certificate
        (0 until participants).map { i -> ChillDKG.participantFinalize(step2[i].state, certificate, participants, threshold) }
            .onEach { assertTrue(it.fault.isOk, "the ceremony has to succeed for the rest to mean anything") }
    }

    private val ancestorKey: PublicKey = dkg[0].thresholdPublicKey!!
    private val publicShares: List<PublicKey> = dkg[0].publicShares
    private val ancestorRoom = SharedKeyDerivation.marmotGroupId(ancestorKey.toString())

    private fun someKey(seed: Int): String =
        PrivateKey(ByteVector32(Hex.decode(seed.toString(16).padStart(64, '0')))).publicKey().toString()

    private val chain = Chain.Regtest

    /** A guarded address of [childKey]'s wallet, under [grant]: its internal key, its tree, and its script. */
    private class Address(val internalKey: fr.acinq.bitcoin.XonlyPublicKey, val leaves: List<ScriptTree.Leaf>, val tree: ScriptTree) {
        val pubkeyScript: ByteVector get() = ByteVector(Script.write(Script.pay2tr(internalKey, tree)))
    }

    private fun address(childKey: String, grant: List<GuardianDerivation.Guardian>, change: Long = 0, index: Long = 0): Address {
        val account = GroupWalletDerivation.account(childKey, chain)
        val internalKey = account.extendedPublicKey.derivePublicKey(change).derivePublicKey(index).publicKey.xOnly()
        val leaves = GuardianDerivation.leaves(grant, chain, 0, change, index)
        return Address(internalKey, leaves, GuardianDerivation.tree(leaves))
    }

    private fun funding(address: Address) = Transaction(
        version = 2,
        txIn = listOf(TxIn(OutPoint(TxId(ByteVector32(ByteArray(32) { 0x24 })), 0), ByteVector.empty, 0xffffffffL)),
        txOut = listOf(TxOut(Satoshi(50_000), address.pubkeyScript)),
        lockTime = 0,
    )

    private fun spend(funding: Transaction, sequence: Long = GuardianDerivation.DEFAULT_DELAY) = Transaction(
        version = 2,
        txIn = listOf(TxIn(OutPoint(funding, 0), ByteVector.empty, sequence)),
        txOut = listOf(TxOut(Satoshi(49_000), Script.pay2wpkh(PrivateKey(ByteVector32(ByteArray(32) { 9 })).publicKey()))),
        lockTime = 0,
    )

    /** One FROST instance over [message] with [cache], by signers 0 and 2. */
    private fun sign(message: ByteVector32, cache: TweakCache): ByteVector64 {
        val signerIds = listOf(0, 2)
        val nonces = signerIds.map { id ->
            SecretNonce.generate(
                sessionRandom = ByteVector32(ByteArray(32) { (id + 40).toByte() }),
                secretShare = dkg[id].secretShare!!,
                publicShare = publicShares[id],
                tweakedThresholdPublicKey = cache.tweakedPublicKey,
                message = message,
                extraInput = null,
            )
        }
        val session = Session.create(
            aggregatedNonce = IndividualNonce.aggregate(nonces.map { it.second }).right!!,
            signerIds = signerIds.map { it.toUInt() },
            signerPublicShares = signerIds.map { publicShares[it] },
            nParticipants = participants,
            threshold = threshold,
            tweakCache = cache,
            message = message,
        )
        val partials = signerIds.mapIndexed { position, id ->
            session.sign(nonces[position].first, dkg[id].secretShare!!, id.toUInt()).right!!.also {
                assertTrue(session.verify(it, nonces[position].second, publicShares[id], position), "signer $id's partial signature")
            }
        }
        return session.aggregateSigs(partials).right!!
    }

    /** The ancestor signs [leaf] of [address] in [tx] with [cache], and the witness goes on. */
    private fun guardianSpend(tx: Transaction, funding: Transaction, address: Address, leaf: ScriptTree.Leaf, cache: TweakCache): Transaction {
        val sighash = tx.hashForSigningTaprootScriptPath(0, funding.txOut, SigHash.SIGHASH_DEFAULT, leaf.hash())
        val signature = sign(sighash, cache)
        return tx.updateWitness(0, Script.witnessScriptPathPay2tr(address.internalKey, leaf, ScriptWitness(listOf(signature)), address.tree))
    }

    private fun assertSpends(tx: Transaction, funding: Transaction) =
        tx.correctlySpends(mapOf(OutPoint(funding, 0) to funding.txOut[0]), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)

    @Test
    fun `a parent's quorum spends its child's coin through its leaf`() {
        val child = someKey(0xc1)
        val childRoom = SharedKeyDerivation.marmotGroupId(child)
        val grant = GuardianDerivation.grant(ancestorKey.toString(), ancestorRoom, emptyList(), childRoom)
        val address = address(child, grant)
        val funding = funding(address)

        val signing = GuardianDerivation.signingCache(ancestorKey.toString(), listOf(childRoom), chain, 0, 0, 0)
        val signed = guardianSpend(spend(funding), funding, address, address.leaves[0], signing.cache)

        assertEquals(1, address.leaves.size)
        assertEquals(33, signed.txIn[0].witness.stack.last().size(), "a tree of one leaf has a bare control block")
        assertSpends(signed, funding)
    }

    @Test
    fun `a grandparent's quorum spends its grandchild's coin from the bottom of the tree`() {
        val middle = someKey(0xb1)
        val middleRoom = SharedKeyDerivation.marmotGroupId(middle)
        val child = someKey(0xc2)
        val childRoom = SharedKeyDerivation.marmotGroupId(child)

        val middleGrant = GuardianDerivation.grant(ancestorKey.toString(), ancestorRoom, emptyList(), middleRoom)
        val grant = GuardianDerivation.grant(middle, middleRoom, middleGrant, childRoom)
        val address = address(child, grant, change = 1, index = 3)
        val funding = funding(address)

        val lineage = GuardianDerivation.lineage(grant, childRoom, ancestorRoom)!!
        assertEquals(listOf(middleRoom, childRoom), lineage)
        val signing = GuardianDerivation.signingCache(ancestorKey.toString(), lineage, chain, 0, 1, 3)
        val signed = guardianSpend(spend(funding), funding, address, address.leaves[1], signing.cache)

        assertEquals(65, signed.txIn[0].witness.stack.last().size(), "the root's leaf is one level down")
        assertSpends(signed, funding)
    }

    @Test
    fun `the coin has to have sat for 12 blocks`() {
        val child = someKey(0xc3)
        val childRoom = SharedKeyDerivation.marmotGroupId(child)
        val grant = GuardianDerivation.grant(ancestorKey.toString(), ancestorRoom, emptyList(), childRoom)
        val address = address(child, grant)
        val funding = funding(address)
        val cache = GuardianDerivation.signingCache(ancestorKey.toString(), listOf(childRoom), chain, 0, 0, 0).cache

        assertSpends(guardianSpend(spend(funding, sequence = 12), funding, address, address.leaves[0], cache), funding)
        assertFails("eleven blocks") {
            assertSpends(guardianSpend(spend(funding, sequence = 11), funding, address, address.leaves[0], cache), funding)
        }
        assertFails("no relative lock at all") {
            assertSpends(guardianSpend(spend(funding, sequence = 0xfffffffdL), funding, address, address.leaves[0], cache), funding)
        }
    }

    /**
     * A chosen delay, at every edge of its encoding: the script interpreter takes
     * the spend at exactly that many blocks and not one fewer. Where an encoding
     * mistake would be caught before a coin could be lost to it.
     * docs/guardian-delay.md, Phase 2.
     */
    @Test
    fun `a chosen delay holds at exactly its own number of blocks`() {
        val child = someKey(0xc6)
        val childRoom = SharedKeyDerivation.marmotGroupId(child)
        val cache = GuardianDerivation.signingCache(ancestorKey.toString(), listOf(childRoom), chain, 0, 0, 0).cache

        listOf(6L, 16L, 17L, 127L, 128L, 32_767L, 32_768L, 65_535L).forEach { delay ->
            val grant = GuardianDerivation.grant(ancestorKey.toString(), ancestorRoom, emptyList(), childRoom, delay = delay)
            val address = address(child, grant)
            val funding = funding(address)

            assertSpends(guardianSpend(spend(funding, sequence = delay), funding, address, address.leaves[0], cache), funding)
            assertFails("one block short of $delay") {
                assertSpends(guardianSpend(spend(funding, sequence = delay - 1), funding, address, address.leaves[0], cache), funding)
            }
        }
    }

    @Test
    fun `the wrong cache signs for a key the leaf does not name`() {
        val child = someKey(0xc4)
        val childRoom = SharedKeyDerivation.marmotGroupId(child)
        val sibling = SharedKeyDerivation.marmotGroupId(someKey(0xc5))
        val grant = GuardianDerivation.grant(ancestorKey.toString(), ancestorRoom, emptyList(), childRoom)
        val address = address(child, grant)
        val funding = funding(address)

        val wrongCaches = mapOf(
            "the room key's" to SharedKeyDerivation.derive(ancestorKey.toString()).cache,
            "the guardian root's" to GuardianDerivation.root(ancestorKey.toString()).cache,
            "a sibling's leaf" to GuardianDerivation.signingCache(ancestorKey.toString(), listOf(sibling), chain, 0, 0, 0).cache,
            "the next address's leaf" to GuardianDerivation.signingCache(ancestorKey.toString(), listOf(childRoom), chain, 0, 0, 1).cache,
            "the wallet's own key" to GroupWalletDerivation.account(ancestorKey.toString(), chain).cache,
        )

        wrongCaches.forEach { (name, cache) ->
            assertFails("$name cache") {
                assertSpends(guardianSpend(spend(funding), funding, address, address.leaves[0], cache), funding)
            }
        }
    }

    @Test
    fun `a guardian signature is over the leaf it names`() {
        // Sign the parent's leaf and present it as the grandparent's: the leaf hash
        // is in the sighash, so a signature cannot be moved between leaves.
        val middle = someKey(0xb2)
        val middleRoom = SharedKeyDerivation.marmotGroupId(middle)
        val child = someKey(0xc6)
        val childRoom = SharedKeyDerivation.marmotGroupId(child)
        val grant = GuardianDerivation.grant(middle, middleRoom, GuardianDerivation.grant(ancestorKey.toString(), ancestorRoom, emptyList(), middleRoom), childRoom)
        val address = address(child, grant)
        val funding = funding(address)
        val tx = spend(funding)
        val cache = GuardianDerivation.signingCache(ancestorKey.toString(), listOf(middleRoom, childRoom), chain, 0, 0, 0).cache

        val overParentLeaf = sign(tx.hashForSigningTaprootScriptPath(0, funding.txOut, SigHash.SIGHASH_DEFAULT, address.leaves[0].hash()), cache)
        val moved = tx.updateWitness(0, Script.witnessScriptPathPay2tr(address.internalKey, address.leaves[1], ScriptWitness(listOf(overParentLeaf)), address.tree))

        assertTrue(!Crypto.verifySignatureSchnorr(tx.hashForSigningTaprootScriptPath(0, funding.txOut, SigHash.SIGHASH_DEFAULT, address.leaves[1].hash()), overParentLeaf, GuardianDerivation.leafKey(grant[1], chain, 0, 0, 0)))
        assertFails { assertSpends(moved, funding) }
    }
}
