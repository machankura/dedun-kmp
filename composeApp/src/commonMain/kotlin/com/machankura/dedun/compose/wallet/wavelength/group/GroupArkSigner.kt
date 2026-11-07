package com.machankura.dedun.compose.wallet.wavelength.group

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.ByteVector64
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.XonlyPublicKey
import fr.acinq.bitcoin.crypto.iceberg.Iceberg
import fr.acinq.bitcoin.crypto.iceberg.NonceContribution
import fr.acinq.bitcoin.crypto.iceberg.Share
import fr.acinq.bitcoin.crypto.iceberg.SignatureShare
import fr.acinq.bitcoin.crypto.musig2.AggregatedNonce
import fr.acinq.bitcoin.crypto.musig2.IndividualNonce
import fr.acinq.bitcoin.crypto.musig2.KeyAggCache
import fr.acinq.bitcoin.crypto.musig2.Session
import fr.acinq.bitcoin.utils.Either
import fr.acinq.bitcoin.utils.getOrElse
import fr.acinq.lightning.transactions.Scripts
import fr.acinq.secp256k1.Hex

/**
 * Both rounds of a group's signature in a Wavelength wallet, as pure functions over a share and a label --
 * `docs/group-wavelength-wallet.md`, D4 and D5. Who gets asked, and whether they answer, is the co-signer's.
 *
 * A plain signature has the group alone in its aggregation: the owner keys, the boarding keys, the on-chain account.
 * A tree signature has the group's child key beside every other cosigner on the node, where Ark's operator is the one
 * that matters.
 *
 * **One label answers one message, once.** Two answers under a label are two equations in a member's three secrets,
 * and three recover its share, with no error from anywhere. The library holds nothing between calls, so every answer
 * here goes through a [LabelLedger], and a label it has seen is refused whatever the message.
 */
object GroupArkSigner {
    /** No cosigners: 33 zero bytes twice is how MuSig2's extended encoding writes infinity. */
    val NO_COSIGNERS: AggregatedNonce = AggregatedNonce(ByteArray(66))

    /** A member's durable record of the labels it has answered, which is never pruned and must not be rolled back. */
    fun interface LabelLedger {
        /** True the first time [label] is claimed, and false ever after. */
        suspend fun claim(label: ByteVector32, message: ByteVector32): Boolean
    }

    class LabelAlreadyAnswered(label: ByteVector32) : IllegalStateException("label ${Hex.encode(label.toByteArray())} was already answered")

    /** Round one: depends on nothing but the share and the label, so it can be published long before there is a message. */
    fun contribution(share: Share, label: ByteVector32): NonceContribution = Iceberg.generateNonce(share, null, label)

    /** The group's one ordinary MuSig2 nonce, from at least `2t-1` contributions. */
    fun groupNonce(contributions: List<NonceContribution>, n: Int, t: Int, groupKey: PublicKey): Either<Throwable, IndividualNonce> =
        Iceberg.aggregateNonces(contributions, n, t, groupKey)

    /** Round two, once this member's checks have passed and the echo rule is met. */
    suspend fun answer(
        ledger: LabelLedger,
        share: Share,
        label: ByteVector32,
        contributions: List<NonceContribution>,
        groupKey: PublicKey,
        cache: KeyAggCache,
        message: ByteVector32,
        cosignerNonce: AggregatedNonce = NO_COSIGNERS,
    ): Either<Throwable, SignatureShare> {
        if (!ledger.claim(label, message)) return Either.Left(LabelAlreadyAnswered(label))
        return Iceberg.partialSign(share, null, label, contributions, groupKey, cache, message, cosignerNonce)
    }

    /** `t` or more shares become the group's one signature, for a key the group holds alone. */
    fun plainSignature(shares: List<SignatureShare>, n: Int, t: Int, groupNonce: IndividualNonce, cache: KeyAggCache, message: ByteVector32): Either<Throwable, ByteVector64> =
        when (val partial = Iceberg.aggregatePartialSignatures(shares, n, t)) {
            is Either.Left -> Either.Left(partial.value)
            is Either.Right -> try {
                val nonce = when (val aggregated = IndividualNonce.aggregate(listOf(groupNonce))) {
                    is Either.Left -> return Either.Left(aggregated.value)
                    is Either.Right -> aggregated.value
                }
                Session.create(nonce, message, cache).aggregateSigs(listOf(partial.value))
            } catch (e: Throwable) {
                Either.Left(e)
            }
        }

    /** `t` or more shares become the group's partial signature in a tree node's MuSig2 session, for the daemon to return. */
    fun treePartialSignature(shares: List<SignatureShare>, n: Int, t: Int): Either<Throwable, ByteVector32> =
        Iceberg.aggregatePartialSignatures(shares, n, t)

    /**
     * A tree node's aggregation as `Node.NewSignerSession` makes one: lnd's MuSig2 sorts the cosigners, and the sweep
     * root goes on as a taproot tweak.
     */
    fun treeCache(cosigners: List<PublicKey>, sweepRoot: ByteVector32): Pair<KeyAggCache, XonlyPublicKey> {
        val sorted = Scripts.sort(cosigners)
        val (internal, cache) = KeyAggCache.create(sorted)
        val (tweaked, outputKey) = cache.tweak(internal.tweak(Crypto.TaprootTweak.ScriptPathTweak(sweepRoot)), isXonly = true).getOrElse { throw it }
        return tweaked to outputKey.xOnly()
    }
}
