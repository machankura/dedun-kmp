package com.machankura.dedun.compose.network.dto

import kotlinx.serialization.Serializable

@Serializable
data class RelayDTO(
    val url: String,
    val read: Boolean,
    val write: Boolean
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as RelayDTO

        if (read != other.read) return false
        if (write != other.write) return false
        if (url != other.url) return false

        return true
    }

    override fun hashCode(): Int {
        var result = read.hashCode()
        result = 31 * result + write.hashCode()
        result = 31 * result + url.hashCode()
        return result
    }
}

fun com.machankura.dedun.compose.database.model.Relay.mapToRelayDTO() =
    RelayDTO(
        url = this.url,
        read = this.read,
        write = this.write,
    )

fun String.toRelayDTO(): RelayDTO = RelayDTO(url = this, read = true, write = true)