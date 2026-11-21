package com.machankura.dedun.compose.nostr

import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.isLocalHost

/**
 * A relay a member typed, as one the group's signed work could be sent to.
 *
 * Lifted out of Curare's `GroupRelaySet`, whose relay lists were not brought back
 * with the group's profile and posts (docs/group-nostr-profile-and-posts.md, D1):
 * this is the one piece of it the broadcast screen needs.
 */
object RelayUrlInput {
    /**
     * [url] as a relay the group's event could be sent to, or null.
     *
     * Structural only, and deliberately narrow: `wss://` because what the group
     * signed is read by strangers over the open network and `ws://` would send it in
     * the clear, and no loopback because a relay only this device can reach is not
     * a broadcast. Wisp's `RelayConfig.isValidUrl` refuses the same three things.
     */
    fun relayUrlOrNull(url: String): NormalizedRelayUrl? {
        val trimmed = url.trim()
        if (!trimmed.startsWith("wss://", ignoreCase = true)) return null

        return RelayUrlNormalizer.normalizeOrNull(trimmed)?.takeUnless { it.isLocalHost() }
    }
}
