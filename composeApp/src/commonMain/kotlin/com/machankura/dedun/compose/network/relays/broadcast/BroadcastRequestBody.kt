package com.machankura.dedun.compose.network.relays.broadcast

import com.vitorpamplona.quartz.nip01Core.core.Event
import kotlinx.serialization.Serializable

@Serializable
data class BroadcastRequestBody(
    val events: List<Event>,
    val relays: List<String>,
)
