package com.machankura.dedun.compose.nostr.inbox

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip59Giftwrap.wraps.GiftWrapEvent
import com.vitorpamplona.quartz.utils.TimeUtils

/**
 * A NIP-59 gift wrap with a decoupled encryption key, as nostr-protocol/nips#2361 has it:
 *
 * ```
 * gift wrap (1059)  pubkey  = a fresh random key
 *                   tags    = [["p", the recipient's identity, relay]]
 *                   content = nip44(seal, conv(wrapper secret, the recipient's encryption key))
 * ```
 *
 * quartz's [GiftWrapEvent.create] encrypts to the key it tags, which is right when they are the
 * same and wrong here, so the wrap is built by hand. The `p` stays the identity: it is how a
 * recipient's relays and apps find their mail, and it names nothing about which key opens it.
 * That is also why a reader with several keys has to try them ([open]).
 */
object InboxWrap {
    const val KIND = GiftWrapEvent.KIND

    /**
     * [seal], already signed, wrapped for [recipientIdentity] under [recipientEncryptionKey] with a
     * throwaway key nobody keeps.
     */
    fun wrap(
        seal: Event,
        recipientIdentity: HexKey,
        recipientEncryptionKey: HexKey,
        relayHint: NormalizedRelayUrl? = null,
        createdAt: Long = TimeUtils.randomWithTwoDays(),
    ): Event {
        val signer = NostrSignerSync(KeyPair())
        val throwaway = requireNotNull(signer.keyPair.privKey)
        val content = InboxWire.encrypt(seal.toJson(), InboxWire.conversationKey(throwaway, recipientEncryptionKey))
        return signer.sign<GiftWrapEvent>(
            createdAt = createdAt,
            kind = KIND,
            tags = arrayOf(PTag.assemble(recipientIdentity, relayHint)),
            content = content,
        )
    }

    /** Who a wrap is addressed to: its first `p`, which is a routing hint and proves nothing. */
    fun recipientOf(wrap: Event): HexKey? =
        wrap.tags.firstOrNull { it.size >= 2 && it[0] == "p" }?.get(1)

    /**
     * The seal inside [wrap], and the index into [secrets] of the key that opened it, or null if
     * none does. Newest key first is the caller's order to give: a wrap is far likelier to be to
     * the key the group announces now than to one it retired.
     */
    fun open(wrap: Event, secrets: List<ByteArray>): Pair<Int, Event>? {
        if (wrap.kind != KIND || !wrap.verify()) return null
        secrets.forEachIndexed { index, secret ->
            val json = InboxWire.decrypt(wrap.content, InboxWire.conversationKey(secret, wrap.pubKey)) ?: return@forEachIndexed
            val seal = Event.fromJsonOrNull(json) ?: return@forEachIndexed
            if (seal.kind == InboxSeal.KIND) return index to seal
        }
        return null
    }
}
