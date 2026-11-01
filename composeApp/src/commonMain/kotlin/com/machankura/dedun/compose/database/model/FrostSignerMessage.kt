package com.machankura.dedun.compose.database.model

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * One signing message from one member, keyed so a duplicate delivery overwrites
 * rather than accumulates — relays redeliver, and feeding the same nonce in
 * twice would make the coordinator's signer set the wrong length.
 *
 * Covers the nonce, the partial signature and the refusal; the coordinator's
 * own broadcasts live on [FrostSigningSession] because there is only ever one
 * of each. A refusal is here rather than on the session for the same reason a
 * nonce is: there is one per member, and how many there are is what decides
 * whether the session goes on -- see `FrostSigningSession.declinesThatAbandon`.
 *
 * Keyed on `(sessionId, signerPublicKey, kind)`, which also means a member
 * cannot replace their own nonce once the coordinator has aggregated it — a
 * second nonce from the same signer overwrites the first, and the aggregate
 * built from it simply stops matching. The session's own write-once rule on the
 * aggregate is what makes that a stalled session rather than a leaked share.
 */
@Entity(
    primaryKeys = ["sessionId", "signerPublicKey", "kind"],
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
data class FrostSignerMessage(
    val sessionId: String,

    /** The member's nostr public key — who sent it. */
    val signerPublicKey: HexKey,

    /** One of the `FrostSigningEvents` kinds. */
    val kind: Kind,

    /**
     * Hex of the protocol bytes: a public nonce, or a partial signature. For a
     * refusal there are no bytes, and this is the reason the member gave.
     */
    val payload: HexKey,

    val createdAt: Instant = Clock.System.now(),
)
