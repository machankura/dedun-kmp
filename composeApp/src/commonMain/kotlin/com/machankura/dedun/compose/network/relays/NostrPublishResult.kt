package com.machankura.dedun.compose.network.relays

import com.machankura.dedun.compose.network.sockets.NostrIncomingMessage

data class NostrPublishResult(
    val result: NostrIncomingMessage? = null,
    val error: Throwable? = null,
    val relayUrl: String,
)
