package com.machankura.dedun.compose.database.model.types

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
data class SynchronizationFilter(
    val ids: Array<HexKey>? = null,
    val authors: Array<HexKey>? = null,
    val kinds: Array<Kind>? = null,
    val tags: Map<String, List<String>>? = null,
    val tagsAll: Map<String, List<String>>? = null,
    val since: Instant? = null,
    val until: Instant? = null,
    val limit: Int? = null,
    val search: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as SynchronizationFilter

        if (since != other.since) return false
        if (until != other.until) return false
        if (limit != other.limit) return false
        if (!ids.contentEquals(other.ids)) return false
        if (!authors.contentEquals(other.authors)) return false
        if (!kinds.contentEquals(other.kinds)) return false
        if (tags != other.tags) return false
        if (tagsAll != other.tagsAll) return false
        if (search != other.search) return false

        return true
    }

    override fun hashCode(): Int {
        var result = since?.hashCode() ?: 0
        result = 31 * result + (until?.hashCode() ?: 0)
        result = 31 * result + (limit ?: 0)
        result = 31 * result + (ids?.contentHashCode() ?: 0)
        result = 31 * result + (authors?.contentHashCode() ?: 0)
        result = 31 * result + (kinds?.contentHashCode() ?: 0)
        result = 31 * result + (tags?.hashCode() ?: 0)
        result = 31 * result + (tagsAll?.hashCode() ?: 0)
        result = 31 * result + (search?.hashCode() ?: 0)
        return result
    }
}
