package com.machankura.dedun.compose

import com.vitorpamplona.crypto.kdf.Hkdf
import com.vitorpamplona.quartz.nip44Encryption.Nip44v2
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.crypto.frost.Frost
import fr.acinq.bitcoin.crypto.frost.KeyMaterial
import com.machankura.dedun.compose.extensions.toHex
import com.machankura.dedun.compose.managers.SharedKeyDerivation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 0 of docs/group-threshold-ecdh.md: the six facts the plan rests on, checked against
 * the real libraries before anything is built over them.
 *
 * This is the one file allowed to build the group's secret whole. Every key in it is a
 * throwaway made by `Frost.trustedDealerKeygen` for the test, and building `x` and `d` is the
 * only way to show that the threshold arithmetic lands where single-key NIP-44 does. The app's
 * own code never does (shared-key-derivation.md's rule), and Phase 2's test enforces that.
 */
class ThresholdEcdhPremiseJvmTest {
    private val one = scalar(1)

    private fun scalar(v: Int): PrivateKey = PrivateKey(ByteArray(32).also {
        it[28] = (v ushr 24).toByte(); it[29] = (v ushr 16).toByte(); it[30] = (v ushr 8).toByte(); it[31] = v.toByte()
    })

    /** `n − 2`, the exponent of Fermat's inverse in the group of order `n`. */
    private val nMinus2 = "fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd036413f"

    private fun inverse(v: PrivateKey): PrivateKey {
        var result = one
        nMinus2.chunked(2).map { it.toInt(16) }.forEach { byte ->
            for (bit in 7 downTo 0) {
                result = result * result
                if ((byte ushr bit) and 1 == 1) result = result * v
            }
        }
        return result
    }

    /** λᵢ at zero over the evaluation points [xs], each a holder's id plus one (check 1). */
    private fun lagrange(xi: Int, xs: List<Int>): PrivateKey =
        xs.filter { it != xi }.fold(one) { acc, xj -> acc * scalar(xj) * inverse(scalar(xj) - scalar(xi)) }

    private fun dealer(seed: Int, n: Int = 5, t: Int = 3): Pair<PrivateKey, KeyMaterial> {
        val secret = PrivateKey(Crypto.sha256("threshold-ecdh-premise/$seed".encodeToByteArray()))
        return secret to Frost.trustedDealerKeygen(secret, n, t)
    }

    private fun subsets(ids: List<Int>, t: Int): List<List<Int>> =
        if (t == 0) listOf(emptyList()) else if (ids.size < t) emptyList()
        else subsets(ids.drop(1), t - 1).map { listOf(ids.first()) + it } + subsets(ids.drop(1), t)

    /** D2, replayed: `(a, b)` with `d = a·x + b`, and the point the replay reaches. */
    private data class Replay(val aNegative: Boolean, val b: PrivateKey?, val q: PublicKey, val parities: List<Boolean>)

    private fun replay(x: PublicKey, tweaks: List<ByteVector32>): Replay {
        var aNegative = false
        var b: PrivateKey? = null
        var q = x
        val parities = mutableListOf<Boolean>()
        tweaks.forEach { tau ->
            val even = q.isEven()
            parities += even
            val t = PrivateKey(tau)
            if (!even) {
                q = -q
                aNegative = !aNegative
                b = b?.let { -it }
            }
            q += t.publicKey()
            b = b?.let { it + t } ?: t
        }
        return Replay(aNegative, b, q, parities)
    }

    private fun conversationKey(point: PublicKey): ByteArray =
        Hkdf().extract(point.value.toByteArray().copyOfRange(1, 33), "nip44-v2".encodeToByteArray())

    @Test
    fun `check 1 - the Shamir evaluation point is the id plus one`() {
        val (secret, keys) = dealer(1)
        subsets((0 until 5).toList(), 3).forEach { ids ->
            val xs = ids.map { it + 1 }
            val rebuilt = ids.map { id -> keys.secretShares[id] * lagrange(id + 1, xs) }.reduce { a, b -> a + b }
            assertEquals(keys.thresholdPublicKey, rebuilt.publicKey(), "subset $ids")
            assertEquals(secret.publicKey(), rebuilt.publicKey())
        }
        // The id itself, unshifted, does not reconstruct: the convention is not a no-op.
        val wrong = listOf(0, 1, 2).map { id -> keys.secretShares[id] * lagrange(id + 1, listOf(1, 2, 3).map { it + 1 }) }
            .reduce { a, b -> a + b }
        assertTrue(wrong.publicKey() != keys.thresholdPublicKey)
    }

    @Test
    fun `check 2 - replaying the tweaks with their signs reaches the npub, in every parity case`() {
        val seen = mutableSetOf<Pair<Int, Boolean>>()
        (1..40).forEach { seed ->
            val (secret, keys) = dealer(seed)
            val derived = SharedKeyDerivation.derive(keys.thresholdPublicKey.value.toByteArray().toHex())
            val r = replay(keys.thresholdPublicKey, derived.tweaks)
            assertEquals(derived.publicKey, r.q.xOnly(), "seed $seed")
            // And the scalar side: d = a·x + b has the npub as its public key.
            val d = (if (r.aNegative) -secret else secret) + r.b!!
            assertEquals(derived.publicKey, d.publicKey().xOnly(), "seed $seed")
            r.parities.forEachIndexed { step, even -> seen += step to even }
        }
        assertEquals(6, seen.size, "every step met both parities somewhere in the seeds: $seen")
    }

    @Test
    fun `check 3 - the combined point gives quartz's own conversation key`() {
        (1..12).forEach { seed ->
            val (secret, keys) = dealer(seed)
            val derived = SharedKeyDerivation.derive(keys.thresholdPublicKey.value.toByteArray().toHex())
            val r = replay(keys.thresholdPublicKey, derived.tweaks)
            val d = (if (r.aNegative) -secret else secret) + r.b!!

            val other = PrivateKey(Crypto.sha256("peer/$seed".encodeToByteArray()))
            val p = other.publicKey()
            val expected = Nip44v2().computeConversationKey(d.value.toByteArray(), p.xOnly().value.toByteArray())
            // And from the other side, as a sender's app would compute it.
            val fromPeer = Nip44v2().computeConversationKey(other.value.toByteArray(), derived.publicKey.value.toByteArray())
            assertEquals(expected.toHex(), fromPeer.toHex())

            subsets((0 until 5).toList(), 3).forEach { ids ->
                val xs = ids.map { it + 1 }
                val xP = ids.map { id -> (p * keys.secretShares[id]) * lagrange(id + 1, xs) }.reduce { a, b -> a + b }
                val dP = (if (r.aNegative) -xP else xP) + p * r.b!!
                assertEquals(expected.toHex(), conversationKey(dP).toHex(), "seed $seed, subset $ids")
                // Either lift of P gives the same x-coordinate.
                val xPNeg = ids.map { id -> ((-p) * keys.secretShares[id]) * lagrange(id + 1, xs) }.reduce { a, b -> a + b }
                val dPNeg = (if (r.aNegative) -xPNeg else xPNeg) + (-p) * r.b!!
                assertEquals(expected.toHex(), conversationKey(dPNeg).toHex())
            }
            // The sign inside the sum matters: the wrong a gives a different key.
            val xP = listOf(0, 1, 2).map { id -> (p * keys.secretShares[id]) * lagrange(id + 1, listOf(1, 2, 3)) }.reduce { a, b -> a + b }
            val wrongSign = (if (r.aNegative) xP else -xP) + p * r.b!!
            assertFalse(expected.contentEquals(conversationKey(wrongSign)))
        }
    }

    @Test
    fun `check 4 - scalars reduce mod n, the inverse is Fermat's, and the constructor does not range-check`() {
        val nMinus1 = -one
        assertEquals(one, nMinus1 * nMinus1)
        (2..40).forEach { assertEquals(one, scalar(it) * inverse(scalar(it))) }
        assertEquals(scalar(5), scalar(2) + scalar(3))
        // A hash at or above n is NOT rejected by the constructor; isValid() is the check D3 needs.
        val n = PrivateKey(ByteVector32("fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141"))
        assertFalse(n.isValid())
        assertFalse(PrivateKey(ByteVector32("ff".repeat(32))).isValid())
    }

    @Test
    fun `check 6 - a batch of partials fits a relay's event when the payload is encrypted once`() {
        val nip44 = Nip44v2()
        val key = ByteArray(32) { 7 }
        // A wrap of a 1,000-character message, as evidence: rumor, seal and wrap layers.
        val rumor = """{"kind":14,"content":"${"x".repeat(1000)}","tags":[["p","${"a".repeat(64)}"]],"pubkey":"${"b".repeat(64)}","created_at":1700000000,"id":"${"c".repeat(64)}"}"""
        val seal = """{"kind":13,"content":"${nip44.encrypt(rumor, key).encodePayload()}","tags":[],"pubkey":"${"b".repeat(64)}","created_at":1700000000,"id":"${"c".repeat(64)}","sig":"${"d".repeat(128)}"}"""
        val wrap = """{"kind":1059,"content":"${nip44.encrypt(seal, key).encodePayload()}","tags":[["p","${"a".repeat(64)}"]],"pubkey":"${"b".repeat(64)}","created_at":1700000000,"id":"${"c".repeat(64)}","sig":"${"d".repeat(128)}"}"""
        val item = """{"point":"${"02" + "e".repeat(64)}","purpose":"wrap","evidence":$wrap,"partial":"${"02" + "f".repeat(64)}","proof":"${"1".repeat(128)}"}"""
        val bare = """{"point":"${"02" + "e".repeat(64)}","purpose":"wrap","partial":"${"02" + "f".repeat(64)}","proof":"${"1".repeat(128)}"}"""

        fun sealedPerAdmin(items: List<String>, admins: Int): Int {
            val payload = """{"v":1,"generation":1,"from":0,"items":[${items.joinToString(",")}]}"""
            return admins * nip44.encrypt(payload, key).encodePayload().length
        }

        fun sealedOnce(items: List<String>, admins: Int): Int {
            val payload = """{"v":1,"generation":1,"from":0,"items":[${items.joinToString(",")}]}"""
            return nip44.encrypt(payload, key).encodePayload().length + admins * nip44.encrypt("f".repeat(64), key).encodePayload().length
        }

        println("wrap as evidence: ${wrap.length} bytes")
        println("20 items with evidence, 7 admins, per admin: ${sealedPerAdmin(List(20) { item }, 7)}")
        println("20 items with evidence, 7 admins, once:      ${sealedOnce(List(20) { item }, 7)}")
        println("20 bare items, 7 admins, once:               ${sealedOnce(List(20) { bare }, 7)}")
        // The plan as first written -- each payload sealed per admin, the wrap carried as
        // evidence -- is fourteen times a relay's 64 KB at this size.
        assertTrue(sealedPerAdmin(List(20) { item }, 7) > 65_536)
        // Carried once, evidence still outgrows a relay's event: NIP-44 pads to the next
        // power of two, and one wrap of a long message is most of 64 KB on its own.
        assertTrue(sealedOnce(List(20) { item }, 7) > 65_536)
        // So the payload is encrypted once under a key sealed per admin, and a wrap or a seal
        // travels by reference (every admin's device subscribes to the same wraps, and opens
        // the same seals). Twenty bare items for seven admins fit in 16 KB.
        assertTrue(sealedOnce(List(20) { bare }, 7) < 16_384)
    }

    @Test
    fun `check 5 - kinds 30400 and 30401 are free here`() {
        // Dedun's own block is 30420-30442 since the second batch's Phase 4, and neither number
        // is the legacy one of a kind it moved, so nothing of Dedun's reads them.
        for (kind in listOf(30400, 30401)) {
            assertFalse(kind in 30420..30442)
            assertFalse(com.machankura.dedun.compose.nostr.MovedKinds.current(kind) != kind)
            assertFalse(kind in com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthEvents.ALL)
        }
    }
}
