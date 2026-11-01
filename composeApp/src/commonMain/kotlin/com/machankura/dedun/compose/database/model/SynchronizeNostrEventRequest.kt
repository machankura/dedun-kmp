package com.machankura.dedun.compose.database.model

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
@Entity(
    foreignKeys = [
        ForeignKey(
            entity = NostrEvent::class,
            parentColumns = ["id"],
            childColumns = ["nostrEventId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = UnsignedNostrEvent::class,
            parentColumns = ["id"],
            childColumns = ["unsignedNostrEventId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("nostrEventId"),
        Index("status"),
    ],
)
data class SynchronizeNostrEventRequest(
    @PrimaryKey
    val id: String = Uuid.generateV4().toHexDashString(),
    val purpose: String,
    val status: String = "pending",
    val relayURL: String,
    val isRecommendedRelay: Boolean = false,

    val level: Int,

    val synchronizationFilters: Array<com.machankura.dedun.compose.database.model.types.SynchronizationFilter>,

    /**
     * The identity that asked, so that only its pump answers: what a fetch brings back
     * is opened with the fetcher's key. Null for rows written before there was one to
     * record, which any identity may drain. See docs/multiple-profiles.md, Phase 6.
     */
    val ownerPublicKey: HexKey? = null,

    override val nostrEventId: HexKey? = null,
    override val unsignedNostrEventId: Long? = null,
    override val createdAt: Instant = Clock.System.now(),
    override val updatedAt: Instant = createdAt,
): com.machankura.dedun.compose.database.model.traits.OptionalNostrEventEntity,
    com.machankura.dedun.compose.database.model.traits.UnsignedNostrEventEntity,
    com.machankura.dedun.compose.database.model.traits.TimestampedEntity {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as SynchronizeNostrEventRequest

        if (unsignedNostrEventId != other.unsignedNostrEventId) return false
        if (id != other.id) return false
        if (status != other.status) return false
        if (relayURL != other.relayURL) return false
        if (!synchronizationFilters.contentEquals(other.synchronizationFilters)) return false
        if (ownerPublicKey != other.ownerPublicKey) return false
        if (nostrEventId != other.nostrEventId) return false
        if (createdAt != other.createdAt) return false
        if (updatedAt != other.updatedAt) return false

        return true
    }

    override fun hashCode(): Int {
        var result = unsignedNostrEventId?.hashCode() ?: 0
        result = 31 * result + id.hashCode()
        result = 31 * result + status.hashCode()
        result = 31 * result + relayURL.hashCode()
        result = 31 * result + synchronizationFilters.contentHashCode()
        result = 31 * result + (ownerPublicKey?.hashCode() ?: 0)
        result = 31 * result + (nostrEventId?.hashCode() ?: 0)
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + updatedAt.hashCode()
        return result
    }

}