package com.machankura.dedun.compose.cryptography.threshold

import com.vitorpamplona.crypto.kdf.Hkdf
import fr.acinq.bitcoin.PublicKey
import fr.acinq.secp256k1.Hex

/**
 * Threshold ECDH for a group's npub: `d·P` from `t` holders' partials, and the NIP-44
 * conversation key it gives (docs/group-threshold-ecdh.md, *The arithmetic*).
 *
 * ```
 * x·P = Σ λᵢ·Dᵢ        d·P = a·(x·P) + b·P        convKey = HKDF-extract("nip44-v2", x(d·P))
 * ```
 *
 * The key that comes out is the one any NIP-44 library computes from `d` and `P`, which is
 * the whole of the interoperability argument: a sender's app cannot tell the group's key
 * from a person's. Nothing here holds `x` or `d`; the only secret a device contributes is
 * its own `Dᵢ`, made by [EcdhPartial].
 */
object ThresholdEcdh {
    private val SALT = "nip44-v2".encodeToByteArray()

    /**
     * `d·P` from verified partials, keyed by 0-based participant id. Any `t` or more partials
     * of one generation give the same point; fewer give a point that opens nothing, which a
     * caller cannot tell from here, so it counts before calling.
     */
    fun combine(partials: Map<Int, PublicKey>, tweak: NpubTweak, p: PublicKey): PublicKey {
        require(partials.isNotEmpty()) { "Nothing to combine" }
        val ids = partials.keys
        val xP = partials.entries
            .map { (id, d) -> Lagrange.coefficient(id, ids).scale(d) }
            .reduce { acc, term -> acc + term }
        return tweak.apply(xP, p)
    }

    /** NIP-44's conversation key from the shared point: only its x-coordinate counts. */
    fun conversationKey(point: PublicKey): ByteArray =
        Hkdf().extract(point.value.toByteArray().copyOfRange(1, 33), SALT)

    /** A nostr key, given as 32-byte x-only hex, as a point. Its parity does not matter (D2). */
    fun pointOf(xOnlyHex: String): PublicKey? = try {
        if (xOnlyHex.length != 64) null
        else PublicKey(byteArrayOf(2) + Hex.decode(xOnlyHex)).takeIf { it.isValid() }
    } catch (_: Throwable) {
        null
    }
}
