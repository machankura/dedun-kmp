package com.machankura.dedun.compose.nostr.inbox

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip59Giftwrap.rumors.Rumor
import com.vitorpamplona.quartz.nip59Giftwrap.seals.SealEvent

/**
 * A NIP-17 seal with a decoupled encryption key, as nostr-protocol/nips#2361 has it
 * (docs/group-inbox.md):
 *
 * ```
 * seal (13)  pubkey  = the sender's identity                 signed by the identity
 *            tags    = [["n", the sender's encryption key]]  when it is not the identity
 *            content = nip44(rumor, conv(sender's encryption secret, receiver's encryption key))
 * ```
 *
 * The split is what lets a group answer at all: the admins hold the encryption secret and can
 * write the content; only the quorum can sign the seal. A group's seal is therefore built here
 * as an unsigned template, and signed by the FROST session.
 */
object InboxSeal {
    const val KIND = SealEvent.KIND
    const val ENCRYPTION_KEY_TAG = "n"

    /**
     * The unsigned seal of [rumor] from the inbox key [inboxSecret] to [recipientEncryptionKey]:
     * the recipient's announced 10044 key, their identity if they announce none, or the inbox
     * key itself for the group's own copy.
     */
    fun template(
        rumor: Event,
        inboxSecret: ByteArray,
        recipientEncryptionKey: HexKey,
        createdAt: Long,
    ): EventTemplate<Event> = EventTemplate(
        createdAt = createdAt,
        kind = KIND,
        tags = arrayOf(arrayOf(ENCRYPTION_KEY_TAG, InboxWire.publicKeyOf(inboxSecret))),
        content = InboxWire.encrypt(rumorJson(rumor), InboxWire.conversationKey(inboxSecret, recipientEncryptionKey)),
    )

    /** The rumor as NIP-59 puts it inside a seal: its id, no signature. */
    fun rumorJson(rumor: Event): String = Rumor.toJson(Rumor.create(rumor))

    /** The `n` a seal names, or null when it names none or a malformed one. */
    fun encryptionKeyOf(tags: Array<Array<String>>): HexKey? {
        val named = tags.filter { it.isNotEmpty() && it[0] == ENCRYPTION_KEY_TAG }
        if (named.size != 1) return null
        return named.single().getOrNull(1)?.lowercase()?.takeIf(InboxWire::isHex32)
    }

    /**
     * Which key the sender encrypted with, by #2361's rule and quartz's: the seal's `n` if it
     * has one, else the key the sender's own 10044 announces if this device has seen it, else
     * the sender's identity.
     */
    fun senderEncryptionKey(seal: Event, senderAnnouncedKey: HexKey?): HexKey =
        encryptionKeyOf(seal.tags) ?: senderAnnouncedKey ?: seal.pubKey

    data class Opened(val seal: Event, val rumor: Event, val senderEncryptionKey: HexKey)

    /**
     * [seal], received, opened with [receiverSecret]. Null for every way that can fail: not a
     * seal, a signature that does not verify, a key that does not open it, a rumor that is not
     * one, or a rumor claiming an author the seal does not have -- NIP-17's impersonation check,
     * the one thing that makes the seal's signature mean the rumor's author.
     *
     * If the seal names no `n`, [senderAnnouncedKey] is tried before the identity, and then the
     * identity, since a sender's app may decouple without writing the tag.
     */
    fun open(seal: Event, receiverSecret: ByteArray, senderAnnouncedKey: HexKey? = null): Opened? {
        if (seal.kind != KIND || !seal.verify()) return null
        val candidates = buildList {
            encryptionKeyOf(seal.tags)?.let(::add)
            senderAnnouncedKey?.let(::add)
            add(seal.pubKey)
        }.distinct()
        for (key in candidates) {
            val rumor = rumorInside(seal.content, InboxWire.conversationKey(receiverSecret, key), seal.pubKey) ?: continue
            return Opened(seal, rumor, key)
        }
        return null
    }

    /**
     * The rumor inside a seal's [content] under [conversationKey], with its id computed rather
     * than trusted, or null. [author] is the seal's pubkey: a rumor claiming anyone else is
     * refused here, so no caller can forget to.
     */
    fun rumorInside(content: String, conversationKey: ByteArray, author: HexKey): Event? {
        val json = InboxWire.decrypt(content, conversationKey) ?: return null
        val rumor = runCatching { Rumor.fromJson(json) }.getOrNull() ?: return null
        if (rumor.pubKey != author) return null
        val kind = rumor.kind ?: return null
        val createdAt = rumor.createdAt ?: return null
        val tags = rumor.tags ?: emptyArray()
        val content = rumor.content ?: ""
        val id = EventHasher.hashId(author, createdAt, kind, tags, content)
        return Event(id, author, createdAt, kind, tags, content, "")
    }
}
