package com.machankura.dedun.compose.database.model

import androidx.room3.Entity
import androidx.room3.PrimaryKey
import com.vitorpamplona.quartz.nip01Core.core.HexKey

/**
 * A commit this device prepared and has not yet heard a relay accept.
 *
 * Publish-before-apply (`protocol-core/publish-lifecycle.md`): a locally generated
 * group-state change must not become canonical until a relay in its scope has
 * acknowledged it, and the record has to be durable *before* anything is broadcast
 * -- publishing first and recording after leaves a crash window in which peers hold
 * a commit this device has no memory of preparing, and the next start would mint a
 * replacement, forking the group against everyone who took the first one.
 *
 * The bytes are the library's own encoding of `MarmotPublishObligation` -- the
 * outbound MLS bytes, their recipient scope, the state they were made from and the
 * state they would make canonical -- opaque here, because the gate decodes them.
 * See docs/marmot-v2.md, Phase 6.
 */
@Entity
data class MarmotPublishObligation(
    /** `SHA-256` of the outbound bytes; the same id a retry carries. */
    @PrimaryKey
    val obligationId: HexKey,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is MarmotPublishObligation && obligationId == other.obligationId && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = 31 * obligationId.hashCode() + bytes.contentHashCode()
}

/**
 * An outbound gate a group is under -- `LEAVING`, `DISBANDING`, `REMOVED` -- which
 * blocks new local commits and has to survive a restart: a disband that is only in
 * memory is lost by the crash between raising it and publishing the commit.
 */
@Entity
data class MarmotOutboundGate(
    /** The routing address the library gates by. */
    @PrimaryKey
    val groupId: HexKey,
    val gate: String,
)
