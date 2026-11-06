package com.machankura.dedun.compose.wallet.wavelength.group

import com.machankura.dedun.compose.managers.IcebergDkg
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.crypto.Pack
import fr.acinq.bitcoin.crypto.iceberg.Iceberg
import fr.acinq.bitcoin.crypto.iceberg.PublicShare
import fr.acinq.bitcoin.crypto.iceberg.Share
import fr.acinq.bitcoin.utils.Either

/**
 * A VTXO's tree key: a whole Iceberg key of its own, hashed from the group's seeds -- `docs/group-wavelength-wallet.md`, D5.
 *
 * ```
 * s'[T] = H("dedun/group-wavelength/tree-seed/v1", s[T], ser32(child))     for each subset T the member holds
 * ```
 *
 * A tree needs one MuSig2 key per VTXO, and no tweak reaches a key inside someone else's aggregation. Hashing the
 * seeds needs no interaction and keeps the DKG's argument: a seed stays hidden from its subset, so a coalition of
 * `t-1`, which is itself one of the subsets, misses that seed and with it this child. Members compute a child's
 * share alone, and publish its public share for the group to find its key.
 *
 * Frozen once a release holds money. The tag hashes under every one of a wallet's tree keys.
 */
object IcebergChildKeys {
    private const val TAG = "dedun/group-wavelength/tree-seed/v1"
    private const val HEADER = 4
    private const val SEED = 32

    /** This member's share of tree key [child], in the same `version | n | t | index | seeds` encoding as its parent. */
    fun share(parent: Share, child: Long): Share {
        require(child in 0..0x7FFFFFFFL) { "child is out of range" }
        val bytes = parent.toByteArray()
        val n = bytes[1].toInt()
        val t = bytes[2].toInt()
        val index = bytes[3].toInt()
        val suffix = Pack.writeInt32BE(child.toInt())
        val seeds = (HEADER until bytes.size step SEED).map { offset ->
            Crypto.taggedHash(bytes.copyOfRange(offset, offset + SEED) + suffix, TAG)
        }
        return IcebergDkg.share(n, t, index, seeds)
    }

    /** What this member publishes for [child], so that the group can agree on its key. */
    fun publicShare(parent: Share, child: Long): PublicShare = Iceberg.publicShare(share(parent, child), null)

    /** The child's key, from at least `2t-1` public shares, or the failure that says they don't lie on one polynomial. */
    fun key(publicShares: List<PublicShare>, n: Int, t: Int): Either<Throwable, PublicKey> = Iceberg.groupPublicKey(publicShares, n, t)
}
