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
import com.machankura.dedun.compose.database.model.types.ShareRepairStage

/**
 * One repair of a participant id at one generation -- bringing back a member a
 * refresh left stale, with reduced FROST enrollment (docs/frost-share-refresh.md,
 * Phase 10). The id is the session id the enrollment binds every message to.
 */
@Entity(
    foreignKeys = [
        ForeignKey(entity = ChatRoom::class, parentColumns = ["id"], childColumns = ["chatRoomId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("chatRoomId"), Index("dkgSessionId")],
)
data class ShareRepairSession(
    /** 32 bytes hex, chosen by the target, and the enrollment's session id. */
    @PrimaryKey
    val id: String,
    val chatRoomId: String,
    val dkgSessionId: String,
    /** The generation the target is brought back into. */
    val generation: Int,
    /** The participant id being repaired. */
    val targetId: Int,
    /** Who asked: the identity that will hold the share. */
    val targetPublicKey: HexKey,
    /** The host key the target asked under, which the helpers encrypt to. */
    val targetHostPublicKey: HexKey,
    /** The helpers' participant ids, ascending, comma separated: t live members of the generation. */
    val helperIds: String,
    val userPublicKey: HexKey,
    val stage: ShareRepairStage = ShareRepairStage.COLLECTING,
    /** Secret. A helper's round-1.1 randomness. */
    val sessionRandom: HexKey,
    val helpApprovedAt: Instant? = null,
    val approvalRequested: Boolean = false,
    val failureReason: String? = null,
    override val createdAt: Instant = Clock.System.now(),
    override val updatedAt: Instant = createdAt,
    override val savedAt: Instant = createdAt,
) : TimestampedEntity, LocalStoreEntity {
    fun helperIdList(): List<Int> = helperIds.split(",").mapNotNull { it.trim().toIntOrNull() }
}
