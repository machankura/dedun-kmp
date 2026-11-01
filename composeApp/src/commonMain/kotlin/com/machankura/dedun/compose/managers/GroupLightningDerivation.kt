package com.machankura.dedun.compose.managers

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.KeyPath
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.crypto.Pack
import fr.acinq.bitcoin.crypto.frost.TweakCache
import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.crypto.GroupBasepoints

/**
 * The two commitment basepoints a group's Lightning wallet holds instead of its operator's node, derived
 * from the group's ChillDKG threshold key -- the same key its on-chain wallet is.
 *
 * ```
 * I   = HMAC-SHA512("bennu/group-lightning/basepoints/v1", serP(K) || chainHash || ser32(wallet))
 * K_b = K + I[0:32]·G,   chain code I[32:64]
 * payment basepoint  = CKDpub((K_b, c_b), 0)
 * delayed basepoint  = CKDpub((K_b, c_b), 1)
 * ```
 *
 * Why these keys, and not the node's: whoever holds the node seed can make the LSP force-close, and in
 * a stock channel the output that pays us after a force close is keyed from that seed. With these
 * basepoints it is keyed from the group's key, so only a FROST quorum moves it. See constraint 7 in
 * `docs/group-lightning-wallet.md`.
 *
 * The shape is [GroupWalletDerivation]'s account step with its own tag, so it is a different key that
 * every member recomputes from what they already hold. It is not an account of the on-chain wallet:
 * these keys sign tapscript leaves, never a key path, so a descriptor that found them would only
 * mislead a watch-only wallet.
 *
 * ### Signing as them
 *
 * Both are script-path keys, used x-only inside a leaf. `to_remote`'s leaf key is the payment basepoint
 * itself; `to_local`'s and an HTLC transaction's is the delayed basepoint plus
 * `SHA256(per_commitment_point || basepoint)·G`. Each is a FROST session over [leafCache] with no
 * TapTweak, because nothing commits a script tree to these keys -- they are the leaf.
 */
object GroupLightningDerivation {
    /** Frozen once a channel exists under it, for the reason [GroupWalletDerivation]'s account tag is. */
    private const val TAG = "bennu/group-lightning/basepoints/v1"

    const val PAYMENT: Long = 0L
    const val DELAYED_PAYMENT: Long = 1L

    private const val MAX_INDEX: Long = 0x7FFFFFFFL

    /** One basepoint: the full point lightning-kmp puts in `open_channel`, and the cache that signs as it. */
    class Basepoint internal constructor(val which: Long, val publicKey: PublicKey, val cache: TweakCache)

    class Basepoints internal constructor(val chain: Chain, val wallet: Long, val payment: Basepoint, val delayedPayment: Basepoint) {
        /** What a group node's channel keys take: [fr.acinq.lightning.crypto.ChannelKeys.withGroupBasepoints]. */
        val forChannel: GroupBasepoints get() = GroupBasepoints(payment.publicKey, delayedPayment.publicKey)
    }

    /**
     * @param wallet the Lightning wallet's number in the room, as its statement's `wallet` tag says; a
     *   re-keyed wallet is the next number, and gets unrelated basepoints.
     */
    fun basepoints(thresholdPublicKey: HexKey, chain: Chain, wallet: Long): Basepoints {
        require(wallet in 0L..MAX_INDEX) { "wallet $wallet is not an unhardened index" }
        val root = PublicKey(ByteVector(thresholdPublicKey.hexToByteArray()))
        require(root.isValid()) { "the threshold key is not a valid point" }

        val i = Crypto.hmac512(TAG.encodeToByteArray(), root.value.toByteArray() + chain.chainHash.value.toByteArray() + Pack.writeInt32BE(wallet.toInt()))
        val tweak = ByteVector32(i.copyOfRange(0, 32))
        require(Crypto.isPrivKeyValid(tweak.toByteArray())) { "the basepoint step for wallet $wallet landed on an invalid scalar" }
        val stepKey = root + PrivateKey(tweak).publicKey()
        val stepCache = plainTweak(TweakCache.create(root), tweak, "the basepoint step")
        require(stepCache.tweakedPublicKey == stepKey.xOnly()) { "the basepoint cache does not reach the step key" }
        val step = DeterministicWallet.ExtendedPublicKey(publickeybytes = stepKey.value, chaincode = ByteVector32(i.copyOfRange(32, 64)), depth = 0, path = KeyPath.empty, parent = 0L)

        fun child(which: Long): Basepoint {
            val key = step.derivePublicKey(which).publicKey
            val childTweak = ByteVector32(Crypto.hmac512(step.chaincode.toByteArray(), step.publickeybytes.toByteArray() + Pack.writeInt32BE(which.toInt())).copyOfRange(0, 32))
            val cache = plainTweak(stepCache, childTweak, "basepoint $which")
            require(cache.tweakedPublicKey == key.xOnly()) { "the cache does not reach basepoint $which" }
            return Basepoint(which, key, cache)
        }
        return Basepoints(chain, wallet, child(PAYMENT), child(DELAYED_PAYMENT))
    }

    /**
     * The cache a FROST session signs a leaf with: [basepoint] itself when [perCommitmentTweak] is null
     * (`to_remote`), or the basepoint plus that tweak (`to_local`, an HTLC transaction's output) --
     * lightning-kmp's `GroupHeldOutputs.Output.tweak`.
     */
    fun leafCache(basepoint: Basepoint, perCommitmentTweak: ByteVector32?): TweakCache =
        if (perCommitmentTweak == null) basepoint.cache else plainTweak(basepoint.cache, perCommitmentTweak, "the per-commitment tweak")

    private fun plainTweak(cache: TweakCache, tweak: ByteVector32, step: String): TweakCache =
        when (val tweaked = cache.tweak(tweak, isXonly = false)) {
            is Either.Left -> throw IllegalArgumentException("Could not apply $step to the group's key", tweaked.value)
            is Either.Right -> tweaked.value.first
        }
}
