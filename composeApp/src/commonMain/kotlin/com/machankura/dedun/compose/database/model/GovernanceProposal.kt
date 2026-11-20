package com.machankura.dedun.compose.database.model

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlin.time.Clock
import kotlin.time.Instant
import com.machankura.dedun.compose.database.model.traits.LocalStoreEntity
import com.machankura.dedun.compose.database.model.traits.TimestampedEntity

/**
 * A change this device proposed to a governed room, held while the group signs it.
 * docs/member-admin-promotion.md, Phase 5.
 *
 * The commit is built when the change is proposed -- the group signs the very
 * kind:445 that carries it -- and this device cannot process its own commit, so the
 * state the commit leads to has to be kept from then until a relay accepts it.
 * Signing outlasts a session, so it is kept here rather than in memory.
 *
 * Only the proposer's device has one; every other device learns of the change from
 * the signing session and applies it from the carried envelope.
 */
@Entity(
    foreignKeys = [
        ForeignKey(entity = ChatRoom::class, parentColumns = ["id"], childColumns = ["chatRoomId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("chatRoomId"), Index("frostSigningSessionId"), Index("commitEventId")],
)
data class GovernanceProposal(
    /** The unsigned statement's id: what the group signs. */
    @PrimaryKey
    val id: HexKey,
    val chatRoomId: String,
    val routingId: HexKey,
    /** The epoch the commit is built at, and the only one it can land at. */
    val epoch: Long,
    val frostSigningSessionId: String,
    /** The kind:445 that carries the commit, as JSON, exactly as it was built. */
    val commitEventId: HexKey,
    val commitEventJson: String,
    /**
     * The invitee's key package event, for an add, so the Welcome goes to the right
     * package. The commit's own parts -- its bytes, its Welcome, its outer key -- are
     * in the `MarmotCommitResult` filed under [commitEventId].
     */
    val peerKeyPackageEventId: HexKey? = null,
    /** The group's state before and after the commit, TLS-encoded hex. */
    val priorStateHex: String,
    val pendingStateHex: String,
    val stage: GovernanceProposalStage = GovernanceProposalStage.SIGNING,
    override val createdAt: Instant = Clock.System.now(),
    override val updatedAt: Instant = createdAt,
    override val savedAt: Instant = createdAt,
) : TimestampedEntity, LocalStoreEntity

enum class GovernanceProposalStage {
    /** Waiting for the group's signature. */
    SIGNING,

    /** Signed and sent; waiting for a relay to accept it. */
    PUBLISHED,

    /** The group moved past its epoch before it was signed or sent; nothing can land it now. */
    STALE,
}
