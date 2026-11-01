package com.machankura.dedun.compose.database.model

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.PrimaryKey
import com.machankura.dedun.compose.database.model.traits.BroadcastableEntity
import com.machankura.dedun.compose.database.model.traits.LocalStoreEntity
import com.machankura.dedun.compose.database.model.traits.SoftDeletableEntity
import com.machankura.dedun.compose.database.model.traits.TimestampedEntity
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.machankura.dedun.compose.nostr.MarmotProfile
import kotlin.time.Clock
import kotlin.time.Instant

@Entity(
    foreignKeys = [
        ForeignKey(
            entity = Profile::class,
            parentColumns = ["publicKey"],
            childColumns = ["publicKey"],
            onDelete = ForeignKey.CASCADE,
        ),
    ]
)
data class MarmotKeyPackage(
    @PrimaryKey
    val id: HexKey,

    val publicKey: HexKey,

    val tlsEncodedMarmotKeyPackage: ByteArray,

    /**
     * [MarmotProfile.wireName] of the kind:30443 this came from, filled at ingest
     * from `KeyPackageEvent.isCurrentProfile()`; null for a row from before the
     * column, which is legacy. What lets a room pick a peer's package by profile
     * with a query rather than a decode -- see docs/marmot-v2.md, Phase 3.
     */
    val profile: String? = null,

    override val createdAt: Instant = Clock.System.now(),
    override val updatedAt: Instant = Clock.System.now(),
    override val savedAt: Instant = Clock.System.now(),
    override val deletedAt: Instant? = null,
    override val broadcastedAt: Instant? = null,
): TimestampedEntity, LocalStoreEntity, BroadcastableEntity, SoftDeletableEntity {
    val profileOrLegacy: MarmotProfile get() = MarmotProfile.fromWireName(profile)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as MarmotKeyPackage

        if (id != other.id) return false
        if (publicKey != other.publicKey) return false
        if (!tlsEncodedMarmotKeyPackage.contentEquals(other.tlsEncodedMarmotKeyPackage)) return false
        if (profile != other.profile) return false
        if (createdAt != other.createdAt) return false
        if (updatedAt != other.updatedAt) return false
        if (savedAt != other.savedAt) return false
        if (deletedAt != other.deletedAt) return false
        if (broadcastedAt != other.broadcastedAt) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + publicKey.hashCode()
        result = 31 * result + tlsEncodedMarmotKeyPackage.contentHashCode()
        result = 31 * result + (profile?.hashCode() ?: 0)
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + updatedAt.hashCode()
        result = 31 * result + savedAt.hashCode()
        result = 31 * result + (deletedAt?.hashCode() ?: 0)
        result = 31 * result + (broadcastedAt?.hashCode() ?: 0)
        return result
    }

}