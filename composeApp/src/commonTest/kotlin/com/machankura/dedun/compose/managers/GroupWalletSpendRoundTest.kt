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
import fr.acinq.bitcoin.SigHash
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import fr.acinq.bitcoin.crypto.dkg.chill.ChillDKG
import fr.acinq.bitcoin.crypto.dkg.chill.ParticipantFinalizeResult
import fr.acinq.bitcoin.crypto.frost.AggregatedNonce
import fr.acinq.bitcoin.crypto.frost.IndividualNonce
import fr.acinq.bitcoin.crypto.frost.SecretNonce
import fr.acinq.bitcoin.crypto.frost.Session
import fr.acinq.bitcoin.crypto.frost.TweakCache
import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * A group's threshold key spending its own wallet, against real ChillDKG and
 * real FROST, with no database and no manager in the way.
 *
 * This is the test the rest of the wallet is built on. `FrostSigningManager`
 * will spread these same calls across messages and devices; what it cannot
 * check from inside is that the calls themselves produce a transaction the
 * network accepts. So the judge here is `correctlySpends` -- the script
 * interpreter -- and never `verifySignatureSchnorr` alone: a signature that
 * verifies against the wrong sighash, or for the wrong key, is a perfectly
 * good signature and a transaction nobody can mine.
 */
class GroupWalletSpendRoundTest {
    private val participants = 3
    private val threshold = 2

    /** A real ceremony, so the shares are what a member's device actually holds. Fixed randomness, so it is the same ceremony every run. */
    private val dkg: List<ParticipantFinalizeResult> = run {
        val hostSecretKeys = (1..participants).map { PrivateKey(ByteVector32(ByteArray(32) { i -> (it * 7 + i).toByte() })) }
        val hostPublicKeys = hostSecretKeys.map { ChillDKG.hostPublicKey(it) }
        val step1 = hostSecretKeys.mapIndexed { i, key ->
            ChillDKG.participantStep1(key, hostPublicKeys, threshold, ByteVector32(ByteArray(32) { (i + 1).toByte() }))
        }
        val coordinator1 = ChillDKG.coordinatorStep1(step1.map { it.message }, hostPublicKeys, threshold)
        val step2 = hostSecretKeys.mapIndexed { i, key ->
            ChillDKG.participantStep2(key, step1[i].state, coordinator1.message, ByteVector32(ByteArray(32) { (i + 11).toByte() }))
        }
        val certificate = ChillDKG.coordinatorFinalize(coordinator1.state, step2.map { it.certEqSignature }, threshold).certificate
        (0 until participants).map { i -> ChillDKG.participantFinalize(step2[i].state, certificate, participants, threshold) }
            .onEach { assertTrue(it.fault.isOk, "the ceremony has to succeed for the rest to mean anything") }
    }

    private val thresholdPublicKey: PublicKey = dkg[0].thresholdPublicKey!!
    private val publicShares: List<PublicKey> = dkg[0].publicShares

    private val account = GroupWalletDerivation.account(thresholdPublicKey.toString(), Chain.Regtest)
    private val first = GroupWalletDerivation.derive(account, GroupWalletDerivation.RECEIVE, 0)
    private val second = GroupWalletDerivation.derive(account, GroupWalletDerivation.RECEIVE, 1)
    private val change = GroupWalletDerivation.derive(account, GroupWalletDerivation.CHANGE, 0)

    /** Somebody else's money, paying the group's first two receive addresses. */
    private val funding = Transaction(
        version = 2,
        txIn = listOf(TxIn(OutPoint(ByteVector32(ByteArray(32) { 0x42 }).let { fr.acinq.bitcoin.TxId(it) }, 0), signatureScript = ByteVector.empty, sequence = 0xffffffffL)),
        txOut = listOf(TxOut(Satoshi(60_000), first.pubkeyScript), TxOut(Satoshi(40_000), second.pubkeyScript)),
        lockTime = 0,
    )

    private val spentOutputs: List<TxOut> = funding.txOut

    /** The group pays 70,000 out, 29,000 back to its own change, 1,000 to fees. */
    private val spend = Transaction(
        version = 2,
        txIn = listOf(
            TxIn(OutPoint(funding, 0), signatureScript = ByteVector.empty, sequence = 0xfffffffdL),
            TxIn(OutPoint(funding, 1), signatureScript = ByteVector.empty, sequence = 0xfffffffdL),
        ),
        txOut = listOf(
            TxOut(Satoshi(70_000), Script.pay2wpkh(PrivateKey(ByteVector32(ByteArray(32) { 9 })).publicKey())),
            TxOut(Satoshi(29_000), change.pubkeyScript),
        ),
        lockTime = 0,
    )

    private val derivations = listOf(first, second)

    private fun sighash(tx: Transaction, input: Int, outputs: List<TxOut> = spentOutputs): ByteVector32 =
        tx.hashForSigningTaprootKeyPath(input, outputs, SigHash.SIGHASH_DEFAULT)

    private class Signed(val signature: ByteVector64, val aggregatedNonce: AggregatedNonce)

    /**
     * One input's FROST instance, in the order the manager runs it: a nonce per
     * signer from that input's own seed, aggregated; a session over that input's
     * sighash and cache; a partial signature from each signer, checked; the aggregate.
     */
    private fun signInput(
        message: ByteVector32,
        cache: TweakCache,
        seed: Int,
        signerIds: List<Int> = listOf(0, 2),
    ): Signed {
        val nonces = signerIds.map { id ->
            SecretNonce.generate(
                sessionRandom = ByteVector32(ByteArray(32) { (seed * 16 + id).toByte() }),
                secretShare = dkg[id].secretShare!!,
                publicShare = publicShares[id],
                tweakedThresholdPublicKey = cache.tweakedPublicKey,
                message = message,
                extraInput = null,
            )
        }
        val aggregated = IndividualNonce.aggregate(nonces.map { it.second }).right!!
        val session = Session.create(
            aggregatedNonce = aggregated,
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
        return Signed(session.aggregateSigs(partials).right!!, aggregated)
    }

    private fun witnessed(tx: Transaction, signatures: List<ByteVector64>): Transaction =
        signatures.foldIndexed(tx) { input, partial, signature -> partial.updateWitness(input, Script.witnessKeyPathPay2tr(signature)) }

    private fun assertSpends(tx: Transaction) =
        tx.correctlySpends(funding.txOut.mapIndexed { i, out -> OutPoint(funding, i.toLong()) to out }.toMap(), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)

    @Test
    fun `a quorum of the ceremony's shares spends the group's coins`() {
        val signed = derivations.mapIndexed { input, derived -> signInput(sighash(spend, input), derived.cache, seed = input + 1) }

        signed.forEachIndexed { input, it ->
            assertTrue(Crypto.verifySignatureSchnorr(sighash(spend, input), it.signature, derivations[input].outputKey))
        }
        assertSpends(witnessed(spend, signed.map { it.signature }))
    }

    @Test
    fun `any quorum will do`() {
        val signed = derivations.mapIndexed { input, derived ->
            signInput(sighash(spend, input), derived.cache, seed = input + 1, signerIds = listOf(1, 2))
        }

        assertSpends(witnessed(spend, signed.map { it.signature }))
    }

    @Test
    fun `each input is its own instance, with its own nonce`() {
        val signed = derivations.mapIndexed { input, derived -> signInput(sighash(spend, input), derived.cache, seed = input + 1) }

        // Every assertion above still passes if two inputs share a nonce. What
        // sharing costs is each signer's share, to anyone who sees both partials.
        assertNotEquals(signed[0].aggregatedNonce, signed[1].aggregatedNonce)
        assertNotEquals(sighash(spend, 0), sighash(spend, 1), "and the two messages differ, so one nonce would be two equations")
    }

    @Test
    fun `the wrong cache signs for a key the coins are not at`() {
        val wrongCaches = mapOf(
            "the other input's" to second.cache,
            "the bare threshold key's" to TweakCache.create(thresholdPublicKey),
            "the account key's, without the tail or the key-path tweak" to account.cache,
        )

        wrongCaches.forEach { (name, cache) ->
            val signatures = listOf(signInput(sighash(spend, 0), cache, seed = 1).signature, signInput(sighash(spend, 1), second.cache, seed = 2).signature)
            assertFails("$name cache") { assertSpends(witnessed(spend, signatures)) }
        }
    }

    @Test
    fun `a sighash over a lie about an amount does not spend`() {
        // BIP341 commits every spent amount into every sighash. That is what lets a
        // signer trust a PSBT's amounts without fetching the transactions: a
        // proposer who lies gets a signature the network rejects, not a bigger fee.
        val lied = listOf(spentOutputs[0].copy(amount = Satoshi(600_000)), spentOutputs[1])
        val signatures = derivations.mapIndexed { input, derived -> signInput(sighash(spend, input, lied), derived.cache, seed = input + 1).signature }

        assertFails { assertSpends(witnessed(spend, signatures)) }
    }
}
