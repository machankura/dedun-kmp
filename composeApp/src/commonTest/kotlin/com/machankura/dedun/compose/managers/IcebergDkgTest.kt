package com.machankura.dedun.compose.managers

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.byteVector32
import fr.acinq.bitcoin.crypto.Pack
import fr.acinq.bitcoin.crypto.iceberg.Iceberg
import fr.acinq.bitcoin.crypto.iceberg.PublicShare
import fr.acinq.bitcoin.crypto.iceberg.Share
import fr.acinq.bitcoin.crypto.musig2.IndividualNonce
import fr.acinq.bitcoin.crypto.musig2.KeyAggCache
import fr.acinq.bitcoin.crypto.musig2.SecretNonce
import fr.acinq.bitcoin.crypto.musig2.Session
import fr.acinq.bitcoin.utils.Either
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The Iceberg DKG, run between simulated participants against the real library.
 *
 * The layout test is the one to read first: it proves, against the library's
 * own dealer, that a share this DKG assembles is laid out the way
 * `secp256k1_iceberg_share_parse` reads it. Everything after it would pass for a
 * DKG whose seeds were in the wrong order -- the shares would still parse and
 * still aggregate -- right up to the signature that did not verify.
 */
class IcebergDkgTest {
    private val random = Random(0x1ceb)

    private fun bytes32() = random.nextBytes(32).byteVector32()

    private fun hostKeys(n: Int): List<PublicKey> = (1..n).map { PrivateKey(bytes32()).publicKey() }

    private class Run(val params: IcebergDkg.Params, val shares: List<Share>, val publicShares: List<PublicShare>, val groupKey: PublicKey)

    /** Every participant runs every round, honestly. */
    private fun run(n: Int, t: Int): Run {
        val params = IcebergDkg.Params.of(t, hostKeys(n), bytes32())
        val roots = (1..n).associateWith { bytes32() }
        val commitments = (1..n).associateWith { IcebergDkg.round1(params, it, roots.getValue(it)) }
        val shares = (1..n).map { k ->
            val reveals = (1..n).filter { it != k }.associateWith { j -> IcebergDkg.round2(params, j, roots.getValue(j), k) }
            val share = IcebergDkg.assemble(params, k, roots.getValue(k), commitments, reveals)
            assertIs<Either.Right<Share>>(share).value
        }
        val publicShares = shares.map { Iceberg.publicShare(it, null) }
        val key = assertIs<Either.Right<PublicKey>>(IcebergDkg.groupPublicKey(params, publicShares)).value
        return Run(params, shares, publicShares, key)
    }

    /** One signature by [signers] (1-based) beside a stock MuSig2 cosigner, judged by BIP340. */
    private fun signs(run: Run, signers: List<Int>): Boolean {
        val n = run.params.n
        val t = run.params.t
        val cosigner = PrivateKey(bytes32())
        val msg = bytes32()
        val sid = bytes32()
        val keys = listOf(run.groupKey, cosigner.publicKey())
        val (aggregateKey, cache) = KeyAggCache.create(keys)
        val contributions = signers.map { Iceberg.generateNonce(run.shares[it - 1], null, sid) }
        val groupNonce = Iceberg.aggregateNonces(contributions, n, t, run.groupKey).right ?: return false
        val (cosignerSecret, cosignerPublic) = SecretNonce.generate(bytes32(), Either.Left(cosigner), msg, cache, null)
        val aggregated = IndividualNonce.aggregate(listOf(groupNonce, cosignerPublic)).right ?: return false
        val cosignerAggregated = IndividualNonce.aggregate(listOf(cosignerPublic)).right ?: return false
        val session = Session.create(aggregated, msg, cache)
        val signatureShares = signers.map {
            Iceberg.partialSign(run.shares[it - 1], null, sid, contributions, run.groupKey, cache, msg, cosignerAggregated).right ?: return false
        }
        val groupPartial = Iceberg.aggregatePartialSignatures(signatureShares, n, t).right ?: return false
        val cosignerPartial = session.sign(cosignerSecret, cosigner).right ?: return false
        val sig = session.aggregateSigs(listOf(groupPartial, cosignerPartial)).right ?: return false
        return Crypto.verifySignatureSchnorr(msg, sig, aggregateKey)
    }

    private fun quorums(n: Int, t: Int): List<List<Int>> =
        IcebergDkg.subsetsInRankOrder(n, 2 * t - 1).map { mask -> (1..n).filter { mask and (1 shl it) != 0 } }

    @Test
    fun `an assembled share is laid out as the library's dealer lays one out`() {
        for ((n, t) in listOf(4 to 2, 7 to 3, 10 to 4, 5 to 3)) {
            val root = bytes32()
            val dealt = Iceberg.dealShares(n, t, root)
            val ranks = IcebergDkg.subsetsInRankOrder(n, t - 1)
            for (k in 1..n) {
                // keygen_impl.h: seed(rank) = H_"Iceberg/dealer"(root || n || t || rank), appended for every subset k is outside of.
                val seeds = ranks.indices.filter { ranks[it] and (1 shl k) == 0 }.map {
                    Crypto.taggedHash(root.toByteArray() + byteArrayOf(n.toByte(), t.toByte()) + Pack.writeInt32BE(it), "Iceberg/dealer")
                }
                assertEquals(dealt[k - 1], IcebergDkg.share(n, t, k, seeds), "participant $k of $t-of-$n")
            }
        }
    }

    @Test
    fun `the rank order is rss_h's`() {
        // The example rss.h writes out because the reference's docstring was off by one.
        val expected = listOf(setOf(1, 2), setOf(1, 3), setOf(1, 4), setOf(1, 5), setOf(2, 3), setOf(2, 4), setOf(2, 5), setOf(3, 4), setOf(3, 5), setOf(4, 5))
        assertEquals(expected, IcebergDkg.subsetsInRankOrder(5, 2).map { mask -> (1..5).filter { mask and (1 shl it) != 0 }.toSet() })
    }

    @Test
    fun `2-of-4 and 3-of-7 sign from every quorum, and every quorum agrees on the key`() {
        for ((n, t) in listOf(4 to 2, 7 to 3)) {
            val run = run(n, t)
            for (q in quorums(n, t)) {
                assertTrue(signs(run, q), "quorum $q of $t-of-$n")
                val key = Iceberg.groupPublicKey(q.map { run.publicShares[it - 1] }, n, t).right
                assertEquals(run.groupKey, key, "key over $q")
            }
        }
    }

    @Test
    fun `4-of-10 signs`() {
        val run = run(10, 4)
        assertTrue(signs(run, listOf(1, 2, 3, 4, 5, 6, 7)))
        assertTrue(signs(run, listOf(4, 5, 6, 7, 8, 9, 10)))
        assertTrue(signs(run, listOf(1, 3, 5, 7, 8, 9, 10)))
    }

    @Test
    fun `a reveal that was not committed to names its sender`() {
        val params = IcebergDkg.Params.of(2, hostKeys(4), bytes32())
        val roots = (1..4).associateWith { bytes32() }
        val commitments = (1..4).associateWith { IcebergDkg.round1(params, it, roots.getValue(it)) }
        val reveals = (2..4).associateWith { j -> IcebergDkg.round2(params, j, roots.getValue(j), 1) }.toMutableMap()
        reveals[3] = reveals.getValue(3).mapValues { bytes32() }
        val result = IcebergDkg.assemble(params, 1, roots.getValue(1), commitments, reveals)
        val fault = assertIs<IcebergDkg.Fault.BadReveal>(assertIs<Either.Left<IcebergDkg.Fault>>(result).value)
        assertEquals(3, fault.participant)
    }

    @Test
    fun `a silent participant is named`() {
        val params = IcebergDkg.Params.of(2, hostKeys(4), bytes32())
        val roots = (1..4).associateWith { bytes32() }
        val commitments = (1..4).associateWith { IcebergDkg.round1(params, it, roots.getValue(it)) }
        val reveals = (2..3).associateWith { j -> IcebergDkg.round2(params, j, roots.getValue(j), 1) }
        assertEquals(4, (IcebergDkg.assemble(params, 1, roots.getValue(1), commitments, reveals) as Either.Left).value.participant)
        assertEquals(2, (IcebergDkg.assemble(params, 1, roots.getValue(1), commitments - 2, reveals) as Either.Left).value.participant)
    }

    @Test
    fun `a share off the polynomial fails the group key`() {
        val run = run(4, 2)
        val bytes = run.shares[2].toByteArray().also { it[10] = (it[10] + 1).toByte() }
        val tampered = run.publicShares.toMutableList().apply { this[2] = Iceberg.publicShare(Share(bytes), null) }
        assertIs<Either.Left<IcebergDkg.Fault>>(IcebergDkg.groupPublicKey(run.params, tampered))
    }

    @Test
    fun `the session binds the participants, the threshold and the ceremony, but not the order they were listed in`() {
        val keys = hostKeys(5)
        val ceremony = bytes32()
        val base = IcebergDkg.Params.of(2, keys, ceremony)
        assertEquals(base.sessionId, IcebergDkg.Params.of(2, keys.reversed(), ceremony).sessionId)
        assertEquals(base.hostKeys, IcebergDkg.Params.of(2, keys.shuffled(random), ceremony).hostKeys)
        assertNotEquals(base.sessionId, IcebergDkg.Params.of(2, keys, bytes32()).sessionId)
        assertNotEquals(base.sessionId, IcebergDkg.Params.of(2, keys.dropLast(1), ceremony).sessionId)
        assertFailsWith<IllegalArgumentException> { IcebergDkg.Params.of(2, keys + keys.first(), ceremony) }
    }

    @Test
    fun `only deployable groups are offered`() {
        assertEquals(emptyList(), IcebergDkg.deployableThresholds(3))
        assertEquals(listOf(2), IcebergDkg.deployableThresholds(4))
        assertEquals(listOf(2), IcebergDkg.deployableThresholds(6))
        assertEquals(listOf(2, 3), IcebergDkg.deployableThresholds(7))
        assertEquals(listOf(2, 3, 4), IcebergDkg.deployableThresholds(10))
        assertEquals(emptyList(), IcebergDkg.deployableThresholds(11))
        assertTrue(IcebergDkg.isExpressible(3, 2))
        assertFalse(IcebergDkg.isDeployable(3, 2))
        assertFalse(IcebergDkg.isExpressible(4, 3))
        assertFailsWith<IllegalArgumentException> { IcebergDkg.Params.of(3, hostKeys(5), bytes32()) }
    }

    @Test
    fun `a lost share is repaired from the other holders' seeds`() {
        val run = run(7, 3)
        val lost = 4
        val received = (1..7).filter { it != lost }.associateWith { IcebergDkg.repairSeeds(run.params, it, run.shares[it - 1], lost) }
        val repaired = IcebergDkg.repair(run.params, lost, received, run.publicShares[lost - 1])
        assertEquals(run.shares[lost - 1], assertIs<Either.Right<Share>>(repaired).value)

        // One helper lying about one seed is caught, whether or not another helper sent the same seed.
        val lying = received.toMutableMap().apply { this[1] = getValue(1).mapValues { bytes32() } }
        assertIs<Either.Left<IcebergDkg.Fault>>(IcebergDkg.repair(run.params, lost, lying, run.publicShares[lost - 1]))
        val onlyTheLiar = mapOf(1 to lying.getValue(1)) + received.filterKeys { it != 1 }.mapValues { (_, seeds) -> seeds - lying.getValue(1).keys }
        assertIs<Either.Left<IcebergDkg.Fault>>(IcebergDkg.repair(run.params, lost, onlyTheLiar, run.publicShares[lost - 1]))
    }

    @Test
    fun `members shown different commitments publish different transcripts`() {
        val params = IcebergDkg.Params.of(2, hostKeys(4), bytes32())
        val roots = (1..4).associateWith { bytes32() }
        val commitments = (1..4).associateWith { IcebergDkg.round1(params, it, roots.getValue(it)) }
        val honest = IcebergDkg.transcriptHash(params, commitments)
        assertEquals(honest, IcebergDkg.transcriptHash(params, commitments.toList().reversed().toMap()))
        val other = commitments + (2 to IcebergDkg.round1(params, 2, bytes32()))
        assertNotEquals(honest, IcebergDkg.transcriptHash(params, other))
    }
}
