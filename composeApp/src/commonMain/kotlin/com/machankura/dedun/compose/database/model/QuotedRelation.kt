package com.machankura.dedun.compose.database.model

import androidx.room3.Entity
import androidx.room3.ForeignKey
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlin.time.Clock
import kotlin.time.Instant

@Entity(
    foreignKeys = [
        ForeignKey(
            entity = Profile::class,
            parentColumns = ["publicKey"],
            childColumns = ["quotedProfilePublicKey"],
            onDelete = ForeignKey.CASCADE,
        ),
        // This might cause problems... but we gonna make the feed so cool if it works...
        ForeignKey(
            entity = NostrEvent::class,
            parentColumns = ["id"],
            childColumns = ["quotingNostrEventId"],
            onDelete = ForeignKey.CASCADE,
        ),
        // Support post replies
        ForeignKey(
            entity = NostrEvent::class,
            parentColumns = ["id"],
            childColumns = ["quotedNostrEventId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    primaryKeys = [
        "quotingNostrEventId", "quotedNostrEventId"
    ]
)
data class QuotedRelation(
    val quotingNostrEventId: HexKey,

    val quotedNostrEventId: HexKey,
    val quotedProfilePublicKey: HexKey?,

    override val createdAt: Instant = Clock.System.now(),
    override val updatedAt: Instant = createdAt,
    override val savedAt: Instant = Clock.System.now(),
    override val viewedAt: Instant? = null,
    override val deletedAt: Instant? = null,
    override val broadcastedAt: Instant? = null,
): com.machankura.dedun.compose.database.model.traits.TimestampedEntity,
    com.machankura.dedun.compose.database.model.traits.LocalStoreEntity,
    com.machankura.dedun.compose.database.model.traits.UserViewableEntity,
    com.machankura.dedun.compose.database.model.traits.BroadcastableEntity,
    com.machankura.dedun.compose.database.model.traits.SoftDeletableEntity