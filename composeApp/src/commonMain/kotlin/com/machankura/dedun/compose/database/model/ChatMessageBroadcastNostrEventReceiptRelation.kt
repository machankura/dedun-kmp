package com.machankura.dedun.compose.database.model

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.PrimaryKey
import kotlin.time.Clock
import kotlin.time.Instant

@Entity(
    foreignKeys = [
        ForeignKey(
            entity = ChatMessage::class,
            parentColumns = ["id"],
            childColumns = ["chatMessageId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = BroadcastNostrEventReceipt::class,
            parentColumns = ["id"],
            childColumns = ["broadcastNostrEventReceiptId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ]
)
data class ChatMessageBroadcastNostrEventReceiptRelation(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val chatMessageId: Long,
    val broadcastNostrEventReceiptId: Long,

    override val createdAt: Instant = Clock.System.now(),
    override val updatedAt: Instant = Clock.System.now(),
    override val savedAt: Instant = Clock.System.now()
): com.machankura.dedun.compose.database.model.traits.TimestampedEntity,
    com.machankura.dedun.compose.database.model.traits.LocalStoreEntity
