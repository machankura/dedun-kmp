package com.machankura.dedun.compose.database.model

import androidx.room3.Entity
import androidx.room3.ForeignKey
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import kotlin.time.Clock
import kotlin.time.Instant
import com.machankura.dedun.compose.database.model.traits.LocalStoreEntity
import com.machankura.dedun.compose.database.model.traits.TimestampedEntity

/**
 * One repair message: a helper's share for another helper, a helper's sum for
 * the target, or the target's proof. Encrypted to its recipient's host key
 * where it carries a secret. Keyed so a redelivery replaces rather than adds.
 */
@Entity(
    primaryKeys = ["sessionId", "senderPublicKey", "kind", "recipientPublicKey"],
    foreignKeys = [
        ForeignKey(entity = ShareRepairSession::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)
    ],
)
data class ShareRepairMessage(
    val sessionId: String,
    val senderPublicKey: HexKey,
    val kind: Kind,
    /** Who it is for; empty for a message to everyone. */
    val recipientPublicKey: HexKey,
    val payload: String,
    override val createdAt: Instant = Clock.System.now(),
    override val updatedAt: Instant = createdAt,
    override val savedAt: Instant = createdAt,
) : TimestampedEntity, LocalStoreEntity
