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
import fr.acinq.lightning.DefaultSwapInParams
import fr.acinq.lightning.crypto.SwapInOnChainKeys
import fr.acinq.lightning.transactions.SwapInProtocol
import fr.acinq.phoenixd.conf.LSP

/**
 * The group Lightning wallet's swap-in addresses: `docs/group-lightning-swap-in.md`, D1-D3.
 *
 * ```
 * key path   MuSig2([icebergKey, serverKey])            serverKey = LSP swap-in xpub / perUserPath(nodeId)
 * leaf       <R(b, i)> CHECKSIGVERIFY <refundDelay> CSV
 *
 * I        = HMAC-SHA512("dedun/group-lightning/swap-in-refund/v1", serP(K) || chainHash || ser32(wallet))
 * K_s      = K + I[0:32]·G,   chain code I[32:64]
 * R(b, i)  = CKDpub(CKDpub((K_s, c_s), b), i)       b = the depositor's branch, i = the address index
 * ```
 *
 * The key path is the one the LSP co-signs a swap with, and the group's side of it is the Iceberg key,
 * the same for every address (D1): Iceberg has no derivation, and lightning-kmp's own swap-in keeps one
 * user key too. What makes each address different is the refund leaf, whose key is the room's FROST key
 * stepped like [GroupLightningDerivation]'s basepoints under a tag of its own and then down the
 * depositor's branch (D2, D3). Only a FROST quorum can spend the leaf, and only after the refund delay;
 * the key is never put together anywhere, only tweaked publicly, which is the rule
 * `docs/shared-key-derivation.md` exists for.
 *
 * The branch is [DepositBranch]'s, from the depositor statement, so a coin's address says whose it is to
 * every device that can derive it -- which is every device in the room.
 */
object GroupSwapInDerivation {
    /** Frozen once a coin sits on an address under it: changing it loses every refund key. */
    private const val TAG = "dedun/group-lightning/swap-in-refund/v1"

    private const val MAX_INDEX: Long = 0x7FFFFFFFL

    /** One address's refund key, and the cache a FROST session signs its leaf with (no TapTweak: it is the leaf). */
    class RefundKey internal constructor(val branch: Long, val index: Long, val publicKey: PublicKey, val cache: TweakCache)

    /** One swap-in address: whose branch, which index, and the protocol lightning-kmp spends it under. */
    class Address internal constructor(val branch: Long, val index: Long, val refundKey: RefundKey, val protocol: SwapInProtocol, val address: String)

    /**
     * Everything a group's swap-in addresses are made of, for one Lightning wallet.
     *
     * @param icebergKey the wallet statement's Iceberg key: the group's side of the key path.
     * @param serverKey the LSP's swap-in key for the group's node ([serverKey]).
     */
    class Keys internal constructor(
        val chain: Chain,
        val wallet: Long,
        val icebergKey: PublicKey,
        val serverKey: PublicKey,
        val refundDelay: Int,
        private val step: DeterministicWallet.ExtendedPublicKey,
        private val stepCache: TweakCache,
    ) {
        /** The refund key of [branch]'s address [index]. */
        fun refundKey(branch: Long, index: Long): RefundKey {
            require(branch in 0L..MAX_INDEX && index in 0L..MAX_INDEX) { "($branch, $index) is not a pair of unhardened indices" }
            val (branchKey, branchCache) = child(step, stepCache, branch, "branch $branch")
            val (key, cache) = child(branchKey, branchCache, index, "index $index")
            return RefundKey(branch, index, key.publicKey, cache)
        }

        fun address(branch: Long, index: Long): Address {
            val refund = refundKey(branch, index)
            val protocol = SwapInProtocol(icebergKey, serverKey, refund.publicKey, refundDelay)
            return Address(branch, index, refund, protocol, protocol.address(chain))
        }

        /** [branch]'s addresses `0 … upTo`, in order. */
        fun addresses(branch: Long, upTo: Long): List<Address> = (0L..upTo).map { address(branch, it) }
    }

    /**
     * @param thresholdPublicKey the room's FROST key, which the onchain wallet and the basepoints are too.
     * @param lspSwapInXpub the LSP's published swap-in xpub; [LSP.from] knows it for mainnet and testnet3.
     * @param nodeId the group node's id, from the wallet statement.
     */
    fun keys(
        thresholdPublicKey: HexKey,
        chain: Chain,
        wallet: Long,
        icebergKey: HexKey,
        nodeId: HexKey,
        lspSwapInXpub: String = LSP.from(chain).swapInXpub,
        refundDelay: Int = DefaultSwapInParams.RefundDelay,
    ): Keys {
        require(wallet in 0L..MAX_INDEX) { "wallet $wallet is not an unhardened index" }
        val root = PublicKey(ByteVector(thresholdPublicKey.hexToByteArray()))
        require(root.isValid()) { "the threshold key is not a valid point" }
        val iceberg = PublicKey(ByteVector(icebergKey.hexToByteArray()))
        require(iceberg.isValid()) { "the Iceberg key is not a valid point" }

        val i = Crypto.hmac512(TAG.encodeToByteArray(), root.value.toByteArray() + chain.chainHash.value.toByteArray() + Pack.writeInt32BE(wallet.toInt()))
        val tweak = ByteVector32(i.copyOfRange(0, 32))
        require(Crypto.isPrivKeyValid(tweak.toByteArray())) { "the swap-in step for wallet $wallet landed on an invalid scalar" }
        val stepKey = root + PrivateKey(tweak).publicKey()
        val stepCache = plainTweak(TweakCache.create(root), tweak, "the swap-in step")
        require(stepCache.tweakedPublicKey == stepKey.xOnly()) { "the swap-in cache does not reach the step key" }
        val step = DeterministicWallet.ExtendedPublicKey(publickeybytes = stepKey.value, chaincode = ByteVector32(i.copyOfRange(32, 64)), depth = 0, path = KeyPath.empty, parent = 0L)

        return Keys(chain, wallet, iceberg, serverKey(lspSwapInXpub, PublicKey(ByteVector(nodeId.hexToByteArray()))), refundDelay, step, stepCache)
    }

    /**
     * The LSP's swap-in key for the node [nodeId]: its xpub at [SwapInOnChainKeys.perUserPath], exactly as
     * lightning-kmp's `LocalKeyManager` derives it, since that is the key the LSP signs a swap with.
     */
    fun serverKey(lspSwapInXpub: String, nodeId: PublicKey): PublicKey =
        DeterministicWallet.ExtendedPublicKey.decode(lspSwapInXpub).second.derivePublicKey(SwapInOnChainKeys.perUserPath(nodeId)).publicKey

    private fun child(parent: DeterministicWallet.ExtendedPublicKey, parentCache: TweakCache, index: Long, step: String): Pair<DeterministicWallet.ExtendedPublicKey, TweakCache> {
        val key = parent.derivePublicKey(index)
        val childTweak = ByteVector32(Crypto.hmac512(parent.chaincode.toByteArray(), parent.publickeybytes.toByteArray() + Pack.writeInt32BE(index.toInt())).copyOfRange(0, 32))
        val cache = plainTweak(parentCache, childTweak, step)
        require(cache.tweakedPublicKey == key.publicKey.xOnly()) { "the cache does not reach the refund key's $step" }
        return key to cache
    }

    private fun plainTweak(cache: TweakCache, tweak: ByteVector32, step: String): TweakCache =
        when (val tweaked = cache.tweak(tweak, isXonly = false)) {
            is Either.Left -> throw IllegalArgumentException("Could not apply $step to the group's key", tweaked.value)
            is Either.Right -> tweaked.value.first
        }
}
