package com.machankura.dedun.compose.database.model

import androidx.room3.Entity
import androidx.room3.Index
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
//    foreignKeys = [
//        ForeignKey(
//            entity = Profile::class,
//            parentColumns = ["publicKey"],
//            childColumns = ["publicKey"],
//            onDelete = ForeignKey.CASCADE,
//        ),
//    ],
    indices = [
        Index("publicKey"),
    ],
)
data class MarmotKeyPackageBundle(
    @PrimaryKey
    val id: HexKey,

    val publicKey: HexKey,

    val tlsEncodedMarmotKeyPackage: ByteArray,

    val ncryptsecInitPrivateKey: String,
    val ncryptsecEncryptionPrivateKey: String,
    val ncryptsecSignaturePrivateKey: String,

    val consumed: Boolean = false,
    val rotated: Boolean = false, // TODO: Should we have rotatedByID
    /**
     * [MarmotProfile.wireName] of the KeyPackage this bundle backs, or null for a row
     * written before the column existed, which is a legacy one. Read through
     * [profileOrLegacy]. Decides which shape a rotation republishes into which slot.
     */
    val profile: String? = null,
    /**
     * The kind:30443 slot this bundle was published under. MIP-00 gives each slot a
     * random d-tag on first use and reuses it for every rotation, so a replacement
     * supersedes the old event on relays rather than accumulating beside it. Null for
     * a row from before the column, whose slot was random and is not reused.
     */
    val dTag: String? = null,
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

        other as MarmotKeyPackageBundle

        if (consumed != other.consumed) return false
        if (rotated != other.rotated) return false
        if (profile != other.profile) return false
        if (dTag != other.dTag) return false
        if (id != other.id) return false
        if (publicKey != other.publicKey) return false
        if (!tlsEncodedMarmotKeyPackage.contentEquals(other.tlsEncodedMarmotKeyPackage)) return false
        if (ncryptsecInitPrivateKey != other.ncryptsecInitPrivateKey) return false
        if (ncryptsecEncryptionPrivateKey != other.ncryptsecEncryptionPrivateKey) return false
        if (ncryptsecSignaturePrivateKey != other.ncryptsecSignaturePrivateKey) return false
        if (createdAt != other.createdAt) return false
        if (updatedAt != other.updatedAt) return false
        if (savedAt != other.savedAt) return false
        if (deletedAt != other.deletedAt) return false
        if (broadcastedAt != other.broadcastedAt) return false

        return true
    }

    override fun hashCode(): Int {
        var result = consumed.hashCode()
        result = 31 * result + rotated.hashCode()
        result = 31 * result + (profile?.hashCode() ?: 0)
        result = 31 * result + (dTag?.hashCode() ?: 0)
        result = 31 * result + id.hashCode()
        result = 31 * result + publicKey.hashCode()
        result = 31 * result + tlsEncodedMarmotKeyPackage.contentHashCode()
        result = 31 * result + ncryptsecInitPrivateKey.hashCode()
        result = 31 * result + ncryptsecEncryptionPrivateKey.hashCode()
        result = 31 * result + ncryptsecSignaturePrivateKey.hashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + updatedAt.hashCode()
        result = 31 * result + savedAt.hashCode()
        result = 31 * result + (deletedAt?.hashCode() ?: 0)
        result = 31 * result + (broadcastedAt?.hashCode() ?: 0)
        return result
    }
}