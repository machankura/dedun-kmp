package com.machankura.dedun.compose.nostr.inbox

import com.vitorpamplona.quartz.experimental.decoupling.setup.EncryptionKeyListEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip59Giftwrap.rumors.RumorAssembler
import com.vitorpamplona.quartz.nip59Giftwrap.seals.SealEvent
import com.vitorpamplona.quartz.nip59Giftwrap.wraps.GiftWrapEvent
import fr.acinq.bitcoin.Crypto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The group inbox's wire, byte for byte where it matters (docs/group-inbox.md, Phase 2). The
 * group's FROST key is stood in for by a plain key here: what is under test is the layering, and
 * `SignedGroupKeyStateTest` shows the quorum signs a seal as the room.
 */
class InboxWireTest {
    private fun secret(label: String) = Crypto.sha256(label.encodeToByteArray())

    private val group = KeyPair(secret("the group's identity"))
    private val inboxSecret = secret("the inbox key")
    private val inboxKey = InboxWire.publicKeyOf(inboxSecret)

    private val alice = KeyPair(secret("alice"))
    private val aliceEncryptionSecret = secret("alice's encryption key")
    private val aliceEncryptionKey = InboxWire.publicKeyOf(aliceEncryptionSecret)

    private fun KeyPair.hex(): HexKey = pubKey.toHexKey()

    private fun rumor(author: HexKey, to: List<HexKey>, text: String, createdAt: Long = 1_700_000_000L): Event =
        RumorAssembler.assembleRumor(
            pubKey = author,
            ev = EventTemplate<Event>(createdAt, 14, to.map { arrayOf("p", it) }.toTypedArray(), text),
        )

    // ---- the ceremony's tags

    @Test
    fun `a proposal's tags carry the session, the generation, threshold 1 and the admins`() {
        val session = "ab".repeat(32)
        val admins = listOf("c".repeat(64), "b".repeat(64), "c".repeat(64))
        val tags = InboxKeyRitualEvents.assembleTags(session, generation = 2, participants = admins)

        assertEquals(session, InboxKeyRitualEvents.parseSessionId(tags))
        assertEquals(2, InboxKeyRitualEvents.parseGeneration(tags))
        assertEquals(1, InboxKeyRitualEvents.parseThreshold(tags))
        assertEquals(listOf("b".repeat(64), "c".repeat(64)), InboxKeyRitualEvents.parseParticipants(tags))
        assertNull(InboxKeyRitualEvents.parseParticipants(tags + arrayOf(arrayOf("p", "not a key"))))
    }

    // ---- the announcement

    @Test
    fun `the group's 10044 names its key alone, and quartz reads it`() {
        val template = InboxAnnouncement.keyTemplate(inboxKey, 1_700_000_000L)
        val signed = NostrSignerSync(group).sign(template)

        assertEquals(10044, signed.kind)
        assertEquals(inboxKey, InboxAnnouncement.groupKeyOf(signed))
        assertEquals(inboxKey, InboxAnnouncement.announcedKeyOf(signed))
        assertEquals(inboxKey, (signed as EncryptionKeyListEvent).encryptionKey()?.pubkey)
    }

    @Test
    fun `a 10044 with a second key, a nonce or content is not one the group would sign`() {
        val other = "d".repeat(64)
        assertNull(InboxAnnouncement.groupKeyOf(10044, arrayOf(arrayOf("n", inboxKey), arrayOf("n", other)), ""))
        assertNull(InboxAnnouncement.groupKeyOf(10044, arrayOf(arrayOf("n", inboxKey, "ab".repeat(16))), ""))
        assertNull(InboxAnnouncement.groupKeyOf(10044, arrayOf(arrayOf("n", inboxKey)), "hello"))
        assertNull(InboxAnnouncement.groupKeyOf(10044, arrayOf(arrayOf("n", inboxKey.uppercase())), ""))
        // Someone else's may carry more; the first key is still theirs.
        assertEquals(inboxKey, InboxAnnouncement.announcedKeyOf(
            Event("a".repeat(64), alice.hex(), 1, 10044, arrayOf(arrayOf("n", inboxKey), arrayOf("n", other)), "", ""),
        ))
    }

    @Test
    fun `the group's 10050 lists public relays only`() {
        val relay = assertNotNull(RelayUrlNormalizer.normalizeOrNull("wss://relay.example.com"))
        val template = InboxAnnouncement.relaysTemplate(listOf(relay), 1_700_000_000L)
        assertEquals(listOf(relay), InboxAnnouncement.groupRelaysOf(template.kind, template.tags, template.content))

        assertNull(InboxAnnouncement.groupRelaysOf(10050, arrayOf(arrayOf("relay", "ws://relay.example.com")), ""))
        assertNull(InboxAnnouncement.groupRelaysOf(10050, emptyArray(), ""))
        assertNull(InboxAnnouncement.groupRelaysOf(10050, arrayOf(arrayOf("r", "wss://relay.example.com")), ""))
    }

    // ---- the seal

    @Test
    fun `a seal from the inbox key opens with the recipient's encryption key`() {
        val reply = rumor(group.hex(), listOf(alice.hex()), "Thank you for writing")
        val seal = NostrSignerSync(group).sign(InboxSeal.template(reply, inboxSecret, aliceEncryptionKey, 1_700_000_000L))

        assertEquals(listOf(listOf("n", inboxKey)), seal.tags.map { it.toList() })
        val opened = assertNotNull(InboxSeal.open(seal, aliceEncryptionSecret))
        assertEquals(reply.id, opened.rumor.id)
        assertEquals("Thank you for writing", opened.rumor.content)
        assertEquals(inboxKey, opened.senderEncryptionKey)
    }

    @Test
    fun `a seal to a recipient with no 10044 opens with their identity`() {
        val reply = rumor(group.hex(), listOf(alice.hex()), "Hello")
        val seal = NostrSignerSync(group).sign(InboxSeal.template(reply, inboxSecret, alice.hex(), 1_700_000_000L))

        assertNotNull(InboxSeal.open(seal, assertNotNull(alice.privKey)))
        assertNull(InboxSeal.open(seal, aliceEncryptionSecret), "only the key it was sealed to opens it")
    }

    @Test
    fun `the group's own copy opens with the inbox key`() {
        val reply = rumor(group.hex(), listOf(alice.hex()), "Hello")
        val seal = NostrSignerSync(group).sign(InboxSeal.template(reply, inboxSecret, inboxKey, 1_700_000_000L))
        assertEquals("Hello", assertNotNull(InboxSeal.open(seal, inboxSecret)).rumor.content)
    }

    @Test
    fun `a seal from a sender who does not decouple opens with their identity`() {
        // A stock NIP-17 seal, as any app writes one, to the group's announced key.
        val message = rumor(alice.hex(), listOf(group.hex()), "Can I join?")
        val content = InboxWire.encrypt(InboxSeal.rumorJson(message), InboxWire.conversationKey(assertNotNull(alice.privKey), inboxKey))
        val seal = NostrSignerSync(alice).sign<Event>(1_700_000_000L, 13, emptyArray(), content)

        val opened = assertNotNull(InboxSeal.open(seal, inboxSecret))
        assertEquals("Can I join?", opened.rumor.content)
        assertEquals(alice.hex(), opened.senderEncryptionKey)
    }

    @Test
    fun `a seal from a sender who decoupled opens by its n tag, or by their 10044 without one`() {
        val message = rumor(alice.hex(), listOf(group.hex()), "From my other key")
        val content = InboxWire.encrypt(InboxSeal.rumorJson(message), InboxWire.conversationKey(aliceEncryptionSecret, inboxKey))

        val tagged = NostrSignerSync(alice).sign<Event>(1_700_000_000L, 13, arrayOf(arrayOf("n", aliceEncryptionKey)), content)
        assertEquals(aliceEncryptionKey, assertNotNull(InboxSeal.open(tagged, inboxSecret)).senderEncryptionKey)

        val untagged = NostrSignerSync(alice).sign<Event>(1_700_000_000L, 13, emptyArray(), content)
        assertNull(InboxSeal.open(untagged, inboxSecret), "without the tag or the 10044 there is nothing to try but the identity")
        assertNotNull(InboxSeal.open(untagged, inboxSecret, senderAnnouncedKey = aliceEncryptionKey))
    }

    @Test
    fun `a rumor claiming another author is refused`() {
        val forged = rumor(group.hex(), listOf(group.hex()), "I am the group")
        val content = InboxWire.encrypt(InboxSeal.rumorJson(forged), InboxWire.conversationKey(assertNotNull(alice.privKey), inboxKey))
        val seal = NostrSignerSync(alice).sign<Event>(1_700_000_000L, 13, emptyArray(), content)
        assertNull(InboxSeal.open(seal, inboxSecret))
    }

    @Test
    fun `an unsigned or tampered seal is refused`() {
        val reply = rumor(group.hex(), listOf(alice.hex()), "Hello")
        val template = InboxSeal.template(reply, inboxSecret, aliceEncryptionKey, 1_700_000_000L)
        val unsigned = Event("a".repeat(64), group.hex(), template.createdAt, template.kind, template.tags, template.content, "")
        assertNull(InboxSeal.open(unsigned, aliceEncryptionSecret))
    }

    // ---- the wrap

    @Test
    fun `a wrap names the identity and opens with the encryption key`() {
        val seal = NostrSignerSync(group).sign(InboxSeal.template(rumor(group.hex(), listOf(alice.hex()), "Hi"), inboxSecret, aliceEncryptionKey, 1L))
        val relay = RelayUrlNormalizer.normalizeOrNull("wss://inbox.example.com")
        val wrap = InboxWrap.wrap(seal, alice.hex(), aliceEncryptionKey, relay, createdAt = 2L)

        assertEquals(1059, wrap.kind)
        assertEquals(alice.hex(), InboxWrap.recipientOf(wrap))
        assertTrue(wrap.pubKey != group.hex() && wrap.pubKey != alice.hex(), "nothing outside names the sender")

        val (index, opened) = assertNotNull(InboxWrap.open(wrap, listOf(secret("a retired key"), aliceEncryptionSecret)))
        assertEquals(1, index)
        assertEquals(seal.id, opened.id)
        assertNull(InboxWrap.open(wrap, listOf(assertNotNull(alice.privKey))), "the identity does not open a wrap to the encryption key")
    }

    @Test
    fun `a wrap built here is still a NIP-59 wrap to quartz, when the keys are the same`() = runTest {
        val message = rumor(group.hex(), listOf(alice.hex()), "Plain NIP-17")
        val content = InboxWire.encrypt(InboxSeal.rumorJson(message), InboxWire.conversationKey(assertNotNull(group.privKey), alice.hex()))
        val seal = NostrSignerSync(group).sign<Event>(1L, 13, emptyArray(), content)
        val wrap = InboxWrap.wrap(seal, alice.hex(), alice.hex(), createdAt = 2L) as GiftWrapEvent

        val opened = assertNotNull(wrap.unwrapAndUnsealOrNull(NostrSignerInternal(alice)))
        assertEquals("Plain NIP-17", opened.content)
        assertEquals(group.hex(), opened.pubKey)
    }

    @Test
    fun `a wrap quartz builds to the inbox key opens here`() = runTest {
        // A sender whose app follows NIP-4E only so far as encrypting to the announced key.
        val message = rumor(alice.hex(), listOf(group.hex()), "Quartz wrote this")
        val seal = NostrSignerSync(alice).sign<SealEvent>(
            1L, 13, emptyArray(),
            InboxWire.encrypt(InboxSeal.rumorJson(message), InboxWire.conversationKey(assertNotNull(alice.privKey), inboxKey)),
        )
        val wrap = GiftWrapEvent.create(event = seal, recipientPubKey = inboxKey, createdAt = 2L)

        val (_, unwrapped) = assertNotNull(InboxWrap.open(wrap, listOf(inboxSecret)))
        assertEquals("Quartz wrote this", assertNotNull(InboxSeal.open(unwrapped, inboxSecret)).rumor.content)
    }

    // ---- the manifest

    private val manifest = InboxReplyManifest(
        generation = 3,
        seals = listOf(
            InboxReplyManifest.Entry(alice.hex(), aliceEncryptionKey, """{"kind":10044}"""),
            InboxReplyManifest.Entry(group.hex(), inboxKey, null),
        ),
        groupCopy = 1,
    )

    @Test
    fun `a manifest opens only with the inbox key it was sealed under`() {
        val sealed = manifest.seal(inboxSecret)
        assertEquals(manifest, InboxReplyManifest.open(sealed, inboxSecret))
        assertNull(InboxReplyManifest.open(sealed, secret("another generation")))
        assertEquals(sealed, InboxReplyManifest.sealedIn(arrayOf(InboxReplyManifest.tag(sealed), arrayOf("x", "y"))))
        assertNull(InboxReplyManifest.sealedIn(arrayOf(InboxReplyManifest.tag(sealed), InboxReplyManifest.tag(sealed))))
    }

    @Test
    fun `a manifest with a stray field, a missing one or a copy out of range is none`() {
        val encoded = manifest.encode()
        assertEquals(manifest, InboxReplyManifest.decode(encoded))
        assertNull(InboxReplyManifest.decode(encoded.replaceFirst("{\"v\":1", "{\"v\":1,\"extra\":true")))
        assertNull(InboxReplyManifest.decode(encoded.replace(",\"group_copy\":1", "")))
        assertNull(InboxReplyManifest.decode(encoded.replace("\"group_copy\":1", "\"group_copy\":2")))
        assertNull(InboxReplyManifest.decode(encoded.replace("\"v\":1", "\"v\":2")))
    }

    // ---- the conversation

    @Test
    fun `a message and its reply are one conversation, whatever the order of its people`() {
        val bob = KeyPair(secret("bob")).hex()
        val incoming = rumor(alice.hex(), listOf(group.hex(), bob), "To the group and Bob")
        val reply = rumor(group.hex(), listOf(bob, alice.hex()), "To both")

        assertEquals(listOf(alice.hex(), bob).sorted(), InboxConversation.participantsOf(incoming, group.hex()))
        assertEquals(InboxConversation.idOf(incoming, group.hex()), InboxConversation.idOf(reply, group.hex()))
        assertTrue(InboxConversation.idOf(rumor(alice.hex(), listOf(group.hex()), "Just us"), group.hex()) != InboxConversation.idOf(incoming, group.hex()))
    }

    @Test
    fun `the inbox key's hex is the x-only key of its secret`() {
        assertEquals(64, inboxKey.length)
        assertEquals(32, inboxKey.hexToByteArray().size)
        assertEquals(inboxKey, inboxKey.lowercase())
    }
}
