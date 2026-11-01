package com.machankura.dedun.compose.database.model

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import com.machankura.dedun.compose.database.model.traits.NostrEventEntity
import com.machankura.dedun.compose.database.model.traits.TimestampedEntity
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalUuidApi::class)
@Entity(
    foreignKeys = [
        ForeignKey(
            entity = NostrEvent::class,
            parentColumns = ["id"],
            childColumns = ["nostrEventId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("relayURL"),
    ],
    primaryKeys = ["nostrEventId","relayURL"]
)
data class NostrEventRelay(
    val relayURL: String,

    override val nostrEventId: HexKey,

    override val createdAt: Instant = Clock.System.now(),
    override val updatedAt: Instant = createdAt,
): NostrEventEntity, TimestampedEntity