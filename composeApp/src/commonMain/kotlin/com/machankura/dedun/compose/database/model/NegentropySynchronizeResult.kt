package com.machankura.dedun.compose.database.model

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.PrimaryKey
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
@Entity(
    foreignKeys = [
        ForeignKey(
            entity = SynchronizeNostrEventResult::class,
            parentColumns = ["id"],
            childColumns = ["negentropySynchronizeRequestId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class NegentropySynchronizeResult(
    @PrimaryKey
    val id: String = Uuid.generateV4().toHexDashString(),
    val relayURL: String,
    val status: String = "pending",

    val negentropySynchronizeRequestId: String,

    override val createdAt: Instant = Clock.System.now(),
    override val updatedAt: Instant = createdAt,
): com.machankura.dedun.compose.database.model.traits.TimestampedEntity