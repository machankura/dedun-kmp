package com.machankura.dedun.compose.wallet.wavelength.group

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.KeyPath
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.byteVector32
import fr.acinq.bitcoin.crypto.Pack
import fr.acinq.bitcoin.crypto.musig2.KeyAggCache
import fr.acinq.bitcoin.utils.getOrElse

/**
 * The keys of a group's Wavelength wallet, derived from its Iceberg key -- `docs/group-wavelength-wallet.md`, D4.
 *
 * ```
 * Q   = KeyAgg([P])                                the group's key, alone in a MuSig2 aggregation
 * I   = HMAC-SHA512("dedun/group-wavelength/account/v1", ser(Q) || chainHash || ser32(wallet) || ser32(slot))
 * Q_s = Q + I[0:32]·G,  chain code I[32:64]        an xpub, one for each slot the daemon's manifest asks for
 * ```
 *
 * Below a slot the daemon derives public children itself, and each step reaches Iceberg as an outer tweak on
 * the aggregation's cache, which the module handles and takes no input on. So nothing here touches a share,
 * and every member recomputes all of it from the group's key.
 *
 * The identity family isn't here: it signs too often for a quorum and comes from the authentication seed
 * instead ([GroupArkAuth]). The tree family's slot exists only because a manifest has to fill it; a tree's keys
 * are [IcebergChildKeys], since no tweak can reach a key inside someone else's MuSig2 aggregation.
 *
 * Frozen once a release holds money: moving a tag or a slot number moves where the coins are.
 */
object GroupArkDerivation {
    private const val TAG = "dedun/group-wavelength/account/v1"

    /** A fund slot of the daemon's manifest: the on-chain account, or a key family (`lib/keyfamily/keyfamily.go`). */
    enum class Slot(val number: Long, val family: Long?) {
        ONCHAIN(0L, null),
        BOARDING(42L, 42L),
        VTXO_OWNER(44L, 44L),
        VTXO_TREE(45L, 45L),
        OOR_RECEIVE(202L, 202L),
    }

    /** One slot: the xpub the daemon derives from, its origin path as the manifest states it, and the cache that signs as it. */
    class Account internal constructor(val slot: Slot, val xpub: DeterministicWallet.ExtendedPublicKey, val cache: KeyAggCache)

    /** One key below a slot: what a daemon's `KeyLocator` names. */
    class Child internal constructor(val publicKey: PublicKey, val cache: KeyAggCache)

    /** `m/86'/0'/0'` on every network, because btcwallet's on-chain scopes hard-code it; `m/1017'/coin'/family'` for the rest. */
    private fun originOf(slot: Slot, chain: Chain): KeyPath = when (val family = slot.family) {
        null -> KeyPath(listOf(DeterministicWallet.hardened(86), DeterministicWallet.hardened(0), DeterministicWallet.hardened(0)))
        else -> KeyPath(listOf(DeterministicWallet.hardened(1017), DeterministicWallet.hardened(coinType(chain)), DeterministicWallet.hardened(family)))
    }

    /** lnd's key families follow the network, unlike the on-chain scope. */
    fun coinType(chain: Chain): Long = if (chain.isMainnet()) 0L else 1L

    /** The group's key alone in a MuSig2 aggregation, and the full point that makes, which an xpub needs and the x-only result doesn't give. */
    private fun rootOf(icebergKey: PublicKey): Pair<KeyAggCache, PublicKey> {
        val (_, cache) = KeyAggCache.create(listOf(icebergKey))
        return cache to cache.tweak(ByteVector32.Zeroes, isXonly = false).getOrElse { throw it }.second
    }

    fun account(icebergKey: PublicKey, chain: Chain, wallet: Long, slot: Slot): Account {
        require(wallet in 0..MAX_INDEX) { "wallet is out of range" }
        val (root, q) = rootOf(icebergKey)
        val i = Crypto.hmac512(
            TAG.encodeToByteArray(),
            q.value.toByteArray() + chain.chainHash.value.toByteArray() + Pack.writeInt32BE(wallet.toInt()) + Pack.writeInt32BE(slot.number.toInt()),
        )
        val (cache, key) = root.tweak(i.copyOfRange(0, 32).byteVector32(), isXonly = false).getOrElse { throw it }
        val origin = originOf(slot, chain)
        return Account(slot, DeterministicWallet.ExtendedPublicKey(key.value, i.copyOfRange(32, 64).byteVector32(), origin.path.size, origin, 0L), cache)
    }

    /** `/branch/index` below [account]: what the daemon derives from the xpub, with the cache that signs as it. */
    fun child(account: Account, branch: Long, index: Long): Child {
        var extended = account.xpub
        var cache = account.cache
        for (step in listOf(branch, index)) {
            val tweak = Crypto.hmac512(extended.chaincode.toByteArray(), extended.publickeybytes.toByteArray() + Pack.writeInt32BE(step.toInt())).copyOfRange(0, 32).byteVector32()
            val next = extended.derivePublicKey(step)
            val (nextCache, nextKey) = cache.tweak(tweak, isXonly = false).getOrElse { throw it }
            check(nextKey == next.publicKey) { "the cache and the xpub disagree on a child" }
            cache = nextCache
            extended = next
        }
        return Child(extended.publicKey, cache)
    }

    /** [child]'s cache under BIP86's taproot tweak, which is how the on-chain account's key path signs. */
    fun bip86Cache(child: Child): KeyAggCache =
        child.cache.tweak(child.publicKey.xOnly().tweak(Crypto.TaprootTweak.KeyPathTweak), isXonly = true).getOrElse { throw it }.first

    private const val MAX_INDEX: Long = 0x7FFFFFFFL
}
