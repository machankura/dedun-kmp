package com.machankura.dedun.compose.nostr

import com.vitorpamplona.quartz.marmot.foundation.appEvents.MarmotAppEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.firstTagValue
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip17Dm.messages.ChatMessageEvent
import com.vitorpamplona.quartz.nip59Giftwrap.seals.SealedRumorEvent
import com.vitorpamplona.quartz.nip59Giftwrap.wraps.GiftWrapEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The envelope a direct message travels in, run against real secp256k1 and real NIP-44.
 *
 * Two of these assert properties that read like bugs and are not. The wrap names nobody,
 * so the group can only learn who sent it from the MLS frame around it; and the sender
 * cannot reopen their own message, because the key that sealed it was discarded. Both are
 * consequences of using a throwaway key, both are documented in
 * docs/marmot-direct-messages.md, and both are here so that a later change which quietly
 * reverses them fails rather than ships.
 */
class MarmotDirectMessageTest {
    private val alice = NostrSignerSync(KeyPair())
    private val bob = KeyPair()
    private val eve = KeyPair()

    private val bobPublicKey = bob.pubKey.toHexKey()
    private val at = 1_700_000_000L
    private val text = "the vote is at six, do not tell the room"

    private fun aliceWrapsForBob(content: String = text): GiftWrapEvent =
        MarmotDirectMessage.wrap(
            signer = alice,
            recipientPublicKey = bobPublicKey,
            kind = ChatMessageEvent.KIND,
            createdAt = at,
            tags = arrayOf(PTag.assemble(bobPublicKey, null)),
            content = content,
        )

    @Test
    fun `the recipient reads what the sender wrote`() {
        val opened = assertNotNull(MarmotDirectMessage.open(aliceWrapsForBob(), bob), "Bob could not open a wrap addressed to him")

        assertEquals(text, opened.rumor.content)
        assertEquals(alice.pubKey, opened.rumor.pubKey)
        assertEquals(ChatMessageEvent.KIND, opened.rumor.kind)
    }

    @Test
    fun `the sender cannot reopen their own message`() {
        // The throwaway key is gone, so this is unrecoverable by construction. Asserted
        // rather than merely documented, because the obvious "fix" -- persisting the
        // throwaway private key -- would be strictly worse than the identity-keyed wrap
        // this design was chosen over, and would reintroduce the attribution the
        // throwaway key exists to remove.
        assertNull(MarmotDirectMessage.open(aliceWrapsForBob(), alice.keyPair))
    }

    @Test
    fun `a bystander gets nothing and no exception`() {
        // Null rather than a throw: the caller is midway through processing a kind:445
        // that carries a real message for somebody, and an exception would abandon it.
        assertNull(MarmotDirectMessage.open(aliceWrapsForBob(), eve))
    }

    @Test
    fun `the wrap names nobody`() {
        val first = aliceWrapsForBob()
        val second = aliceWrapsForBob()

        assertNotEquals(alice.pubKey, first.pubKey, "the wrap is keyed to its sender")
        assertNotEquals(bobPublicKey, first.pubKey, "the wrap is keyed to its recipient")
        assertNotEquals(first.pubKey, second.pubKey, "the throwaway key is being reused")

        // Signed by the throwaway key, per NIP-59. The signature attributes nothing --
        // the signer is meaningless and discarded -- and keeping it is what makes this a
        // real gift wrap that GiftWrapEvent.create builds and unwrapOrNull opens.
        assertTrue(first.verify(), "the wrap does not verify against its own key")
        assertEquals(GiftWrapEvent.KIND, first.kind)
    }

    @Test
    fun `the seal is what binds the words to their author`() {
        val opened = assertNotNull(MarmotDirectMessage.open(aliceWrapsForBob(), bob))

        assertEquals(SealedRumorEvent.KIND, opened.seal.kind)
        // The inbound path checks exactly this pair against the MLS sender identity. It
        // is what replaces the MIP-03 pubkey check for kind:1059, so if the seal stops
        // being signed by the sender, the carve-out becomes a hole.
        assertEquals(alice.pubKey, opened.seal.pubKey)
        assertTrue(opened.seal.verify(), "the seal's signature does not verify")
        assertEquals(opened.seal.pubKey, opened.rumor.pubKey)
    }

    @Test
    fun `a seal from somebody else is still opened and is caught by its pubkey`() {
        // open() decrypts; it does not adjudicate. Eve can seal her own rumor to Bob and
        // wrap it, and Bob's key will open it -- what stops it being rendered as Alice's
        // is the inbound check that the seal's pubkey is the MLS sender's identity. This
        // test pins the half open() is responsible for: the pubkey it reports is Eve's.
        val eveSigner = NostrSignerSync(eve)
        val forged =
            MarmotDirectMessage.wrap(
                signer = eveSigner,
                recipientPublicKey = bobPublicKey,
                kind = ChatMessageEvent.KIND,
                createdAt = at,
                tags = arrayOf(PTag.assemble(bobPublicKey, null)),
                content = "alice here, send the funds",
            )

        val opened = assertNotNull(MarmotDirectMessage.open(forged, bob))

        assertEquals(eveSigner.pubKey, opened.seal.pubKey)
        assertNotEquals(alice.pubKey, opened.seal.pubKey)
    }

    @Test
    fun `the rumor id is the one the sender queued`() {
        val tags = arrayOf(PTag.assemble(bobPublicKey, null))

        // What the outbound queue computes before it has a signer, and what it later uses
        // to find the chat message to link and broadcast. If these ever diverge the
        // message is encrypted, stored, and silently never sent.
        val queued =
            EventHasher.hashId(
                alice.pubKey,
                at,
                ChatMessageEvent.KIND,
                tags,
                text,
            )

        val opened = assertNotNull(MarmotDirectMessage.open(aliceWrapsForBob(), bob))

        assertEquals(queued, opened.rumor.id)
        assertEquals(queued, MarmotDirectMessage.rumor(alice.pubKey, ChatMessageEvent.KIND, at, tags, text).id)
    }

    @Test
    fun `no layer is fuzzed into the past`() {
        val wrap = aliceWrapsForBob()
        val opened = assertNotNull(MarmotDirectMessage.open(wrap, bob))

        // NIP-59 defaults every one of these to randomWithTwoDays(). Inside MLS that only
        // scatters the bystander line up to two days out of position in every other
        // member's transcript, so all three carry the real message time.
        assertEquals(at, wrap.createdAt)
        assertEquals(at, opened.seal.createdAt)
        assertEquals(at, opened.rumor.createdAt)
    }

    @Test
    fun `the recipient is on the wrap where the group can see it`() {
        val wrap = aliceWrapsForBob()

        // Deliberate: it is what lets a bystander's transcript say who the message was
        // for. Removing it would hide the recipient from the group at the cost of the
        // named line -- see the decisions table in docs/marmot-direct-messages.md.
        assertEquals(bobPublicKey, wrap.tags.firstTagValue("p"))
    }

    @Test
    fun `garbage in the wrap does not become a message`() {
        val notAWrap =
            alice.signNormal<Event>(
                createdAt = at,
                kind = GiftWrapEvent.KIND,
                tags = arrayOf(PTag.assemble(bobPublicKey, null)),
                content = "not nip-44 at all",
            )

        assertNull(MarmotDirectMessage.open(notAWrap, bob))
    }

    // ---- The envelope, for current-profile rooms -----------------------------

    @Test
    fun `the envelope is unsigned by the sender and carries the wrap whole`() {
        val wrap = aliceWrapsForBob()
        val envelope = MarmotDirectMessage.envelope(alice.pubKey, bobPublicKey, wrap, at)

        // Six members and no signature: what a conformant decoder accepts, and what a
        // leak cannot republish as a signed statement by anybody.
        val json = envelope.toJson()
        assertTrue(!json.contains("\"sig\""), "the envelope carries a signature")
        assertEquals(alice.pubKey, MarmotAppEvent.decode(json).pubKey)
        assertEquals(MarmotDirectMessage.ENVELOPE_KIND, MarmotAppEvent.decode(json).kind)
        assertEquals(bobPublicKey, envelope.tags.firstTagValue("p"))

        // The wrap inside is the wrap, untouched, and Bob opens it as before.
        val inside = assertNotNull(MarmotDirectMessage.wrapInside(Event.fromJsonOrNull(json)!!))
        assertEquals(wrap.id, inside.id)
        assertEquals(text, assertNotNull(MarmotDirectMessage.open(inside, bob)).rumor.content)
    }

    @Test
    fun `the envelope's createdAt is the wrap's unfuzzed`() {
        val envelope = MarmotDirectMessage.envelope(alice.pubKey, bobPublicKey, aliceWrapsForBob(), at)

        assertEquals(at, envelope.createdAt)
    }

    @Test
    fun `something that is not an envelope holds no wrap`() {
        val wrap = aliceWrapsForBob()
        val wrongKind = Event(
            id = "1".repeat(64), pubKey = alice.pubKey, createdAt = at,
            kind = MarmotDirectMessage.ENVELOPE_KIND + 1, tags = emptyArray(), content = wrap.toJson(), sig = "",
        )
        val notAWrapInside = Event(
            id = "2".repeat(64), pubKey = alice.pubKey, createdAt = at,
            kind = MarmotDirectMessage.ENVELOPE_KIND, tags = emptyArray(), content = "not json", sig = "",
        )
        val sealInside = Event(
            id = "3".repeat(64), pubKey = alice.pubKey, createdAt = at,
            kind = MarmotDirectMessage.ENVELOPE_KIND, tags = emptyArray(),
            content = alice.signNormal<Event>(createdAt = at, kind = SealedRumorEvent.KIND, tags = emptyArray(), content = "").toJson(),
            sig = "",
        )

        assertNull(MarmotDirectMessage.wrapInside(wrongKind))
        assertNull(MarmotDirectMessage.wrapInside(notAWrapInside))
        assertNull(MarmotDirectMessage.wrapInside(sealInside))
    }
}
