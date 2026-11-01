package com.machankura.dedun.compose.nostr

import com.vitorpamplona.quartz.marmot.foundation.appEvents.MarmotAppEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip44Encryption.Nip44
import com.vitorpamplona.quartz.nip17Dm.messages.ChatMessageEvent
import com.vitorpamplona.quartz.nip59Giftwrap.rumors.RumorAssembler
import com.vitorpamplona.quartz.nip59Giftwrap.seals.SealedRumorEvent
import com.vitorpamplona.quartz.nip59Giftwrap.wraps.GiftWrapEvent

/**
 * A one-to-one message carried inside a Marmot group, as the application payload of a
 * kind:445 group event. See docs/marmot-direct-messages.md.
 *
 * The message is a stock NIP-59 gift wrap: a throwaway-keyed kind:1059 around a
 * sender-signed kind:13 seal around the kind:14 rumor that holds the words. Every member
 * of the group decrypts the MLS layer and sees the wrap; only the recipient can open it.
 *
 * What carries the wrap depends on the room's profile. A legacy room carries the wrap
 * itself as the payload -- signed by its throwaway key, with a `pubkey` that names
 * nobody -- which is what every build before docs/marmot-v2.md Phase 2 sent and reads,
 * and what the carve-out in `MarmotInboundManager.mip03Rejection` exists to admit. A
 * current-profile room carries it inside an [envelope]: an unsigned Marmot app event of
 * [ENVELOPE_KIND] whose `pubkey` is the sender's account and whose `content` is the
 * wrap's JSON. A conformant decoder refuses a payload with a `sig` member and one whose
 * author is not the MLS sender, and the wrap fails both; the envelope passes both, and
 * keeps every property the wrap was chosen for -- see docs/marmot-direct-messages.md --
 * because the envelope is unsigned and so a claim rather than a proof, and the wrap
 * inside it is still signed by nobody.
 *
 * Everything here is a pure function of its arguments, with no database and no MLS state,
 * which is what makes it testable — Room-backed code cannot be unit-tested in this project.
 *
 * **Nothing built here may ever be broadcast.** It is a genuine, correctly signed NIP-59
 * gift wrap, indistinguishable from one the NIP-17 path would be right to publish, so the
 * only thing keeping it off a relay is the tables it is kept out of: a Marmot direct
 * message lives in `MarmotInnerEvent`, never in `GiftWrapPayload` or `GiftWrapMessage`.
 */
object MarmotDirectMessage {
    /**
     * The app kind of the envelope a current-profile room carries a direct message in.
     *
     * In the app's own range, beside the chronicle (30327), its request (30328) and the
     * subgroup certificate (30329): a kind that exists only inside MLS and that no relay
     * ever sees. Another client renders it as a kind it does not know, which is the
     * "sent a private message" line and nothing more -- the same thing a bystander in
     * this app sees.
     */
    const val ENVELOPE_KIND: Kind = 30330

    /**
     * A wrap that opened, both layers of it.
     *
     * The seal is returned alongside the rumor because it is the only layer that names
     * the sender, and the caller has to bind it to the MLS sender identity before it
     * believes a word — see [MarmotDirectMessage] and the inbound path.
     */
    data class Opened(
        val seal: Event,
        val rumor: Event,
    )

    /**
     * What this device should do with an arriving wrap.
     *
     * Separated from the filing of it so the decision can be tested: the persistence it
     * drives needs a database, which cannot be unit-tested in this project, and the
     * decision includes the check that replaces MIP-03 for kind:1059. That check is the
     * only thing standing between somebody else's words and being rendered as a member's
     * own, so it is not somewhere to rely on integration testing that does not exist.
     */
    sealed interface Delivery {
        /**
         * Our own message coming back, which we cannot open -- the wrap's key was
         * discarded at send time -- and so cannot tell apart from [Unreadable] by
         * decryption alone. Only the MLS sender identity distinguishes them.
         *
         * File nothing: the row written on send holds the only copy of these words, and
         * an unreadable line would replace them with a placeholder.
         */
        data object Ours : Delivery

        /** Somebody else's. The group sees that it happened, and to whom. */
        data object Unreadable : Delivery

        /** Addressed to us, opened, and consistent with the leaf that sent it. */
        data class Readable(
            val opened: Opened,
        ) : Delivery

        /** Something is wrong with it. Drop it; do not render it as anybody's. */
        data class Rejected(
            val reason: String,
        ) : Delivery
    }

    /**
     * Decides what [wrap] is to this device, given the identity MLS says sent it.
     *
     * [senderIdentity] is not optional and is not read from the payload: a wrap names
     * nobody, so the MLS frame is the only source of who sent it. Absent one there is
     * nothing to attribute a line to and nothing to check the seal against.
     */
    fun classify(
        wrap: Event,
        me: KeyPair,
        senderIdentity: HexKey?,
    ): Delivery {
        if (senderIdentity == null) {
            return Delivery.Rejected("no MLS sender identity to attribute it to")
        }

        if (senderIdentity == me.pubKey.toHexKey()) return Delivery.Ours

        val opened = open(wrap, me) ?: return Delivery.Unreadable

        // The seal is the one layer the sender signs. Binding it to the leaf MLS says
        // sent this message is what replaces MIP-03's pubkey check for kind:1059 --
        // a verified signature bound to a leaf, rather than a plaintext field compared
        // to one. A divergence here is somebody passing off words as another member's.
        if (opened.seal.pubKey != senderIdentity) {
            return Delivery.Rejected(
                "sealed by ${opened.seal.pubKey} but sent by $senderIdentity",
            )
        }

        if (!opened.seal.verify()) {
            return Delivery.Rejected("the seal's signature does not verify")
        }

        if (opened.rumor.pubKey != opened.seal.pubKey) {
            return Delivery.Rejected(
                "rumor by ${opened.rumor.pubKey} inside a seal by ${opened.seal.pubKey}",
            )
        }

        if (opened.rumor.kind != ChatMessageEvent.KIND) {
            return Delivery.Rejected("unsupported rumor kind ${opened.rumor.kind}")
        }

        return Delivery.Readable(opened)
    }

    /**
     * Builds the payload for a direct message to [recipientPublicKey].
     *
     * [createdAt] is used unfuzzed on all three layers. NIP-59 randomises the wrap and
     * the seal by up to two days to frustrate correlation at a relay, and both
     * `GiftWrapEvent.create` and `SealedRumorEvent.create` default to that; there is no
     * relay at this layer, the kind:445 around it already carries the true time, and
     * fuzzing would only scatter the "sent a private message" line up to two days out of
     * position in every other member's transcript.
     *
     * The rumor is assembled from exactly the fields the caller queued, so its id is the
     * one the recipient computes after unwrapping — and, on the way out, the one the
     * outbound path uses to find the chat message to link and broadcast.
     */
    fun wrap(
        signer: NostrSignerSync,
        recipientPublicKey: HexKey,
        kind: Kind,
        createdAt: Long,
        tags: Array<Array<String>>,
        content: String,
        recipientRelayHint: NormalizedRelayUrl? = null,
    ): GiftWrapEvent {
        val rumor = rumor(signer.pubKey, kind, createdAt, tags, content)

        // The only layer that names the sender, and the only one they sign. Everything
        // the recipient believes about who wrote this rests on this signature.
        //
        // Built by hand rather than through SealedRumorEvent.create, which is suspend,
        // takes a NostrSigner rather than the sync signer the outbound path holds, and
        // defaults createdAt to the two-day fuzz.
        val seal =
            signer.signNormal<SealedRumorEvent>(
                createdAt = createdAt,
                kind = SealedRumorEvent.KIND,
                tags = emptyArray(),
                content = signer.nip44Encrypt(rumor.toJson(), recipientPublicKey),
            )

        // create() mints and discards its own random key -- "GiftWrap is always a random
        // key" -- which is the point: nothing in the wrap names the sender. Who sent it
        // comes from the MLS frame instead, which is authenticated and cannot be forged.
        return GiftWrapEvent.create(
            event = seal,
            recipientPubKey = recipientPublicKey,
            createdAt = createdAt,
            recipientRelayHint = recipientRelayHint,
        )
    }

    /**
     * The envelope a current-profile room carries [wrap] in.
     *
     * Unsigned, by construction -- a `MarmotAppEvent` has no signature to give -- and
     * authored by [senderPublicKey], which is the MLS sender's account and therefore
     * what the standard author check compares against. The recipient rides in a `p` tag
     * so a member can tell whose message it is without opening the content, exactly as
     * the wrap's own `p` tag lets them today. [createdAt] is the wrap's, unfuzzed, for
     * the reason [wrap] gives.
     */
    fun envelope(
        senderPublicKey: HexKey,
        recipientPublicKey: HexKey,
        wrap: Event,
        createdAt: Long,
    ): MarmotAppEvent =
        MarmotAppEvent.build(
            pubKey = senderPublicKey,
            kind = ENVELOPE_KIND,
            content = wrap.toJson(),
            createdAt = createdAt,
            tags = arrayOf(arrayOf("p", recipientPublicKey)),
        )

    /**
     * The wrap inside [envelope], or null when it is not an envelope or holds no wrap.
     *
     * Null rather than a throw for the same reason [open] returns null: the caller is
     * inside inbound processing for a kind:445, and one malformed payload must not cost
     * the rest.
     */
    fun wrapInside(envelope: Event): Event? {
        if (envelope.kind != ENVELOPE_KIND) return null
        return try {
            Event.fromJsonOrNull(envelope.content)?.takeIf { it.kind == GiftWrapEvent.KIND }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * The rumor a direct message carries, unsigned and unencrypted.
     *
     * Exposed so the outbound queue can compute a row id before it has a signer, and so
     * tests can assert that the id the recipient arrives at is the id the sender queued.
     */
    fun rumor(
        senderPublicKey: HexKey,
        kind: Kind,
        createdAt: Long,
        tags: Array<Array<String>>,
        content: String,
    ): Event =
        RumorAssembler.assembleRumor<Event>(
            pubKey = senderPublicKey,
            ev =
                EventTemplate(
                    createdAt = createdAt,
                    kind = kind,
                    tags = tags,
                    content = content,
                ),
        )

    /**
     * Opens [wrap] with [me]'s key, or returns null if it was not addressed to them.
     *
     * Trying is the test: the `p` tag is a routing hint, not the authority, and the wrap
     * either decrypts with our key or it does not. Null covers every way that can fail --
     * somebody else's message, a malformed payload, a layer that is not the kind it
     * claims -- because the caller is inside inbound processing for a kind:445 that may
     * carry a perfectly good message for somebody, and a throw would abandon all of it.
     *
     * **The sender cannot open their own wrap.** The throwaway key is discarded at send
     * time and nothing can reconstruct it, so a sender's own message returns null here
     * too. That is a property, not a defect: see docs/marmot-direct-messages.md. Do not
     * "fix" it by storing the throwaway private key.
     */
    fun open(
        wrap: Event,
        me: KeyPair,
    ): Opened? {
        if (wrap.kind != GiftWrapEvent.KIND) return null
        val privateKey = me.privKey ?: return null

        val seal = decryptEvent(wrap.content, privateKey, wrap.pubKey) ?: return null
        if (seal.kind != SealedRumorEvent.KIND) return null

        val rumor = decryptEvent(seal.content, privateKey, seal.pubKey) ?: return null

        return Opened(seal = seal, rumor = rumor)
    }

    private fun decryptEvent(
        ciphertext: String,
        privateKey: ByteArray,
        fromPublicKey: HexKey,
    ): Event? =
        try {
            Event.fromJsonOrNull(
                Nip44.decrypt(
                    payload = ciphertext,
                    privateKey = privateKey,
                    pubKey = fromPublicKey.hexToByteArray(),
                ),
            )
        } catch (_: Exception) {
            null
        }
}
