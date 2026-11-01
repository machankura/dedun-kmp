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
            entity = BroadcastNostrEventRequest::class,
            parentColumns = ["id"],
            childColumns = ["broadcastNostrEventRequestId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ]
)
data class ChatMessageBroadcastNostrEventRequestRelation(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val chatMessageId: Long,
    val broadcastNostrEventRequestId: Long,
    // Add nostrEventId here...

    override val createdAt: Instant = Clock.System.now(),
    override val updatedAt: Instant = Clock.System.now(),
    override val savedAt: Instant = Clock.System.now()
): com.machankura.dedun.compose.database.model.traits.TimestampedEntity,
    com.machankura.dedun.compose.database.model.traits.LocalStoreEntity
