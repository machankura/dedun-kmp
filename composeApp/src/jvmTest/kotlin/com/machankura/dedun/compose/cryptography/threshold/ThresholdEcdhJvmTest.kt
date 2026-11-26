package com.machankura.dedun.compose.cryptography.threshold

import com.vitorpamplona.quartz.nip44Encryption.Nip44v2
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.crypto.frost.Frost
import fr.acinq.bitcoin.crypto.frost.KeyMaterial
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.machankura.dedun.compose.extensions.toHex
import com.machankura.dedun.compose.managers.SharedKeyDerivation

/**
 * Phase 2 of docs/group-threshold-ecdh.md: the arithmetic, alone, against quartz's NIP-44.
 *
 * The expected key is computed the ordinary way, from a whole `d`, which only a test with a
 * dealer key made for it may build. The code under test never sees `d` or `x`.
 */
class ThresholdEcdhJvmTest {
    private fun dealer(seed: Int, n: Int = 5, t: Int = 3): Pair<PrivateKey, KeyMaterial> {
        val secret = PrivateKey(Crypto.sha256("threshold-ecdh/$seed".encodeToByteArray()))
        return secret to Frost.trustedDealerKeygen(secret, n, t)
    }

    /** The npub's secret, built whole -- here and nowhere else. */
    private fun npubSecret(x: PrivateKey, keys: KeyMaterial): PrivateKey {
        val derived = SharedKeyDerivation.derive(keys.thresholdPublicKey.value.toByteArray().toHex())
        // (a, b) recomputed here, independently of NpubTweak, so the two check each other.
        var q = keys.thresholdPublicKey
        var a = false
        var b: PrivateKey? = null
        derived.tweaks.forEach { s ->
            val tau = PrivateKey(s)
            if (q.isOdd()) { q = -q; a = !a; b = b?.let { -it } }
            q += tau.publicKey(); b = b?.let { it + tau } ?: tau
        }
        val d = (if (a) -x else x) + b!!
        assertEquals(derived.publicKey, d.publicKey().xOnly())
        return d
    }

    private fun subsets(ids: List<Int>, t: Int): List<List<Int>> =
        if (t == 0) listOf(emptyList()) else if (ids.size < t) emptyList()
        else subsets(ids.drop(1), t - 1).map { listOf(ids.first()) + it } + subsets(ids.drop(1), t)

    private fun peer(seed: Int) = PrivateKey(Crypto.sha256("peer/$seed".encodeToByteArray()))

    private fun partials(keys: KeyMaterial, ids: List<Int>, p: PublicKey) =
        ids.associateWith { EcdhPartial.compute(keys.secretShares[it], p) }

    @Test
    fun `every t-subset of a 3-of-5 key gives quartz's conversation key, and so does a larger one`() {
        val (x, keys) = dealer(1)
        val d = npubSecret(x, keys)
        val tweak = NpubTweak.of(keys.thresholdPublicKey)
        val other = peer(1)
        val p = other.publicKey()
        val expected = Nip44v2().computeConversationKey(d.value.toByteArray(), p.xOnly().value.toByteArray())

        (subsets((0..4).toList(), 3) + subsets((0..4).toList(), 4) + listOf((0..4).toList())).forEach { ids ->
            val key = ThresholdEcdh.conversationKey(ThresholdEcdh.combine(partials(keys, ids, p), tweak, p))
            assertContentEquals(expected, key, "subset $ids")
        }
        // The sender's side, as their own app computes it.
        val npub = SharedKeyDerivation.derive(keys.thresholdPublicKey.value.toByteArray().toHex()).publicKey
        assertContentEquals(expected, Nip44v2().computeConversationKey(other.value.toByteArray(), npub.value.toByteArray()))
    }

    @Test
    fun `t minus one partials give a key that does not match`() {
        val (x, keys) = dealer(2)
        val d = npubSecret(x, keys)
        val tweak = NpubTweak.of(keys.thresholdPublicKey)
        val p = peer(2).publicKey()
        val expected = Nip44v2().computeConversationKey(d.value.toByteArray(), p.xOnly().value.toByteArray())
        subsets((0..4).toList(), 2).forEach { ids ->
            assertFalse(expected.contentEquals(ThresholdEcdh.conversationKey(ThresholdEcdh.combine(partials(keys, ids, p), tweak, p))))
        }
    }

    @Test
    fun `the tweak replay matches derive in every parity case`() {
        val seen = mutableSetOf<Pair<Int, Boolean>>()
        (1..40).forEach { seed ->
            val (x, keys) = dealer(seed)
            val tweak = NpubTweak.of(keys.thresholdPublicKey)
            val derived = SharedKeyDerivation.derive(keys.thresholdPublicKey.value.toByteArray().toHex())
            assertEquals(derived.hex, tweak.npub)
            npubSecret(x, keys)
            var q = keys.thresholdPublicKey
            derived.tweaks.forEachIndexed { step, s ->
                seen += step to q.isEven()
                if (q.isOdd()) q = -q
                q += PrivateKey(s).publicKey()
            }
            // And the combined point lands for this seed too.
            val p = peer(seed).publicKey()
            val expected = Nip44v2().computeConversationKey(npubSecret(x, keys).value.toByteArray(), p.xOnly().value.toByteArray())
            assertContentEquals(expected, ThresholdEcdh.conversationKey(ThresholdEcdh.combine(partials(keys, listOf(0, 2, 4), p), tweak, p)))
        }
        assertEquals(6, seen.size, "both parities at every step: $seen")
    }

    @Test
    fun `P given as x-only lifts to the same key whichever parity is chosen`() {
        val (x, keys) = dealer(3)
        val tweak = NpubTweak.of(keys.thresholdPublicKey)
        val p = peer(3).publicKey()
        val lifted = ThresholdEcdh.pointOf(p.xOnly().value.toHex())!!
        val even = ThresholdEcdh.conversationKey(ThresholdEcdh.combine(partials(keys, listOf(0, 1, 2), lifted), tweak, lifted))
        val odd = ThresholdEcdh.conversationKey(ThresholdEcdh.combine(partials(keys, listOf(0, 1, 2), -lifted), tweak, -lifted))
        assertContentEquals(even, odd)
        assertContentEquals(
            Nip44v2().computeConversationKey(npubSecret(x, keys).value.toByteArray(), p.xOnly().value.toByteArray()),
            even,
        )
    }

    @Test
    fun `Lagrange coefficients over shifted ids rebuild the dealer's point`() {
        val (_, keys) = dealer(4)
        subsets((0..4).toList(), 3).forEach { ids ->
            val rebuilt = ids.map { Lagrange.coefficient(it, ids).scale(keys.publicShares[it]) }.reduce { a, b -> a + b }
            assertEquals(keys.thresholdPublicKey, rebuilt)
        }
        assertFailsWith<IllegalArgumentException> { Lagrange.coefficient(5, listOf(0, 1, 2)) }
        assertFailsWith<IllegalArgumentException> { Lagrange.coefficient(1, listOf(1, 1, 2)) }
    }

    @Test
    fun `an inverse is an inverse`() {
        (1..64).forEach { assertEquals(Scalars.ONE, Scalars.of(it) * Scalars.inverse(Scalars.of(it))) }
        val big = PrivateKey(Crypto.sha256("big".encodeToByteArray()))
        assertEquals(Scalars.ONE, big * Scalars.inverse(big))
    }

    // --- D3: the proof

    private val aux = ByteArray(32) { 9 }

    private data class Case(val share: PrivateKey, val y: PublicKey, val p: PublicKey, val d: PublicKey, val context: ByteArray)

    private fun case(): Case {
        val (_, keys) = dealer(5)
        val p = peer(5).publicKey()
        val share = keys.secretShares[1]
        return Case(share, keys.publicShares[1], p, EcdhPartial.compute(share, p), Dleq.context("a".repeat(64), 3, 1))
    }

    @Test
    fun `a proof verifies`() {
        val c = case()
        val proof = Dleq.prove(c.share, c.y, c.p, c.d, c.context, aux)
        assertTrue(Dleq.verify(c.y, c.p, c.d, c.context, proof))
        assertEquals(proof, Dleq.Proof.fromHex(proof.toHex()))
        // Fresh aux, another proof, still valid.
        val again = Dleq.prove(c.share, c.y, c.p, c.d, c.context, ByteArray(32) { 1 })
        assertFalse(again == proof)
        assertTrue(Dleq.verify(c.y, c.p, c.d, c.context, again))
    }

    @Test
    fun `a proof fails for another point, generation, id, partial, public share, or a changed c or z`() {
        val c = case()
        val proof = Dleq.prove(c.share, c.y, c.p, c.d, c.context, aux)
        val otherP = peer(99).publicKey()
        assertFalse(Dleq.verify(c.y, otherP, c.d, c.context, proof), "another point")
        assertFalse(Dleq.verify(c.y, c.p, c.d, Dleq.context("a".repeat(64), 4, 1), proof), "another generation")
        assertFalse(Dleq.verify(c.y, c.p, c.d, Dleq.context("a".repeat(64), 3, 2), proof), "another id")
        assertFalse(Dleq.verify(c.y, c.p, c.d, Dleq.context("b".repeat(64), 3, 1), proof), "another room")
        assertFalse(Dleq.verify(c.y, c.p, c.d + c.p, c.context, proof), "another partial")
        assertFalse(Dleq.verify(c.y + c.p, c.p, c.d, c.context, proof), "another public share")
        assertFalse(Dleq.verify(c.y, c.p, c.d, c.context, proof.copy(z = proof.c)), "z swapped")
        assertFalse(Dleq.verify(c.y, c.p, c.d, c.context, Dleq.Proof(proof.z, proof.c)), "c and z swapped")
        val flipped = proof.c.toByteArray().also { it[31] = (it[31].toInt() xor 1).toByte() }
        assertFalse(Dleq.verify(c.y, c.p, c.d, c.context, proof.copy(c = ByteVector32(flipped))), "c changed")
        assertFalse(Dleq.verify(c.y, c.p, c.d, c.context, proof.copy(z = ByteVector32("ff".repeat(32)))), "z out of range")
    }

    @Test
    fun `a wrong partial with an honest-looking proof is caught`() {
        val c = case()
        // A holder who answers with another scalar cannot prove it against their public share.
        val forged = EcdhPartial.compute(PrivateKey(Crypto.sha256("forger".encodeToByteArray())), c.p)
        val proof = Dleq.prove(c.share, c.y, c.p, forged, c.context, aux)
        assertFalse(Dleq.verify(c.y, c.p, forged, c.context, proof))
    }

    // --- shared-key-derivation.md's rule, as a test

    @Test
    fun `nothing in the package can build the group's secret`() {
        val root = File("src").takeIf { it.resolve("commonMain").isDirectory } ?: File("composeApp/src")
        val sources = root.resolve("commonMain/kotlin/com/machankura/dedun/compose/cryptography/threshold")
            .listFiles { f -> f.extension == "kt" }!!.associate { it.name to it.readText() }
        assertTrue(sources.isNotEmpty())
        sources.forEach { (name, text) ->
            // A list of every share is a dealer's, never a device's.
            assertFalse("secretShares" in text, "$name reads a list of shares")
            // A Lagrange coefficient leaves the class only through scale(point).
            if (name != "Lagrange.kt") assertFalse(Regex("""Coefficient\(""").containsMatchIn(text), "$name builds a coefficient")
        }
        // The coefficient's scalar is private: scale(point) is its only use.
        assertTrue("class Coefficient internal constructor(private val scalar: PrivateKey)" in sources.getValue("Lagrange.kt"))
    }
}
