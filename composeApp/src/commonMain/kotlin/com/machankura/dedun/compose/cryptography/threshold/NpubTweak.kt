package com.machankura.dedun.compose.cryptography.threshold

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey
import com.machankura.dedun.compose.extensions.toHex
import com.machankura.dedun.compose.managers.SharedKeyDerivation

/**
 * How a group's npub's secret relates to its threshold key's, as public values:
 * `d = a·x + b`, with `a ∈ {1, −1}` (D2 of docs/group-threshold-ecdh.md).
 *
 * The npub is the threshold key walked through x-only tweaks, and BIP 327 negates the key
 * before each x-only tweak when its y is odd. So the sign of `x` inside `d` is the product of
 * those negations, and `b` collects the tweaks with the signs that came after them. The
 * library's `TweakCache` is opaque, so this replays the walk rather than reading it.
 *
 * Getting [negate] wrong does not fail loudly: it gives another point, whose x-coordinate
 * opens nothing. Phase 0's check 3 and `ThresholdEcdhJvmTest` pin it against quartz.
 */
class NpubTweak private constructor(
    /** True when `a = −1`. */
    val negate: Boolean,
    /** `b`, or null when no tweak was applied and `b = 0`, which libsecp256k1 cannot hold as a scalar. */
    private val b: PrivateKey?,
    /** The x-only npub the walk reached, 32 bytes as hex. */
    val npub: String,
) {
    /** `d·P`, from `x·P`: `a·(x·P) + b·P`. */
    fun apply(xP: PublicKey, p: PublicKey): PublicKey {
        val signed = if (negate) -xP else xP
        return if (b == null) signed else signed + p * b
    }

    override fun toString(): String = "NpubTweak(negate=$negate, npub=$npub)"

    companion object {
        /**
         * The tweak for the key [SharedKeyDerivation.derive] reaches at [path].
         *
         * @throws IllegalStateException if the replay does not reach the derived key, which
         * would mean D2's signs are wrong and every conversation key with them would be too.
         */
        fun of(thresholdPublicKey: PublicKey, path: List<Long> = SharedKeyDerivation.MARMOT_ADMIN_GROUP_PATH): NpubTweak {
            val derived = SharedKeyDerivation.derive(thresholdPublicKey.value.toByteArray().toHex(), path)
            val tweak = replay(thresholdPublicKey, derived.tweaks)
            check(tweak.npub == derived.hex) { "The tweak replay did not reach the group's npub" }
            return tweak
        }

        internal fun replay(x: PublicKey, tweaks: List<ByteVector32>): NpubTweak {
            var negate = false
            var b: PrivateKey? = null
            var q = x
            tweaks.forEach { scalar ->
                val tau = PrivateKey(scalar)
                if (q.isOdd()) {
                    q = -q
                    negate = !negate
                    b = b?.let { -it }
                }
                q += tau.publicKey()
                b = b?.let { it + tau } ?: tau
            }
            return NpubTweak(negate, b, q.xOnly().value.toHex())
        }
    }
}
