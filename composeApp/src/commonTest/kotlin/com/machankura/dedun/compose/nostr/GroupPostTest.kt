package com.machankura.dedun.compose.nostr

import com.machankura.dedun.compose.database.model.GroupSignedEvent
import com.machankura.dedun.compose.extensions.toHex
import com.machankura.dedun.compose.managers.SharedKeyDerivation
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.crypto.frost.Frost
import fr.acinq.bitcoin.crypto.frost.IndividualNonce
import fr.acinq.bitcoin.crypto.frost.KeyMaterial
import fr.acinq.bitcoin.crypto.frost.SecretNonce
import fr.acinq.bitcoin.crypto.frost.Session
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What may be shown as something the group said.
 *
 * The reading gate is `GroupNostrProfileTest`'s -- the room has to be the author and
 * the signature has to be the room's -- and it matters more here than anywhere else
 * in this feature. A profile read wrong shows the wrong name; a post read wrong puts
 * words in a group's mouth under a byline saying a quorum agreed to them, on a page
 * whose whole purpose is to be read by people who were not there.
 *
 * The ordering half is the other thing worth pinning. Posts are the one statement
 * here that **accumulates**: kind:1 is not replaceable, so a second post stands
 * beside the first rather than replacing it, and everything the profile and the
 * relay lists do with "newest wins" is wrong for these.
 */
class GroupPostTest {
    private val participants = 3
    private val threshold = 2

    private val groupMaterial: KeyMaterial = Frost.trustedDealerKeygen(
        thresholdSecretKey = PrivateKey(
            ByteVector32("1c0ffee0000000000000000000000000000000000000000000000000000000a1")
        ),
        nParticipants = participants,
        threshold = threshold
    )

    /** Another group entirely, for the posts signed by the wrong room. */
    private val strangerMaterial: KeyMaterial = Frost.trustedDealerKeygen(
        thresholdSecretKey = PrivateKey(
            ByteVector32("3decade0000000000000000000000000000000000000000000000000000000c3")
        ),
        nParticipants = participants,
        threshold = threshold
    )

    private val path = SharedKeyDerivation.MARMOT_ADMIN_GROUP_PATH

    private val chatRoomId =
        SharedKeyDerivation.marmotGroupId(groupMaterial.thresholdPublicKey.value.toHex(), path)

    @Test
    fun `a kind one the room signed is a post by the group`() {
        val post = GroupPost.of(
            signedEvent = signed("We have finished the first chapter."),
            chatRoomId = chatRoomId
        )

        assertEquals("We have finished the first chapter.", post?.content)
        assertEquals(chatRoomId, post?.publicKey)
        assertTrue(post?.isNewThread() == true)
    }

    @Test
    fun `a kind one another group signed is not this group's post`() {
        // A real quorum and a real signature by a group with no standing to speak
        // for this one. The row names this room because that is where it was filed;
        // the author is what decides whose words they are.
        val stranger = GroupSignedEvent.fromEvent(
            event = noteEvent("Said by somebody else entirely.", material = strangerMaterial),
            chatRoomId = chatRoomId
        )

        assertNull(GroupPost.of(signedEvent = stranger, chatRoomId = chatRoomId))
    }

    @Test
    fun `a kind one the room did not sign is not a post`() {
        // Authored by the room and signed by somebody else: the shape a member
        // putting words in the group's mouth would produce, and the reason the
        // reading verifies rather than trusting the author field.
        assertNull(
            GroupPost.of(
                signedEvent = signed("The group has decided to disband.", signer = strangerMaterial),
                chatRoomId = chatRoomId
            )
        )

        listOf("f".repeat(128), "not a signature", "").forEach { rubbish ->
            assertNull(
                GroupPost.of(
                    signedEvent = signed("Anything at all", signature = rubbish),
                    chatRoomId = chatRoomId
                ),
                "a post signed with \"$rubbish\" was accepted"
            )
        }
    }

    @Test
    fun `an event of another kind is not a post`() {
        assertNull(
            GroupPost.of(
                signedEvent = signed("Hello").copy(kind = 30023),
                chatRoomId = chatRoomId
            )
        )
    }

    @Test
    fun `posts accumulate rather than replace newest first`() {
        // The property that separates these from the profile and the relay lists.
        // A reading that kept only the newest would silently delete a group's whole
        // history the first time it posted twice.
        val first = signed("First", createdAt = 1_700_000_000)
        val second = signed("Second", createdAt = 1_700_000_900)
        val third = signed("Third", createdAt = 1_700_001_800)

        listOf(
            listOf(first, second, third),
            listOf(third, second, first),
            listOf(second, third, first),
        ).forEach { events ->
            assertEquals(
                listOf("Third", "Second", "First"),
                GroupPost.newestFirstAmong(events, chatRoomId).map { it.content },
                "posts came back in the wrong order, or one of them went missing"
            )
        }
    }

    @Test
    fun `two posts signed in the same second resolve the same way on every device`() {
        // Two devices reading the same posts in whatever order the query hands them
        // over have to show the same feed. A tie on the timestamp breaks on the id,
        // which both agree on because it is a hash of the event.
        val one = signed("One", createdAt = 1_700_000_000)
        val other = signed("Other", createdAt = 1_700_000_000)

        val expected = GroupPost.newestFirstAmong(listOf(one, other), chatRoomId).map { it.content }

        assertEquals(
            expected,
            GroupPost.newestFirstAmong(listOf(other, one), chatRoomId).map { it.content },
            "a tie on the timestamp was broken differently by the two orderings"
        )
        assertEquals(
            if (one.id > other.id) listOf("One", "Other") else listOf("Other", "One"),
            expected,
            "the tie should break on the id, which is the only thing both devices share"
        )
    }

    @Test
    fun `a blank post is dropped rather than drawn as an empty card`() {
        // The composer will not produce one, so anything reaching here empty came
        // from somewhere this app does not control -- and an empty signed card
        // cannot be told from a broken one.
        listOf("", "   ", "\n\n").forEach { blank ->
            assertEquals(
                emptyList(),
                GroupPost.newestFirstAmong(listOf(signed(blank)), chatRoomId),
                "a post saying \"$blank\" was shown"
            )
        }
    }

    @Test
    fun `a reply is marked as one`() {
        // Nothing here writes replies, so this is about an event that arrived some
        // other way. Drawing one as an announcement would put half a conversation on
        // a group's page with no sign that it was half.
        val reply = GroupPost.of(
            signedEvent = signed(
                "Yes, agreed.",
                tags = arrayOf(arrayOf("e", "b".repeat(64), "", "reply"))
            ),
            chatRoomId = chatRoomId
        )

        assertFalse(reply?.isNewThread() == true)
    }

    @Test
    fun `the composer builds a bare note and trims it`() {
        // No reply tags, no mentions, no subject: every tag `TextNoteEvent.build`
        // can add describes a relationship to something else, which a group's first
        // way of posting does not have and should not guess at.
        val template = GroupPost.template("  Something the group would say.  ")

        assertEquals(TextNoteEvent.KIND, template.kind)
        assertEquals("Something the group would say.", template.content)
        assertEquals(emptyList(), template.tags.toList())
    }

    @Test
    fun `a post the composer built reads back as the group said it`() {
        val template = GroupPost.template("We have finished the first chapter.")
        val post = GroupPost.of(
            signedEvent = signed(template.content, tags = template.tags),
            chatRoomId = chatRoomId
        )

        assertEquals("We have finished the first chapter.", post?.content)
        assertTrue(post?.isNewThread() == true)
    }

    /** A post as `FrostSigningManager.complete` files one. */
    private fun signed(
        note: String,
        tags: Array<Array<String>> = emptyArray(),
        createdAt: Long = 1_700_000_000,
        material: KeyMaterial = groupMaterial,
        signer: KeyMaterial = material,
        signature: String? = null
    ): GroupSignedEvent = GroupSignedEvent.fromEvent(
        event = noteEvent(note, tags, createdAt, material, signer, signature),
        chatRoomId = chatRoomId,
        derivationPath = SharedKeyDerivation.formatPath(path)
    )

    private fun noteEvent(
        note: String,
        tags: Array<Array<String>> = emptyArray(),
        createdAt: Long = 1_700_000_000,
        material: KeyMaterial = groupMaterial,
        signer: KeyMaterial = material,
        signature: String? = null
    ): Event {
        val groupPubKey = SharedKeyDerivation
            .derive(material.thresholdPublicKey.value.toHex(), path)
            .hex

        val id = EventHasher.hashId(
            pubKey = groupPubKey,
            createdAt = createdAt,
            kind = TextNoteEvent.KIND,
            tags = tags,
            content = note
        )

        return Event(
            id = id,
            pubKey = groupPubKey,
            createdAt = createdAt,
            kind = TextNoteEvent.KIND,
            tags = tags,
            content = note,
            sig = signature ?: groupSignature(signer, id)
        )
    }

    /**
     * A real FROST signature by [material]'s quorum over [eventId], through the
     * same shape `FrostSigningManager.advance` runs.
     */
    private fun groupSignature(material: KeyMaterial, eventId: String): String {
        val cache = SharedKeyDerivation
            .derive(material.thresholdPublicKey.value.toHex(), path)
            .cache
        val message = ByteVector(eventId.hexToByteArray())
        val signerIds = listOf(0, 1)

        val nonces = signerIds.map { signerId ->
            SecretNonce.generate(
                sessionRandom = ByteVector32("a".repeat(63) + "${signerId + 1}"),
                secretShare = material.secretShares[signerId],
                publicShare = material.publicShares[signerId],
                tweakedThresholdPublicKey = cache.tweakedPublicKey,
                message = message,
                extraInput = null
            )
        }

        val signingSession = Session.create(
            aggregatedNonce = IndividualNonce.aggregate(nonces.map { it.second }).right!!,
            signerIds = signerIds.map { it.toUInt() },
            signerPublicShares = signerIds.map { material.publicShares[it] },
            nParticipants = participants,
            threshold = threshold,
            tweakCache = cache,
            message = message
        )

        val partials = signerIds.mapIndexed { position, signerId ->
            signingSession.sign(
                nonces[position].first,
                material.secretShares[signerId],
                signerId.toUInt()
            ).right!!
        }

        return signingSession.aggregateSigs(partials).right!!.toByteArray().toHex()
    }
}
