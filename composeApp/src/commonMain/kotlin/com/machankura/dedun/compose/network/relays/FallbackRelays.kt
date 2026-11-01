package com.machankura.dedun.compose.network.relays

import com.machankura.dedun.compose.network.dto.toRelayDTO

val FALLBACK_RELAYS = listOf(
    "wss://relay.primal.net",
    "wss://relay.damus.io",
    "wss://relay.current.fyi",
    "wss://purplepag.es",
    "wss://nos.lol",
    "wss://offchain.pub",
    "wss://nostr.bitcoiner.social",
).map { it.toRelayDTO() }.toSet()


