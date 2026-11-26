package com.machankura.dedun.compose.nostr.groupdm

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip44Encryption.Nip44v2
import com.vitorpamplona.quartz.nip59Giftwrap.rumors.Rumor
import com.vitorpamplona.quartz.nip59Giftwrap.seals.SealEvent
import com.vitorpamplona.quartz.nip59Giftwrap.wraps.GiftWrapEvent

/**
 * NIP-59's two layers, opened and built with a conversation key that threshold ECDH gave,
 * rather than with a key this device holds. quartz's own `GiftWrapEvent` and `SealEvent` take
 * a signer, which the group does not have, so these are built by hand to the same shape
 * (docs/group-threshold-ecdh.md, Phase 3).
 */
object GroupDmWrap {
    private val nip44 = Nip44v2()

    /** The seal inside [wrap], signed by its own pubkey, or null. */
    fun open(wrap: Event, conversationKey: ByteArray): Event? = runCatching {
        if (wrap.kind != GiftWrapEvent.KIND) return null
        val seal = Event.fromJson(nip44.decrypt(wrap.content, conversationKey))
        seal.takeIf { it.kind == SealEvent.KIND && it.verify() }
    }.getOrNull()
}

object GroupDmSeal {
    private val nip44 = Nip44v2()

    /**
     * The rumor inside [seal], or null. NIP-59's anti-spoofing check is the caller's job in
     * quartz; here it is part of opening: a rumor whose pubkey is not the seal's is refused.
     * Its id is recomputed, never taken from the rumor (quartz's `mergeWith` says why).
     */
    fun open(seal: Event, conversationKey: ByteArray): Event? = runCatching {
        if (seal.kind != SealEvent.KIND) return null
        val rumor = Rumor.fromJson(nip44.decrypt(seal.content, conversationKey))
        if (rumor.pubKey != null && rumor.pubKey != seal.pubKey) return null
        val tags = rumor.tags ?: emptyArray()
        val createdAt = rumor.createdAt ?: return null
        val kind = rumor.kind ?: return null
        val content = rumor.content ?: ""
        val id = EventHasher.hashId(seal.pubKey, createdAt, kind, tags, content)
        Event(id, seal.pubKey, createdAt, kind, tags, content, "")
    }.getOrNull()

    /** The rumor as JSON, id included and no signature, as NIP-59 puts it inside a seal. */
    fun rumorJson(rumor: Event): String = Rumor.toJson(Rumor.create(rumor))

    /**
     * An unsigned seal from the group to whoever [conversationKey] is shared with: kind 13,
     * no tags, the rumor NIP-44-encrypted. The group signs it; nothing else changes it after.
     */
    fun build(rumor: Event, conversationKey: ByteArray, createdAt: Long): EventTemplate<Event> =
        EventTemplate(createdAt, SealEvent.KIND, emptyArray(), nip44.encrypt(rumorJson(rumor), conversationKey).encodePayload())

    /** An unsigned rumor, its id computed, as the group would author it. */
    fun rumor(author: HexKey, createdAt: Long, kind: Int, tags: Array<Array<String>>, content: String): Event =
        Event(EventHasher.hashId(author, createdAt, kind, tags, content), author, createdAt, kind, tags, content, "")
}
