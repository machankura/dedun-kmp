package com.machankura.dedun.compose.database.model

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * One ritual message from one member, keyed so a duplicate delivery overwrites
 * rather than accumulates — relays redeliver, and feeding the same round-1
 * message in twice would make the coordinator's participant list the wrong
 * length.
 *
 * Covers the host key, round 1 and round 2; the coordinator's own broadcasts
 * live on [DkgSession] because there is only ever one of each.
 */
@Entity(
    primaryKeys = ["sessionId", "participantPublicKey", "kind"],
    foreignKeys = [
        ForeignKey(
            entity = DkgSession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [
        Index("sessionId"),
    ],
)
data class DkgParticipantMessage(
    val sessionId: String,

    /** The member's nostr public key — who sent it. */
    val participantPublicKey: HexKey,

    /** One of the `DkgRitualEvents` kinds. */
    val kind: Kind,

    /** Hex of the protocol bytes, or of the host public key for a host-key message. */
    val payload: HexKey,

    val createdAt: Instant = Clock.System.now(),
)
