package com.machankura.dedun.compose.database.model

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.PublicKey
import kotlin.time.Clock
import kotlin.time.Instant
import com.machankura.dedun.compose.database.model.traits.LocalStoreEntity
import com.machankura.dedun.compose.database.model.traits.TimestampedEntity
import com.machankura.dedun.compose.database.model.types.ShareGenerationState

/**
 * One generation of a key's shares, as this device holds it: every generation a
 * refresh made, never the one ChillDKG made.
 *
 * A refresh (docs/frost-share-refresh.md) gives every member a new share of the
 * same threshold key, on a new polynomial, so the old shares stop combining
 * with the new. The key does not move -- the room, its id and everything the
 * group has signed stay as they are -- but which share signs, and which public
 * share it is checked against, now depends on the generation.
 *
 * **Generation 0 has no row.** It is the ceremony's output and stays where the
 * ceremony put it: `DkgSession.secretShare` and `publicShares`, with participant
 * ids derived from the ceremony's host keys. That derivation was chosen so that
 * signing could never disagree with the ceremony that made the key, and a copy
 * of it here would be a second source of truth to keep in step with the first.
 * From generation 1 on there is no ceremony to derive from, and the rows are
 * the record: certified by the refresh, and named in the group's signed key
 * state. `ShareGenerations` reads both through one door.
 */
@Entity(
    primaryKeys = ["dkgSessionId", "generation"],
    foreignKeys = [
        ForeignKey(
            entity = DkgSession::class,
            parentColumns = ["id"],
            childColumns = ["dkgSessionId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [
        Index("dkgSessionId"),
    ],
)
data class KeyShareGeneration(
    /** The ceremony that made the key. Its id is the `frost_key` every signing session names. */
    val dkgSessionId: String,

    /** 1 or more. 0 is the ceremony's, and has no row. */
    val generation: Int,

    /**
     * Secret: this device's share at this generation, hex. Null once wiped, and
     * null throughout for a generation this device was withheld from.
     */
    val secretShare: HexKey?,

    /** Every participant's public share at this generation, comma-separated hex, in participant id order. */
    val publicShares: String,

    val state: ShareGenerationState,

    /** The refresh that made this generation, or null while that is not yet recorded. */
    val refreshSessionId: String? = null,

    /**
     * What rebuilds this generation from the one before it with the host key:
     * the refresh's public transcript. Not secret on its own.
     */
    val recoveryData: HexKey? = null,

    /** When the group started signing with it, here. */
    val activatedAt: Instant? = null,

    /** When its secret share was deleted from this device. */
    val wipedAt: Instant? = null,

    override val createdAt: Instant = Clock.System.now(),
    override val updatedAt: Instant = createdAt,
    override val savedAt: Instant = createdAt,
) : TimestampedEntity, LocalStoreEntity {
    init {
        require(generation >= 1) { "Generation 0 is the ceremony's and has no row; got $generation" }
    }

    fun publicShareList(): List<PublicKey>? = publicShares
        .split(",")
        .mapNotNull { hex -> hex.trim().takeIf { it.isNotEmpty() } }
        .map { PublicKey(ByteVector(it.hexToByteArray())) }
        .takeIf { it.isNotEmpty() }

    override fun toString(): String =
        "KeyShareGeneration(dkgSessionId=$dkgSessionId, generation=$generation, state=$state, " +
            "secretShare=${if (secretShare == null) "null" else "<secret>"})"
}
