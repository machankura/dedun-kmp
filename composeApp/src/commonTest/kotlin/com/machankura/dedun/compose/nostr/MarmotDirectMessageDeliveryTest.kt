package com.machankura.dedun.compose.nostr

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip17Dm.messages.ChatMessageEvent
import com.vitorpamplona.quartz.nip59Giftwrap.seals.SealedRumorEvent
import com.vitorpamplona.quartz.nip59Giftwrap.wraps.GiftWrapEvent
import com.vitorpamplona.quartz.nipC7Chats.ChatEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * What a device decides to do with an arriving wrap.
 *
 * This is where the check that replaces MIP-03 for kind:1059 is tested. The wrap itself
 * names nobody, so everything a transcript says about who sent a private message rests on
 * one comparison -- the seal's author against the identity MLS authenticated -- and if it
 * stops being made, a member can have their words attributed to somebody else, or
 * somebody else's attributed to them. The rest of the inbound path is Room-backed and
 * cannot be unit-tested here, which is exactly why the decision was separated from the
 * filing of it.
 *
 * `Ours` carries a second kind of risk. It is indistinguishable from `Unreadable` by
 * decryption alone -- we cannot open our own wrap either -- and getting it wrong does not
 * fail loudly: it files a placeholder over the only copy of the sender's own words.
 */
class MarmotDirectMessageDeliveryTest {
    private val alice = NostrSignerSync(KeyPair())
    private val bob = KeyPair()
    private val eve = NostrSignerSync(KeyPair())

    private val alicePublicKey = alice.pubKey
    private val bobPublicKey = bob.pubKey.toHexKey()

    private val at = 1_700_000_000L
    private val text = "the vote is at six"

    private fun wrapFor(
        signer: NostrSignerSync = alice,
        recipient: String = bobPublicKey,
        kind: Int = ChatMessageEvent.KIND,
        content: String = text,
    ): GiftWrapEvent =
        MarmotDirectMessage.wrap(
            signer = signer,
            recipientPublicKey = recipient,
            kind = kind,
            createdAt = at,
            tags = arrayOf(PTag.assemble(recipient, null)),
            content = content,
        )

    @Test
    fun `the recipient gets the words`() {
        val delivery = MarmotDirectMessage.classify(wrapFor(), bob, alicePublicKey)

        val readable = assertIs<MarmotDirectMessage.Delivery.Readable>(delivery)
        assertEquals(text, readable.opened.rumor.content)
        assertEquals(alicePublicKey, readable.opened.seal.pubKey)
    }

    @Test
    fun `a bystander gets a line with nothing in it`() {
        val carol = KeyPair()

        assertEquals(
            MarmotDirectMessage.Delivery.Unreadable,
            MarmotDirectMessage.classify(wrapFor(), carol, alicePublicKey),
        )
    }

    @Test
    fun `our own message is ours even though we cannot open it`() {
        // The case that destroys data if it is missed. Alice cannot decrypt the wrap she
        // sent -- its key was discarded -- so nothing about the payload distinguishes this
        // from a bystander's view. Only the MLS sender identity does. Were it to come back
        // Unreadable, the inbound path would file an empty line over the row sendChatMessage
        // wrote, which is the only copy of those words anywhere.
        val delivery = MarmotDirectMessage.classify(wrapFor(), alice.keyPair, alicePublicKey)

        assertEquals(MarmotDirectMessage.Delivery.Ours, delivery)
    }

    @Test
    fun `ours is decided before anything is opened`() {
        // Same as above, from the other direction: a wrap Alice could never open, that is
        // not even addressed to her, is still hers if MLS says she sent it. Nothing here
        // may depend on decryption succeeding.
        val toEve = wrapFor(recipient = eve.pubKey)

        assertEquals(
            MarmotDirectMessage.Delivery.Ours,
            MarmotDirectMessage.classify(toEve, alice.keyPair, alicePublicKey),
        )
    }

    @Test
    fun `a message with no sender identity is dropped`() {
        // Attribution has no other source. A line that cannot say who sent it must not be
        // rendered at all rather than be attributed to the wrap, which names nobody.
        val delivery = MarmotDirectMessage.classify(wrapFor(), bob, null)

        assertIs<MarmotDirectMessage.Delivery.Rejected>(delivery)
    }

    @Test
    fun `words sealed by one member and sent by another are dropped`() {
        // The check that replaces MIP-03. Eve seals to Bob and sends; if MLS says the
        // sender was Alice, the two disagree about who is speaking and the message is not
        // rendered as anybody's. Without this, the seal inside is free to claim an author
        // the frame does not support.
        val delivery = MarmotDirectMessage.classify(wrapFor(signer = eve), bob, alicePublicKey)

        val rejected = assertIs<MarmotDirectMessage.Delivery.Rejected>(delivery)
        assertTrue(
            rejected.reason.contains(eve.pubKey) && rejected.reason.contains(alicePublicKey),
            "the reason should name both parties to the disagreement: ${rejected.reason}",
        )
    }

    @Test
    fun `a seal relabelled with another author cannot even be opened`() {
        // Eve's seal with Alice's pubkey written over it. This never reaches the author
        // check, because NIP-44 derives the conversation key from the pubkey being
        // claimed: relabelling a seal makes it undecryptable by the person it was
        // encrypted for. The label is bound to the key, not merely asserted alongside it.
        //
        // So the outcome is Unreadable rather than Rejected -- Bob genuinely cannot read
        // it -- and the group sees a private message it cannot open, which is the truth.
        val forged = resealedAs(alicePublicKey, sealedBy = eve, to = bob)

        assertEquals(
            MarmotDirectMessage.Delivery.Unreadable,
            MarmotDirectMessage.classify(forged, bob, alicePublicKey),
        )
    }

    @Test
    fun `a seal tampered with after signing is dropped`() {
        // Alice's own seal with its timestamp altered. Everything the previous test
        // relies on still holds -- the pubkey is Alice's, so the content decrypts -- and
        // the pubkey matches what MLS says, so the author check passes. Only the
        // signature is left, and it is what catches this: the id no longer commits to the
        // fields, so verify() fails.
        val tampered = tamperedTimestamp(sealedBy = alice, to = bob)

        val delivery = MarmotDirectMessage.classify(tampered, bob, alicePublicKey)

        val rejected = assertIs<MarmotDirectMessage.Delivery.Rejected>(delivery)
        assertTrue(
            rejected.reason.contains("does not verify"),
            "expected a signature rejection, got: ${rejected.reason}",
        )
    }

    @Test
    fun `a rumor by somebody other than the sealer is dropped`() {
        // A correctly signed seal by Alice, wrapped around a rumor claiming to be Eve's.
        // Everything outside the innermost layer checks out; the words would be filed
        // under whoever the rumor names if this were not caught.
        val mismatched = sealAroundForeignRumor(sealedBy = alice, rumorFrom = eve.pubKey, to = bobPublicKey)

        val delivery = MarmotDirectMessage.classify(mismatched, bob, alicePublicKey)

        val rejected = assertIs<MarmotDirectMessage.Delivery.Rejected>(delivery)
        assertTrue(
            rejected.reason.contains("inside a seal by"),
            "expected a rumor/seal author mismatch, got: ${rejected.reason}",
        )
    }

    @Test
    fun `a rumor of the wrong kind is dropped`() {
        // kind:9 is what an ordinary group message uses. Arriving gift wrapped it is not a
        // direct message this version knows how to render, and rendering it as one would
        // put a group message behind a lock.
        val delivery = MarmotDirectMessage.classify(wrapFor(kind = ChatEvent.KIND), bob, alicePublicKey)

        val rejected = assertIs<MarmotDirectMessage.Delivery.Rejected>(delivery)
        assertTrue(
            rejected.reason.contains("${ChatEvent.KIND}"),
            "expected the offending kind in the reason, got: ${rejected.reason}",
        )
    }

    /** [sealedBy]'s seal with [claimedAuthor] written over its pubkey, wrapped for [to]. */
    private fun resealedAs(
        claimedAuthor: String,
        sealedBy: NostrSignerSync,
        to: KeyPair,
    ): GiftWrapEvent {
        val honest = MarmotDirectMessage.wrap(
            signer = sealedBy,
            recipientPublicKey = to.pubKey.toHexKey(),
            kind = ChatMessageEvent.KIND,
            createdAt = at,
            tags = emptyArray(),
            content = text,
        )
        val seal = MarmotDirectMessage.open(honest, to)!!.seal

        val relabelled =
            Event(
                id = seal.id,
                pubKey = claimedAuthor,
                createdAt = seal.createdAt,
                kind = SealedRumorEvent.KIND,
                tags = seal.tags,
                content = seal.content,
                sig = seal.sig,
            )

        return GiftWrapEvent.create(
            event = relabelled,
            recipientPubKey = to.pubKey.toHexKey(),
            createdAt = at,
        )
    }

    /** [sealedBy]'s own seal, its timestamp altered after signing, wrapped for [to]. */
    private fun tamperedTimestamp(
        sealedBy: NostrSignerSync,
        to: KeyPair,
    ): GiftWrapEvent {
        val honest = MarmotDirectMessage.wrap(
            signer = sealedBy,
            recipientPublicKey = to.pubKey.toHexKey(),
            kind = ChatMessageEvent.KIND,
            createdAt = at,
            tags = emptyArray(),
            content = text,
        )
        val seal = MarmotDirectMessage.open(honest, to)!!.seal

        val altered =
            Event(
                id = seal.id,
                pubKey = seal.pubKey,
                createdAt = seal.createdAt + 86_400,
                kind = SealedRumorEvent.KIND,
                tags = seal.tags,
                content = seal.content,
                sig = seal.sig,
            )

        return GiftWrapEvent.create(
            event = altered,
            recipientPubKey = to.pubKey.toHexKey(),
            createdAt = at,
        )
    }

    /** A properly signed seal by [sealedBy] whose rumor claims [rumorFrom] wrote it. */
    private fun sealAroundForeignRumor(
        sealedBy: NostrSignerSync,
        rumorFrom: String,
        to: String,
    ): GiftWrapEvent {
        val rumor = MarmotDirectMessage.rumor(rumorFrom, ChatMessageEvent.KIND, at, emptyArray(), text)

        val seal =
            sealedBy.signNormal<SealedRumorEvent>(
                createdAt = at,
                kind = SealedRumorEvent.KIND,
                tags = emptyArray(),
                content = sealedBy.nip44Encrypt(rumor.toJson(), to),
            )

        return GiftWrapEvent.create(event = seal, recipientPubKey = to, createdAt = at)
    }
}
