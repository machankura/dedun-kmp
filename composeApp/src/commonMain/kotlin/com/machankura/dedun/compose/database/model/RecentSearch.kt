package com.machankura.dedun.compose.database.model

import androidx.room3.Entity
import androidx.room3.PrimaryKey
import kotlin.time.Clock
import kotlin.time.Instant

@Entity
data class RecentSearch(
    @PrimaryKey
    val query: String,

    val synchronizationFilters: Array<com.machankura.dedun.compose.database.model.types.SynchronizationFilter>? = null,

    override val createdAt: Instant = Clock.System.now(),
    override val updatedAt: Instant = createdAt,
): com.machankura.dedun.compose.database.model.traits.TimestampedEntity {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as RecentSearch

        if (query != other.query) return false
        if (!synchronizationFilters.contentEquals(other.synchronizationFilters)) return false
        if (createdAt != other.createdAt) return false
        if (updatedAt != other.updatedAt) return false

        return true
    }

    override fun hashCode(): Int {
        var result = query.hashCode()
        result = 31 * result + (synchronizationFilters?.contentHashCode() ?: 0)
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + updatedAt.hashCode()
        return result
    }
}