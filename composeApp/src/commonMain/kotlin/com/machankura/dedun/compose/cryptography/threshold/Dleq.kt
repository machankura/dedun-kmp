package com.machankura.dedun.compose.cryptography.threshold

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey
import com.machankura.dedun.compose.extensions.toHex

/**
 * A Chaum-Pedersen proof that `log_G(Yᵢ) = log_P(Dᵢ)`: that a partial was made with the share
 * whose public half the group already knows (D3 of docs/group-threshold-ecdh.md).
 *
 * Without it a wrong partial would make the combined point wrong, NIP-44's MAC would catch
 * that, and nobody could say whose partial it was. With it, a bad partial is dropped and its
 * sender named.
 *
 * ```
 * k = H_nonce(s || P || D || aux) mod n           R₁ = k·G,  R₂ = k·P
 * c = H(context || Y || P || D || R₁ || R₂)       z = k + c·s
 * verify: R₁ = z·G − c·Y,  R₂ = z·P − c·D,  and c recomputes
 * ```
 *
 * The one piece of cryptography the plan writes by hand. Every operation on `s` or `k` is a
 * libsecp256k1 call. A hash at or above `n` is refused rather than reduced: bitcoin-kmp's
 * `PrivateKey` does not range-check (Phase 0, check 4), so [scalarOrNull] does.
 */
object Dleq {
    /**
     * Hash inputs, and so protocol rather than brand: `mantra/` as every tag is
     * (docs/dedun-rebrand.md). Renaming one makes every proof from a peer fail.
     */
    private const val NONCE_TAG = "mantra/threshold-ecdh/dleq-nonce/v1"
    private const val CHALLENGE_TAG = "mantra/threshold-ecdh/dleq/v1"

    data class Proof(val c: ByteVector32, val z: ByteVector32) {
        fun toHex(): String = c.toHex() + z.toHex()

        companion object {
            fun fromHex(hex: String): Proof? {
                if (hex.length != 128 || hex.any { it !in "0123456789abcdef" }) return null
                return Proof(ByteVector32(hex.take(64)), ByteVector32(hex.drop(64)))
            }
        }
    }

    /**
     * What a proof is bound to, so it cannot be replayed into another round: the room, the
     * share generation and the participant id.
     */
    fun context(room: String, generation: Int, id: Int): ByteArray =
        room.encodeToByteArray() + int(generation) + int(id)

    private fun int(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun scalarOrNull(hash: ByteVector32): PrivateKey? = PrivateKey(hash).takeIf { it.isValid() }

    private fun challenge(context: ByteArray, y: PublicKey, p: PublicKey, d: PublicKey, r1: PublicKey, r2: PublicKey): ByteVector32 =
        Crypto.taggedHash(
            context + y.value.toByteArray() + p.value.toByteArray() + d.value.toByteArray() +
                r1.value.toByteArray() + r2.value.toByteArray(),
            CHALLENGE_TAG,
        )

    /**
     * @param aux 32 fresh random bytes, so a fault in one run cannot repeat a nonce in another.
     * @throws IllegalStateException in the ~2⁻¹²⁸ case a hash is not a scalar; try other [aux].
     */
    fun prove(share: PrivateKey, y: PublicKey, p: PublicKey, d: PublicKey, context: ByteArray, aux: ByteArray): Proof {
        require(aux.size == 32) { "aux is 32 bytes" }
        val k = scalarOrNull(
            Crypto.taggedHash(share.value.toByteArray() + p.value.toByteArray() + d.value.toByteArray() + aux, NONCE_TAG)
        ) ?: error("The nonce hash was not a scalar")
        val r1 = k.publicKey()
        val r2 = p * k
        val cHash = challenge(context, y, p, d, r1, r2)
        val c = scalarOrNull(cHash) ?: error("The challenge hash was not a scalar")
        val z = k + c * share
        return Proof(cHash, z.value)
    }

    fun verify(y: PublicKey, p: PublicKey, d: PublicKey, context: ByteArray, proof: Proof): Boolean = try {
        val c = scalarOrNull(proof.c)
        val z = scalarOrNull(proof.z)
        if (c == null || z == null || !y.isValid() || !p.isValid() || !d.isValid()) {
            false
        } else {
            // Either sum may be the point at infinity, which libsecp256k1 refuses to
            // serialise: no valid proof gives one, so the throw is a failure.
            val r1 = z.publicKey() - y * c
            val r2 = p * z - d * c
            challenge(context, y, p, d, r1, r2) == proof.c
        }
    } catch (_: Throwable) {
        false
    }
}
