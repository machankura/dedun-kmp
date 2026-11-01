package com.machankura.dedun.compose.database.model

import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip59Giftwrap.seals.SealedRumorEvent
import com.vitorpamplona.quartz.nip59Giftwrap.wraps.GiftWrapEvent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Who can open a gift wrap, and what becomes of everyone else's.
 *
 * NIP-59 encrypts a wrap under ECDH(ephemeralPriv, recipientPub), and
 * [GiftWrapEvent.create] discards that ephemeral key before it returns. The
 * recipient named in the `p` tag is therefore the only party who can ever unseal
 * one -- the sender included. Reading a wrap that is not ours is not a decryption
 * that might fail, it is one that cannot be attempted, and the code that tried
 * anyway threw `IllegalStateException: Invalid Mac` out of Nip44 and took the
 * enclosing Room transaction down with it, so the event was rolled back and
 * re-fetched on every later sync.
 *
 * These run real secp256k1 rather than recorded fixtures on purpose: the property
 * under test is about the key agreement itself, and a fixture would only prove the
 * fixture still parses.
 */
class GiftWrapMessageTest {

    private val us = KeyPair()
    private val peer = KeyPair()
    private val stranger = KeyPair()

    /**
     * A real wrap, sealed the way DatabaseChatRepository seals one and then mapped
     * into the entity the way NostrEvent.toGiftWrapMessageWithReceiverPTag maps it.
     */
    private fun wrap(
        sender: KeyPair,
        recipient: KeyPair,
    ): GiftWrapMessage {
        val signer = NostrSignerSync(sender)

        val seal = signer.signNormal<SealedRumorEvent>(
            createdAt = SEALED_AT,
            kind = SealedRumorEvent.KIND,
            tags = emptyArray(),
            content = signer.nip44Encrypt(
                plaintext = """{"kind":14,"content":"dumela"}""",
                toPublicKey = recipient.pubKey.toHexKey(),
            ),
        )

        val giftWrap = GiftWrapEvent.create(
            event = seal,
            recipientPubKey = recipient.pubKey.toHexKey(),
            createdAt = WRAPPED_AT,
        )

        return GiftWrapMessage(
            id = giftWrap.id,
            publicKey = giftWrap.pubKey,
            receiverPublicKey = recipient.pubKey.toHexKey(),
            receiverRelayHit = null,
            content = giftWrap.content,
            signature = giftWrap.sig,
            nostrEventId = giftWrap.id,
            createdAt = Instant.fromEpochSeconds(giftWrap.createdAt),
        )
    }

    @Test
    fun `a wrap addressed to us gives up its seal`() = runTest {
        val seal = wrap(sender = peer, recipient = us).decryptGiftWrapSeal(us)

        assertNotNull(seal)
        // Only the wrap was anonymous. The seal inside carries the real sender, which
        // is what lets the impersonation check downstream compare it to the payload.
        assertEquals(peer.pubKey.toHexKey(), seal.publicKey)
    }

    @Test
    fun `someone else's mail comes back null rather than throwing`() = runTest {
        // The event from the crash report: a wrap between two other people, pulled in
        // by a filter that named a peer where it should have named us.
        val theirs = wrap(sender = stranger, recipient = peer)

        assertNull(theirs.decryptGiftWrapSeal(us))
    }

    @Test
    fun `not even the sender can reopen what they sent`() = runTest {
        // What the old branch called "a message we may have sent" and tried to decrypt
        // regardless. The key that encrypted it no longer exists anywhere; holding the
        // sending identity buys nothing back.
        val ours = wrap(sender = us, recipient = peer)

        assertNull(ours.decryptGiftWrapSeal(us))
    }

    @Test
    fun `isAddressedTo reads the p tag whatever case it arrived in`() {
        val message = wrap(sender = peer, recipient = us)

        assertTrue(message.isAddressedTo(us))
        assertFalse(message.isAddressedTo(peer))
        assertTrue(
            message
                .copy(receiverPublicKey = message.receiverPublicKey.uppercase())
                .isAddressedTo(us),
        )
    }

    @Test
    fun `isAddressedTo answers exactly what unsealing would`() = runTest {
        // NostrDao skips indexing on isAddressedTo and throws GiftWrapUnsealException on
        // a null seal. Should those two ever disagree, one path or the other is wrong:
        // either mail we can open is skipped, or the transaction rolls back again.
        listOf(
            wrap(sender = peer, recipient = us),
            wrap(sender = stranger, recipient = peer),
            wrap(sender = us, recipient = peer),
        ).forEach { message ->
            assertEquals(
                message.isAddressedTo(us),
                message.decryptGiftWrapSeal(us) != null,
                "isAddressedTo and decryptGiftWrapSeal disagree on ${message.id}",
            )
        }
    }

    private companion object {
        const val SEALED_AT = 1_700_000_000L
        const val WRAPPED_AT = 1_700_000_100L
    }
}
