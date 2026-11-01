package com.machankura.dedun.compose.database.model

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import kotlin.time.Clock
import kotlin.time.Instant

@Entity(
    indices = [
        Index("pubKey"),
        Index("kind"),
    ],
)
data class UnsignedNostrEvent(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val pubKey: HexKey,
    val kind: Kind,
    val tags: Array<Array<String>>,
    val privateTags: Array<Array<String>>? = null,
    val content: String,

    val signedAt: Instant? = null,

    override val createdAt: Instant = Clock.System.now(),
    override val updatedAt: Instant = Clock.System.now(),
    override val savedAt: Instant = Clock.System.now(),
    override val deletedAt: Instant? = null,
    override val broadcastedAt: Instant? = null,
): com.machankura.dedun.compose.database.model.traits.TimestampedEntity,
    com.machankura.dedun.compose.database.model.traits.LocalStoreEntity,
    com.machankura.dedun.compose.database.model.traits.BroadcastableEntity,
    com.machankura.dedun.compose.database.model.traits.SoftDeletableEntity {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as UnsignedNostrEvent

        if (id != other.id) return false
        if (pubKey != other.pubKey) return false
        if (kind != other.kind) return false
        if (!tags.contentDeepEquals(other.tags)) return false
        if (privateTags == null != (other.privateTags == null)) return false
        if (privateTags != null && !privateTags.contentDeepEquals(other.privateTags)) return false
        if (content != other.content) return false
        if (signedAt != other.signedAt) return false
        if (createdAt != other.createdAt) return false
        if (updatedAt != other.updatedAt) return false
        if (savedAt != other.savedAt) return false
        if (deletedAt != other.deletedAt) return false
        if (broadcastedAt != other.broadcastedAt) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + pubKey.hashCode()
        result = 31 * result + kind
        result = 31 * result + tags.contentDeepHashCode()
        result = 31 * result + (privateTags?.contentDeepHashCode() ?: 0)
        result = 31 * result + content.hashCode()
        result = 31 * result + (signedAt?.hashCode() ?: 0)
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + updatedAt.hashCode()
        result = 31 * result + savedAt.hashCode()
        result = 31 * result + (deletedAt?.hashCode() ?: 0)
        result = 31 * result + (broadcastedAt?.hashCode() ?: 0)
        return result
    }


}