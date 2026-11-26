package com.machankura.dedun.compose.cryptography.threshold

import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey

/**
 * One holder's part of `x·P`: `Dᵢ = sᵢ·P`.
 *
 * A Diffie-Hellman value, so it says nothing about `sᵢ`, and fewer than `t` of them say
 * nothing about `x·P`. The one scalar multiplication by a share in the whole scheme, and it
 * is libsecp256k1's.
 *
 * [point] has to be a key whose owner the caller has seen sign something (D5): a point
 * nobody can sign for may be `x^k·G`, and answering those is how a Diffie-Hellman oracle is
 * worn down. This function cannot check that; `Evidence` does, before it is called.
 */
object EcdhPartial {
    fun compute(share: PrivateKey, point: PublicKey): PublicKey {
        require(point.isValid()) { "Not a point on the curve" }
        return point * share
    }
}
