package com.machankura.dedun.compose.cryptography.threshold

import fr.acinq.bitcoin.PrivateKey

/**
 * The public scalars threshold ECDH needs, mod the curve order `n`: small integers, and their
 * inverses. Every operation is a libsecp256k1 call through bitcoin-kmp's [PrivateKey], so
 * nothing here is Kotlin arithmetic on a number, and none of it ever touches a share.
 *
 * Internal to the package. A scalar leaving it would be a scalar some other file could
 * multiply a share by, which is the one thing [Lagrange] is shaped to prevent.
 */
internal object Scalars {
    val ONE: PrivateKey = of(1)

    /** `n − 2`, Fermat's exponent: `v^(n−2) = v⁻¹` in a group of prime order `n`. */
    private const val N_MINUS_2 = "fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd036413f"

    /** [v] as a scalar. Zero is not a scalar libsecp256k1 accepts, and is never asked for. */
    fun of(v: Int): PrivateKey {
        require(v > 0) { "a scalar here is a positive integer, not $v" }
        return PrivateKey(ByteArray(32).also {
            it[28] = (v ushr 24).toByte()
            it[29] = (v ushr 16).toByte()
            it[30] = (v ushr 8).toByte()
            it[31] = v.toByte()
        })
    }

    /**
     * `v⁻¹ mod n`, by square-and-multiply. 256 squarings and about 128 products, each one
     * libsecp256k1 call: slow next to an extended Euclid, and fast next to anything a round
     * waits on. Public values only, so constant time is not the point; using the library's
     * own arithmetic is.
     */
    fun inverse(v: PrivateKey): PrivateKey {
        var result = ONE
        N_MINUS_2.chunked(2).forEach { pair ->
            val byte = pair.toInt(16)
            for (bit in 7 downTo 0) {
                result = result * result
                if ((byte ushr bit) and 1 == 1) result = result * v
            }
        }
        return result
    }
}
