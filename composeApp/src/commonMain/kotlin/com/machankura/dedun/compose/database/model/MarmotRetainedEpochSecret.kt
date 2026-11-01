package com.machankura.dedun.compose.database.model

import androidx.room3.Entity
import androidx.room3.ForeignKey
import com.machankura.dedun.compose.database.model.traits.LocalStoreEntity
import com.machankura.dedun.compose.database.model.traits.TimestampedEntity
import kotlin.time.Clock
import kotlin.time.Instant

@Entity(
    foreignKeys = [
        ForeignKey(
            entity = ChatRoom::class,
            parentColumns = ["id"],
            childColumns = ["chatRoomId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    primaryKeys = ["chatRoomId", "epoch"]
)
data class MarmotRetainedEpochSecret(
    val chatRoomId: String,
    val epoch: Long,
    val senderDataSecret: ByteArray, // TODO: Encrypt this...
    val encryptionSecret: ByteArray, // TODO: Encrypt this...
    val leafCount: Int,
    val exporterSecret: ByteArray = ByteArray(0), // TODO: Encrypt this...

    override val createdAt: Instant = Clock.System.now(),
    override val updatedAt: Instant = createdAt,
    override val savedAt: Instant = Clock.System.now(),
):  TimestampedEntity, LocalStoreEntity {

}