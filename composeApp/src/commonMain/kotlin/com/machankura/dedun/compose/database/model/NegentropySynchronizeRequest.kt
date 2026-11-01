package com.machankura.dedun.compose.database.model

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.machankura.dedun.compose.network.serialization.encodeToJsonString
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.utils.sha256.sha256
import io.ktor.utils.io.core.toByteArray
import kotlin.math.ceil
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
    ],
    indices = [
        Index("nostrEventId"),
        Index("status"),
    ],
)
data class NegentropySynchronizeRequest(
    @PrimaryKey
    val id: String,
    val uuid: String = Uuid.generateV4().toHexDashString(),
    val purpose: String,
    val status: String = "pending",
    val relayURL: String,
    val isRecommendedRelay: Boolean = false,

    val level: Int,

    val synchronizationFilter: com.machankura.dedun.compose.database.model.types.SynchronizationFilter,

    /** The identity that asked; null for rows from before, which any identity may drain. See [SynchronizeNostrEventRequest.ownerPublicKey]. */
    val ownerPublicKey: HexKey? = null,

    override val nostrEventId: HexKey? = null,
    override val createdAt: Instant = Clock.System.now(),
    override val updatedAt: Instant = createdAt,
): com.machankura.dedun.compose.database.model.traits.OptionalNostrEventEntity,
    com.machankura.dedun.compose.database.model.traits.TimestampedEntity {

    fun toSynchronizeNostrEventRequest(): SynchronizeNostrEventRequest {
        val since = synchronizationFilter.since // TODO: Update since -> until based on what we have in the local db...
        val until = synchronizationFilter.until

        val synchronizationFilter = synchronizationFilter.copy(
            since = since,
            until = until
        )

        return SynchronizeNostrEventRequest(
            purpose = purpose,
            synchronizationFilters = arrayOf(
                synchronizationFilter
            ),
            relayURL = relayURL,
            level = level,
            ownerPublicKey = ownerPublicKey,
        )
    }
    companion object {
        fun computeId(
            relayURL: String,
            synchronizationFilter: com.machankura.dedun.compose.database.model.types.SynchronizationFilter,
        ): String {
            return sha256(
                data = relayURL.toByteArray()
                        + synchronizationFilter.encodeToJsonString().toByteArray()
                        + ceil(Clock.System.now().epochSeconds/60.0).encodeToJsonString().toByteArray()
            ).toHexString()
        }
    }
}