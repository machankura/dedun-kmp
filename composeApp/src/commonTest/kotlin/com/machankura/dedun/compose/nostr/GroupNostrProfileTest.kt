package com.machankura.dedun.compose.nostr

import com.machankura.dedun.compose.database.model.GroupSignedEvent
import com.machankura.dedun.compose.extensions.toHex
import com.machankura.dedun.compose.managers.SharedKeyDerivation
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * What a group's nostr profile is allowed to be read out of.
 *
 * A room's id is the pubkey it signs as, so a kind:0 authored by that key is the
 * group describing itself and a kind:0 authored by anything else is not -- however
 * it came to be filed against the room. The reading is what the group detail screen
 * shows and what an edit is built on top of, so both halves are worth pinning:
 * which events become a profile, and what an edit does to the one before it.
 *
 * The edit half is the one with a silent failure behind it. `updateFromPast` is
 * used precisely so a field this app does not offer survives being edited, and
 * `createNew` in its place would compile, pass any test that only looked at the
 * fields the form knows about, and quietly wipe the rest of the group's profile
 * every time somebody fixed a typo in its name.
 */
class GroupNostrProfileTest {
    private val participants = 3
    private val threshold = 2

    /** The group whose profile this is. */
    private val groupMaterial: KeyMaterial = Frost.trustedDealerKeygen(
        thresholdSecretKey = PrivateKey(
            ByteVector32("1c0ffee0000000000000000000000000000000000000000000000000000000a1")
        ),
        nParticipants = participants,
        threshold = threshold
    )

    /** Another group entirely, for the profiles signed by the wrong room. */
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
    private val strangerChatRoomId =
        SharedKeyDerivation.marmotGroupId(strangerMaterial.thresholdPublicKey.value.toHex(), path)

    @Test
    fun `a kind zero the room signed is the group's profile`() {
        val profile = GroupNostrProfile.of(
            signedEvent = signed(
                content = """{"name":"Translation collective","about":"We translate hard books.",""" +
                    """"picture":"https://example.com/group.png","nip05":"group@example.com"}"""
            ),
            chatRoomId = chatRoomId
        )

        assertNotNull(profile)
        assertEquals("Translation collective", profile.name())
        assertEquals("We translate hard books.", profile.about())
        assertEquals("https://example.com/group.png", profile.picture())
        assertEquals("group@example.com", profile.nip05())
        assertEquals(chatRoomId, profile.publicKey)
    }

    @Test
    fun `display_name is what a name is read out of when both are set`() {
        // Every other nostr client prefers it, and `template` writes both so they
        // agree. A group whose two names disagree got them from somewhere else,
        // and the answer has to be the one the rest of nostr will give.
        val profile = GroupNostrProfile.of(
            signedEvent = signed(
                content = """{"name":"collective","display_name":"Translation collective"}"""
            ),
            chatRoomId = chatRoomId
        )

        assertEquals("Translation collective", profile?.name())
    }

    @Test
    fun `a kind zero another group signed is not this group's profile`() {
        // A real quorum and a real signature by a group with no standing to say
        // anything about this room. The row names this room because that is where
        // the event was filed; the author is what decides whose profile it is.
        val stranger = GroupSignedEvent.fromEvent(
            event = metadataEvent(
                content = """{"name":"Somebody else"}""",
                material = strangerMaterial
            ),
            chatRoomId = chatRoomId
        )

        assertEquals(strangerChatRoomId, stranger.publicKey, "the fixture signs as the stranger")
        assertNull(GroupNostrProfile.of(signedEvent = stranger, chatRoomId = chatRoomId))
    }

    @Test
    fun `a kind zero whose signature is not the room's is not a profile`() {
        // Authored by the room, saying the right things, and signed by a key that
        // is not the room's. This is the shape a member forging a rename produces.
        assertNull(
            GroupNostrProfile.of(
                signedEvent = signed(
                    content = """{"name":"Renamed by one member"}""",
                    signer = strangerMaterial
                ),
                chatRoomId = chatRoomId
            )
        )

        // And everything off the wire is allowed to be nonsense, which means no
        // rather than an exception.
        listOf("f".repeat(128), "not a signature", "").forEach { rubbish ->
            assertNull(
                GroupNostrProfile.of(
                    signedEvent = signed(content = """{"name":"x"}""", signature = rubbish),
                    chatRoomId = chatRoomId
                ),
                "a profile signed with \"$rubbish\" was accepted"
            )
        }
    }

    @Test
    fun `an event of another kind is not a profile`() {
        assertNull(
            GroupNostrProfile.of(
                signedEvent = signed(content = """{"name":"x"}""").copy(kind = 1),
                chatRoomId = chatRoomId
            )
        )
    }

    @Test
    fun `the newest profile the group signed is the one that counts`() {
        // kind:0 is replaceable: editing a profile signs a second one, and the
        // second is the answer whatever order the query hands them over in.
        val older = signed(content = """{"name":"What it was called"}""", createdAt = 1_700_000_000)
        val newer = signed(content = """{"name":"What it is called"}""", createdAt = 1_700_000_900)

        listOf(listOf(older, newer), listOf(newer, older)).forEach { events ->
            assertEquals(
                "What it is called",
                GroupNostrProfile.newestAmong(events, chatRoomId)?.name(),
                "the newest profile lost to query order"
            )
        }
    }

    @Test
    fun `two profiles signed in the same second resolve the same way on every device`() {
        // Two devices catching up read the same events in whatever order the
        // relay or the query gives them, and they have to end up showing the same
        // profile. A tie on the timestamp is broken by the id, which every device
        // agrees on because it is a hash of the event.
        val one = signed(content = """{"name":"One"}""", createdAt = 1_700_000_000)
        val other = signed(content = """{"name":"Other"}""", createdAt = 1_700_000_000)

        val expected = GroupNostrProfile.newestAmong(listOf(one, other), chatRoomId)?.name()

        assertNotNull(expected)
        assertEquals(
            expected,
            GroupNostrProfile.newestAmong(listOf(other, one), chatRoomId)?.name(),
            "a tie on the timestamp was broken differently by the two orderings"
        )
        assertEquals(
            if (one.id > other.id) "One" else "Other",
            expected,
            "the tie should break on the id, which is the only thing both devices share"
        )
    }

    @Test
    fun `the first profile a group signs carries the fields it was given`() {
        val template = GroupNostrProfile.template(
            latest = null,
            name = "Translation collective",
            about = "We translate hard books.",
            picture = "https://example.com/group.png",
            website = "https://example.com",
            nip05 = "group@example.com",
        )

        val profile = GroupNostrProfile.of(
            signedEvent = signed(content = template.content),
            chatRoomId = chatRoomId
        )

        assertNotNull(profile)
        assertEquals(MetadataEvent.KIND, template.kind)
        assertEquals("Translation collective", profile.name())
        assertEquals("We translate hard books.", profile.about())
        assertEquals("https://example.com/group.png", profile.picture())
        assertEquals("https://example.com", profile.website())
        assertEquals("group@example.com", profile.nip05())
        assertNull(profile.lud16(), "Dedun never puts a payment address on the group's behalf")
    }

    /** docs/group-nostr-profile-and-posts.md, D6. */
    @Test
    fun `an edit keeps a lightning address another tool put there`() {
        // Dedun neither offers nor shows one, but the group's own profile is not
        // Dedun's to trim: one a Curare member or another client signed stays.
        val existing = GroupNostrProfile.of(
            signedEvent = signed(content = """{"name":"Old name","lud16":"group@getalby.com"}"""),
            chatRoomId = chatRoomId
        )
        assertNotNull(existing)

        val template = GroupNostrProfile.template(
            latest = existing,
            name = "New name",
            about = "",
            picture = "",
            website = "",
            nip05 = "",
        )

        val edited = GroupNostrProfile.of(
            signedEvent = signed(content = template.content),
            chatRoomId = chatRoomId
        )

        assertNotNull(edited)
        assertEquals("New name", edited.name())
        assertEquals("group@getalby.com", edited.lud16(), "an edit here wiped the group's lightning address")
    }

    @Test
    fun `an edit keeps a field the form does not offer`() {
        // The reason `template` builds on the group's own event rather than from
        // scratch. `bot` is not on the form and never will be reached by it; an
        // edit that dropped it would be this app deciding a group is not a bot
        // because somebody fixed its name.
        val existing = GroupNostrProfile.of(
            signedEvent = signed(content = """{"name":"Old name","bot":true}"""),
            chatRoomId = chatRoomId
        )
        assertNotNull(existing)

        val template = GroupNostrProfile.template(
            latest = existing,
            name = "New name",
            about = "",
            picture = "",
            website = "",
            nip05 = "",
        )

        val edited = GroupNostrProfile.of(
            signedEvent = signed(content = template.content),
            chatRoomId = chatRoomId
        )

        assertNotNull(edited)
        assertEquals("New name", edited.name())
        assertEquals(true, edited.metadata.bot, "an edit dropped a field the form does not offer")
    }

    @Test
    fun `an emptied field unsays what the group had said`() {
        // The only way to withdraw something a group has signed. A form that sent
        // nulls for its blanks could add to a profile and never subtract from one.
        val existing = GroupNostrProfile.of(
            signedEvent = signed(
                content = """{"name":"Translation collective","about":"Something regretted"}"""
            ),
            chatRoomId = chatRoomId
        )
        assertNotNull(existing)

        val template = GroupNostrProfile.template(
            latest = existing,
            name = "Translation collective",
            about = "",
            picture = "",
            website = "",
            nip05 = "",
        )

        val edited = GroupNostrProfile.of(
            signedEvent = signed(content = template.content),
            chatRoomId = chatRoomId
        )

        assertNotNull(edited)
        assertNull(edited.about())
        assertEquals("Translation collective", edited.name())
    }

    @Test
    fun `a name is written to both of the keys a reader might look in`() {
        val template = GroupNostrProfile.template(
            latest = null,
            name = "Translation collective",
            about = "",
            picture = "",
            website = "",
            nip05 = "",
        )

        val metadata = GroupNostrProfile.of(
            signedEvent = signed(content = template.content),
            chatRoomId = chatRoomId
        )?.metadata

        assertNotNull(metadata)
        assertEquals("Translation collective", metadata.name)
        assertEquals(
            "Translation collective",
            metadata.displayName,
            "a client preferring display_name would show the old name after an edit"
        )
    }

    @Test
    fun `an empty profile is a profile rather than a parse failure`() {
        // Wiping a profile is something a group is entitled to do, and an empty
        // kind:0 is how nostr says it. Reading it as nothing would leave the
        // screen claiming the group had never said anything.
        val profile = GroupNostrProfile.of(
            signedEvent = signed(content = ""),
            chatRoomId = chatRoomId
        )

        assertNotNull(profile)
        assertNull(profile.name())
        assertNull(profile.about())
    }

    @Test
    fun `content that is not a profile at all is refused`() {
        assertNull(
            GroupNostrProfile.of(
                signedEvent = signed(content = "not json"),
                chatRoomId = chatRoomId
            )
        )
    }

    /**
     * A profile row as `FrostSigningManager.complete` files one: the content under
     * the room's own key, with the id hashed over it and the group's signature on
     * it.
     *
     * [signer] is pulled apart from the author so a signature by the wrong quorum
     * can be tested, and [signature] replaces the real one outright for what is
     * not a signature at all.
     */
    private fun signed(
        content: String,
        createdAt: Long = 1_700_000_000,
        material: KeyMaterial = groupMaterial,
        signer: KeyMaterial = material,
        signature: String? = null
    ): GroupSignedEvent = GroupSignedEvent.fromEvent(
        event = metadataEvent(
            content = content,
            createdAt = createdAt,
            material = material,
            signer = signer,
            signature = signature
        ),
        chatRoomId = chatRoomId,
        derivationPath = SharedKeyDerivation.formatPath(path)
    )

    private fun metadataEvent(
        content: String,
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
            kind = MetadataEvent.KIND,
            tags = emptyArray(),
            content = content
        )

        return Event(
            id = id,
            pubKey = groupPubKey,
            createdAt = createdAt,
            kind = MetadataEvent.KIND,
            tags = emptyArray(),
            content = content,
            sig = signature ?: groupSignature(signer, id)
        )
    }

    /**
     * A real FROST signature by [material]'s quorum over [eventId], through the
     * same shape `FrostSigningManager.advance` runs.
     *
     * Assembled any other way it would not be evidence about the signatures this
     * app actually produces.
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
