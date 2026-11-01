package com.machankura.dedun.compose.database.model

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import com.vitorpamplona.quartz.nip01Core.core.HexKey

/**
 * One event a signing session signs, and everything about it that cannot be
 * shared with the others.
 *
 * A session signs a *batch*: one ceremony, one signer set, one approval, and `n`
 * events. What separates them is forced by FROST rather than chosen. A Schnorr
 * partial signature is `s = k + e·x` with `e = H(R‖P‖m)`, so two messages under
 * one nonce `R` give two equations in one unknown and the secret share falls
 * out. Every item therefore carries its own nonce material and its own result,
 * and the only things a batch may hold in common are the ones that do not enter
 * that equation -- see [FrostSigningSession].
 *
 * These five columns lived on [FrostSigningSession] until the schema moved to
 * v10, which is why a session created before it reads back as exactly one item
 * at [itemIndex] 0.
 */
@Entity(
    primaryKeys = ["sessionId", "itemIndex"],
    foreignKeys = [
        ForeignKey(
            entity = FrostSigningSession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [
        Index("sessionId"),
    ],
)
data class FrostSigningItem(
    val sessionId: String,

    /**
     * Position in the batch, from zero.
     *
     * Load-bearing rather than cosmetic: it is the order every device joins
     * nonces and partial signatures in, so two devices that disagree about it
     * aggregate against different messages and produce a signature nobody can
     * verify. Fixed by the proposal and never re-sorted.
     *
     * Named `itemIndex` rather than `index` because `index` needs quoting in
     * every hand-written query it appears in, and one missing backtick is a
     * compile error at best.
     */
    val itemIndex: Int,

    /**
     * The unsigned event, as JSON. Kept whole so a member can be shown what
     * they are being asked to sign rather than a hash of it.
     */
    val unsignedEventJson: String,

    /**
     * The event id, which is the 32 bytes actually signed.
     *
     * Recomputed from the event's own fields on arrival, never taken from the
     * proposal. It pins this item to one message -- which is what makes reusing
     * [nonceRandom] safe, exactly as it was when a session signed one event.
     */
    val eventId: HexKey,

    /** Secret. 32 bytes of fresh randomness, the seed for this item's nonce. */
    val nonceRandom: HexKey,

    /**
     * The coordinator's `AggregatedNonce` for this item once it arrives, hex.
     *
     * Written once, and written for every item of the batch in one transaction
     * alongside [FrostSigningSession.signerIds] -- so "some items aggregated" is
     * a state that cannot be reached, and `signerIds != null` is the one gate
     * the rest of the session reads.
     */
    val aggregatedNonce: HexKey? = null,

    /** Result: the finished 64-byte BIP-340 signature over [eventId], hex. */
    val signature: HexKey? = null,

    /**
     * For a [com.machankura.dedun.compose.database.model.types.FrostSigningSubject.TRANSACTION]
     * session, the transaction input this item signs; null for an event.
     *
     * Equal to [itemIndex] whenever it is set -- a transaction's items are its
     * inputs, in input order -- and stored anyway, so that what an item is
     * signing does not depend on a convention two pieces of code have to keep.
     */
    val inputIndex: Int? = null,
) {
    /** Whether the group has produced this item's signature. */
    fun isSigned(): Boolean = signature != null

    /**
     * The 32 bytes this item's nonce is committed to: an event id, or a BIP341
     * sighash. [eventId] under the name that is true of both.
     *
     * An alias and not a renamed column. `eventId` is spelled the same way in
     * thirty files that have nothing to do with this table, and a rename that a
     * search-and-replace cannot tell apart from them is a rename that is not
     * worth the column it would save.
     */
    val message: HexKey get() = eventId

    /**
     * What [message] was computed from: the unsigned event, or the input's place
     * under the wallet's account as `{"change":1,"index":4}`. [unsignedEventJson]
     * under the name that is true of both.
     */
    val payload: String get() = unsignedEventJson
}
