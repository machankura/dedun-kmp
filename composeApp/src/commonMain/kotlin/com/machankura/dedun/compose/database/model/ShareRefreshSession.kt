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
import com.machankura.dedun.compose.database.model.types.ShareRefreshApprovalStep
import com.machankura.dedun.compose.database.model.types.ShareRefreshStage
import com.machankura.dedun.compose.database.model.types.ShareStanding

/**
 * One share refresh of a group's key, as this device holds it
 * (docs/frost-share-refresh.md, Phase 7).
 *
 * Like `DkgSession`, it stores inputs rather than protocol state: the
 * randomness, the received messages and the two aggregates. Every step is a
 * pure function of them, so a refresh survives the app being killed halfway
 * and resumes on the next message.
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
data class ShareRefreshSession(
    /** The refresh id: 32 bytes hex, chosen by the proposer, bound into every message. */
    @PrimaryKey
    val id: String,

    /** The Marmot room the refresh runs in, whose id the key derives. */
    val chatRoomId: String,

    /** The ceremony whose key is being refreshed. */
    val dkgSessionId: String,

    /** Who proposed it, and so who sums and certifies. A recipient, and untrusted like any coordinator. */
    val coordinatorPublicKey: HexKey,

    /** Whose device this row belongs to. */
    val userPublicKey: HexKey,

    /** The generation being refreshed. The refresh makes `baseGeneration + 1`. */
    val baseGeneration: Int,

    /** The recipients' participant ids, ascending, comma separated. Every one deals and confirms. */
    val recipientIds: String,

    /**
     * The participant ids dealt nothing, and what each becomes, as `id:STANDING`
     * comma separated -- `2:STALE` to be repaired later, `2:RETIRED` for good.
     * Null when nobody is withheld.
     */
    val withheld: String? = null,

    val stage: ShareRefreshStage = ShareRefreshStage.COLLECTING_ROUND_1,

    /** Secret. Round 1's fresh randomness. Kept because round 1 is pure in it. */
    val round1Random: HexKey,

    /** Secret-adjacent. Round 2's auxiliary randomness for the confirmation's signature. */
    val auxRandom: HexKey,

    val dealApprovedAt: Instant? = null,
    val confirmApprovedAt: Instant? = null,

    /** The furthest step this device has asked its owner about, so the request line is written once. */
    val approvalRequestedThrough: ShareRefreshApprovalStep? = null,

    /** The coordinator's sum (cmsg1), hex, once it arrives. */
    val coordinatorRound1: HexKey? = null,

    /** The certificate (cmsg2), hex, once it arrives. */
    val certificate: HexKey? = null,

    val failureReason: String? = null,

    override val createdAt: Instant = Clock.System.now(),
    override val updatedAt: Instant = createdAt,
    override val savedAt: Instant = createdAt,
) : TimestampedEntity, LocalStoreEntity {
    fun recipientIdList(): List<Int> = recipientIds.split(",").mapNotNull { it.trim().toIntOrNull() }

    fun withheldStandings(): Map<Int, ShareStanding> = withheld.orEmpty()
        .split(",")
        .mapNotNull { entry ->
            val (id, standing) = entry.split(":").takeIf { it.size == 2 } ?: return@mapNotNull null
            val standingValue = ShareStanding.entries.firstOrNull { it.name == standing.trim().uppercase() } ?: return@mapNotNull null
            id.trim().toIntOrNull()?.let { it to standingValue }
        }
        .toMap()

    fun isCoordinator(): Boolean = coordinatorPublicKey == userPublicKey

    override fun toString(): String =
        "ShareRefreshSession(id=$id, key=$dkgSessionId, base=$baseGeneration, stage=$stage, recipients=$recipientIds, withheld=$withheld)"

    companion object {
        fun formatIds(ids: Collection<Int>): String = ids.sorted().joinToString(",")

        fun formatWithheld(withheld: Map<Int, ShareStanding>): String? =
            withheld.takeIf { it.isNotEmpty() }?.entries?.sortedBy { it.key }?.joinToString(",") { "${it.key}:${it.value.name}" }
    }
}
