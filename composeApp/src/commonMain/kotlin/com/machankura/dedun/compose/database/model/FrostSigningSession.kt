package com.machankura.dedun.compose.database.model

import androidx.room3.ColumnInfo
import com.machankura.dedun.compose.database.model.types.FrostSigningSubject
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlin.time.Clock
import kotlin.time.Instant
import com.machankura.dedun.compose.database.model.traits.LocalStoreEntity
import com.machankura.dedun.compose.database.model.traits.TimestampedEntity
import com.machankura.dedun.compose.database.model.types.FrostSigningStage
import com.machankura.dedun.compose.managers.SharedKeyDerivation

/**
 * One FROST signing session, as this device sees it.
 *
 * Like [DkgSession] this stores *inputs* rather than protocol state, so a
 * session survives the app being killed halfway through: every step is
 * recomputed from what is on the row. Unlike a DKG that choice is not merely
 * convenient here, it is forced -- `fr.acinq.bitcoin.crypto.frost.SecretNonce`
 * cannot be serialised and refuses to be used twice, by design.
 *
 * ### One session, several events
 *
 * A session signs a batch of one or more events, held as [FrostSigningItem]
 * rows. What lives here is what the whole batch shares, and the split is not a
 * matter of taste: an item's nonce, message and signature are the terms of
 * `s = k + e·x` with `e = H(R‖P‖m)`, so sharing any of them across two messages
 * is how a secret share is extracted. Everything on this row is outside that
 * equation.
 *
 * | shared, and so here | per item, and so on [FrostSigningItem] |
 * |---|---|
 * | ceremony, threshold, participant count | the unsigned event and its id |
 * | [signerIds] and the signer set | nonce seed, and the nonce from it |
 * | [derivationPath] and its tweak cache | aggregated nonce |
 * | [signApprovedAt] or [signDeclinedAt] -- one decision | the signature |
 *
 * ### The nonce, and why one item means one message
 *
 * [FrostSigningItem.nonceRandom] is secret, and regenerating this device's nonce
 * from it is safe for exactly one reason: an item signs one message and can
 * never be made to sign another. `SecretNonce.generate` mixes the message in, so
 * the same randomness under a different message would be a different nonce --
 * but the same randomness under the same message with two *different* aggregated
 * nonces would produce two partial signatures over one secret nonce, which is
 * how a secret share is extracted.
 *
 * Two rules keep that impossible, and both are load-bearing:
 *
 *  - [FrostSigningItem.eventId] is written when the session is created, and a
 *    proposal that disagrees with the batch it already holds is rejected rather
 *    than applied.
 *  - [FrostSigningItem.aggregatedNonce] and [signerIds] are written once, and
 *    written together. A second, different signer set for the same session is
 *    ignored, not honoured.
 */
@Entity(
    foreignKeys = [
        ForeignKey(
            entity = ChatRoom::class,
            parentColumns = ["id"],
            childColumns = ["chatRoomId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [
        Index("chatRoomId"),
        Index("dkgSessionId"),
    ],
)
data class FrostSigningSession(
    /** Minted by the proposer, carried on every message as `frost_session`. */
    @PrimaryKey
    val id: String,

    val chatRoomId: String,

    /** The member who proposed the signature, who also aggregates for it. */
    val coordinatorPublicKey: HexKey,

    /** Whose device this row belongs to, for multi-account support. */
    val userPublicKey: HexKey,

    /** The ceremony whose key this signs with — a group may hold more than one. */
    val dkgSessionId: String,

    /** The `t` of the t-of-n: how many partial signatures make a signature. */
    val threshold: Int,

    /** The `n` the key was generated for. FROST needs it to place signer ids. */
    val participantCount: Int,

    /** This device's FROST id: its index in the ceremony's participant order. */
    val signerId: Int,

    /**
     * The path off the ceremony's key this session signs at, `m/9420/0/0` style,
     * or null to sign as the untweaked threshold key.
     *
     * An input, and stored for the same reason every other input is: the
     * [TweakCache] a signature aggregates against is rebuilt from it on every
     * pass, and a session that resolved a different path after a restart would
     * regenerate a different nonce from the same seed -- publishing a partial
     * signature against an aggregate nobody else computed.
     *
     * Resolved from the room rather than from the proposer, and only accepted
     * when it derives the room's own id, so what a session signs as is the room
     * it is running in. Null is the honest answer for a room that is not derived
     * from the key at all, and is what sessions predating this column read back
     * as -- both of which signed as the threshold key itself.
     *
     * Shared by the whole batch: every item of a session signs as the same room.
     */
    val derivationPath: String? = null,

    val stage: FrostSigningStage = FrostSigningStage.COLLECTING_NONCES,

    /** The chosen signers' FROST ids in aggregation order, comma separated. Written once. */
    val signerIds: String? = null,

    val failureReason: String? = null,

    /**
     * When this device's owner agreed to sign, and with it to everything the
     * session does on their behalf. Null until they do, and nothing of theirs
     * goes out before it is set.
     *
     * One gate rather than the DKG's three, and one gate for the whole batch
     * rather than one per item. What a signer is consenting to is the events,
     * and the events are fixed before they are asked: the second round puts no
     * new question to them, so asking again would be asking the same question
     * twice about a decision already made. That holds for a batch only as long
     * as the member can actually see every event in it before answering.
     */
    val signApprovedAt: Instant? = null,

    /**
     * When this device's owner refused instead. The other answer to the same
     * question, and the same shape: a decision, recorded before anything is
     * published on the strength of it, and never revisited. `advance` publishes
     * the refusal the way it publishes a nonce for an approval -- whenever the
     * row says so and no copy of the message is on file yet.
     *
     * Refusing does not end the session. A t-of-n key needs t willing members,
     * so what one refusal does is take one member out of the n; the session is
     * abandoned only once fewer than t are left -- see [declinesThatAbandon].
     */
    val signDeclinedAt: Instant? = null,

    /** Whether the chat line asking for that approval has been written. */
    val approvalRequestedAt: Instant? = null,

    /**
     * When the coordinator last re-published this session, or null if never.
     *
     * Two jobs, and the second one is not cosmetic. It is the cooldown -- a
     * resend is a publish, and a control that publishes on every tap fills a
     * room with duplicates. And it keeps a second resend out of the same second
     * as the first, where an outbound row's primary key is a hash over
     * `createdAt` and a collision would replace a sent row with an unsent one;
     * see `FrostSigningManager.broadcast`.
     *
     * Only ever written on the coordinator's own row. Null is what every
     * session predating the column reads back as, which is what they all were.
     */
    val resentAt: Instant? = null,

    /**
     * What this session's items are messages of: events, or the inputs of one
     * transaction from the group's wallet.
     *
     * Written at proposal and never again. Every session before the column reads
     * back as [FrostSigningSubject.EVENTS], which is what they all were, and a
     * build that predates it never sees a transaction session at all -- the
     * proposal travels on a kind that build does not route.
     */
    @ColumnInfo(defaultValue = "EVENTS")
    val subject: FrostSigningSubject = FrostSigningSubject.EVENTS,

    override val createdAt: Instant = Clock.System.now(),
    override val updatedAt: Instant = createdAt,
    override val savedAt: Instant = createdAt,
): TimestampedEntity, LocalStoreEntity {
    fun isCoordinator(): Boolean = coordinatorPublicKey == userPublicKey

    /** The chosen signers, or null while the coordinator has yet to choose. */
    fun signerIdList(): List<Int>? = signerIds
        ?.split(",")
        ?.mapNotNull { it.trim().toIntOrNull() }
        ?.takeIf { it.isNotEmpty() }

    /** Whether this device was picked to sign. A t-of-n key does not need everyone. */
    fun isSigner(): Boolean = signerIdList()?.contains(signerId) ?: false

    /**
     * How many members have to refuse before the signature cannot be made.
     *
     * A t-of-n key can do without `n - t` members, so it is the refusal after
     * that -- the one leaving fewer than t who could still sign -- that abandons
     * the session. In a 2-of-3 group one member may decline and the other two
     * still sign; the second refusal is the one that ends it. In an n-of-n group
     * every member is needed and a single refusal is enough.
     */
    fun declinesThatAbandon(): Int = participantCount - threshold + 1

    /**
     * [derivationPath] as indices, and an empty list when there is none.
     *
     * Empty rather than null because that is what it means to
     * `SharedKeyDerivation.derive`: a walk of no steps, ending on the threshold
     * key itself. An unwalkable string reads the same way, since a path nobody
     * can walk is not one this session ever signed at.
     */
    fun pathIndices(): List<Long> =
        derivationPath?.let { SharedKeyDerivation.parsePathString(it) } ?: emptyList()
}
