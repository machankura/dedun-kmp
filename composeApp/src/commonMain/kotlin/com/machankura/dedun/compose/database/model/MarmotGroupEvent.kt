package com.machankura.dedun.compose.database.model

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.PrimaryKey
import com.machankura.dedun.compose.database.model.traits.BroadcastableEntity
import com.machankura.dedun.compose.database.model.traits.LocalStoreEntity
import com.machankura.dedun.compose.database.model.traits.SoftDeletableEntity
import com.machankura.dedun.compose.database.model.traits.TimestampedEntity
import com.vitorpamplona.quartz.marmot.mip03GroupMessages.GroupEvent
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlin.time.Clock
import kotlin.time.Instant

@Entity(
    foreignKeys = [
        ForeignKey(
            entity = NostrEvent::class,
            parentColumns = ["id"],
            childColumns = ["id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class MarmotGroupEvent(
    @PrimaryKey
    val id: HexKey,

    /**
     * Sender Public Key
     */
    val userPublicKey: String,

    val publicKey: HexKey,

    val chatRoomId: String,

    val signature: String, // TODO: Add signature...

    val encryptedContent: String,
    val expiresAt: Instant?,

    override val createdAt: Instant = Clock.System.now(),
    override val updatedAt: Instant = createdAt,
    override val savedAt: Instant = Clock.System.now(),
    override val deletedAt: Instant? = null,
    override val broadcastedAt: Instant? = null,
) : TimestampedEntity, LocalStoreEntity, BroadcastableEntity, SoftDeletableEntity {

    companion object {
        const val KIND = GroupEvent.KIND
    }

}