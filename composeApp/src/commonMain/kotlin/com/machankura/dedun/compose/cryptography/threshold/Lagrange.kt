package com.machankura.dedun.compose.cryptography.threshold

import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey

/**
 * Lagrange coefficients at zero, over FROST participant ids.
 *
 * A participant id is 0-based, as `KeyMaterial.publicSharesOf` and [com.machankura.dedun.compose.managers.ShareGenerations]
 * index it; the point its share was evaluated at is the id plus one, as BIP 445 says
 * (docs/group-threshold-ecdh.md, Phase 0, check 1).
 */
object Lagrange {
    /**
     * A coefficient that can only scale a point.
     *
     * `Σ λᵢ·sᵢ` over shares is the group's secret, and building it is the thing
     * shared-key-derivation.md forbids. So the scalar stays private, and the only thing a
     * coefficient does is multiply a public point: `λᵢ·Dᵢ`, never `λᵢ·sᵢ`. The compiler
     * keeps it that way, and `ThresholdEcdhJvmTest` reads this package's sources for the
     * rest.
     */
    class Coefficient internal constructor(private val scalar: PrivateKey) {
        fun scale(point: PublicKey): PublicKey = point * scalar

        override fun equals(other: Any?): Boolean = other is Coefficient && other.scalar == scalar
        override fun hashCode(): Int = scalar.hashCode()
        override fun toString(): String = "Coefficient(${scalar.value.toHex()})"
    }

    /**
     * `λ_id = Π_{j ≠ id} xⱼ / (xⱼ − x_id)`, where `x = id + 1`.
     *
     * @throws IllegalArgumentException if [id] is not in [ids], or an id repeats.
     */
    fun coefficient(id: Int, ids: Collection<Int>): Coefficient {
        require(id in ids) { "participant $id is not among $ids" }
        require(ids.size == ids.toSet().size) { "participant ids repeat: $ids" }
        require(ids.all { it >= 0 }) { "participant ids are 0-based: $ids" }

        val xi = Scalars.of(id + 1)
        val lambda = ids.filter { it != id }.fold(Scalars.ONE) { acc, j ->
            val xj = Scalars.of(j + 1)
            acc * xj * Scalars.inverse(xj - xi)
        }
        return Coefficient(lambda)
    }
}
