package com.machankura.dedun.compose.nostr.inbox

import com.vitorpamplona.quartz.experimental.decoupling.setup.EncryptionKeyListEvent
import com.vitorpamplona.quartz.experimental.decoupling.setup.tags.KeyTag
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip17Dm.settings.DmRelayListEvent
import com.vitorpamplona.quartz.nip17Dm.settings.tags.RelayTag
import com.machankura.dedun.compose.nostr.RelayUrlInput

/**
 * The two public events that make a group writable to (docs/group-inbox.md, D5 and D6), both
 * signed by the group's quorum:
 *
 * - a kind **10044**, NIP-4E's announcement (nostr-protocol/nips#1647): `[["n", E]]` and nothing
 *   else, where `E` is the inbox key;
 * - a kind **10050**, NIP-17's DM relay list: where senders should put what they write.
 *
 * Read by quartz's own [EncryptionKeyListEvent] and [DmRelayListEvent], so the group's events
 * parse the way every other client's do.
 */
object InboxAnnouncement {
    const val KEY_KIND = EncryptionKeyListEvent.KIND
    const val RELAYS_KIND = DmRelayListEvent.KIND

    /** The group's 10044, naming [inboxKey] alone. */
    fun keyTemplate(inboxKey: HexKey, createdAt: Long): EventTemplate<Event> {
        require(InboxWire.isHex32(inboxKey)) { "an inbox key is a 32-byte x-only key" }
        val built = EncryptionKeyListEvent.build(inboxKey, createdAt)
        return EventTemplate(built.createdAt, built.kind, built.tags, built.content)
    }

    /** The group's 10050, listing [relays]. */
    fun relaysTemplate(relays: List<NormalizedRelayUrl>, createdAt: Long): EventTemplate<Event> {
        require(relays.isNotEmpty()) { "an inbox nobody can deliver to is not an inbox" }
        return EventTemplate(createdAt, RELAYS_KIND, DmRelayListEvent.createTagArray(relays), "")
    }

    /**
     * The key a 10044 announces, read as any NIP-4E client reads it: the first well-formed `n`.
     * For someone else's announcement, which may carry more than this app writes.
     */
    fun announcedKeyOf(event: Event): HexKey? {
        if (event.kind != KEY_KIND) return null
        return event.tags.firstNotNullOfOrNull(KeyTag::parse)?.pubkey
    }

    /**
     * The key a group's own 10044 announces, or null if [event] is not shaped the way this app
     * writes one: exactly one tag, an `n` with a key and no nonce, and no content. A signer
     * refuses anything else (docs/group-inbox.md, D5): a second `n`, or a legacy nonce, would be
     * the group announcing something nobody agreed to.
     */
    fun groupKeyOf(event: Event): HexKey? = groupKeyOf(event.kind, event.tags, event.content)

    fun groupKeyOf(kind: Int, tags: Array<Array<String>>, content: String): HexKey? {
        if (kind != KEY_KIND || content.isNotEmpty()) return null
        val tag = tags.singleOrNull() ?: return null
        if (tag.size != 2) return null
        val key = KeyTag.parse(tag) ?: return null
        return key.pubkey.takeIf { key.nonce == null && tag[1] == key.pubkey }
    }

    /**
     * The relays a group's 10050 lists, or null if [event] is not one a group should sign: no
     * relays, anything but `relay` tags, content, or a relay a stranger could not reach
     * ([RelayUrlInput.relayUrlOrNull]: `wss://`, not loopback).
     */
    fun groupRelaysOf(event: Event): List<NormalizedRelayUrl>? = groupRelaysOf(event.kind, event.tags, event.content)

    fun groupRelaysOf(kind: Int, tags: Array<Array<String>>, content: String): List<NormalizedRelayUrl>? {
        if (kind != RELAYS_KIND || content.isNotEmpty() || tags.isEmpty()) return null
        val relays = tags.map { tag ->
            if (tag.size < 2 || tag[0] != RelayTag.TAG_NAME) return null
            RelayUrlInput.relayUrlOrNull(tag[1]) ?: return null
        }
        return relays.distinct()
    }

    /** The relays anyone's 10050 lists, as NIP-17 senders read them. */
    fun announcedRelaysOf(event: Event): List<NormalizedRelayUrl> =
        if (event.kind != RELAYS_KIND) emptyList() else event.tags.mapNotNull(RelayTag::parse)
}
