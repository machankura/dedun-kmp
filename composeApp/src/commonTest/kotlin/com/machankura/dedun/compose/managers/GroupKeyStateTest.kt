package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.nostr.subgroup.tags.WalletPolicyTag
import com.machankura.dedun.compose.nostr.subgroup.SubgroupWalletPolicy
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
import com.vitorpamplona.quartz.nip65RelayList.AdvertisedRelayListEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import com.vitorpamplona.quartz.nip01Core.crypto.Nip01Crypto
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.XonlyPublicKey
import fr.acinq.bitcoin.crypto.frost.Frost
import fr.acinq.bitcoin.crypto.frost.IndividualNonce
import fr.acinq.bitcoin.crypto.frost.KeyMaterial
import fr.acinq.bitcoin.crypto.frost.SecretNonce
import fr.acinq.bitcoin.crypto.frost.Session
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import com.machankura.dedun.compose.database.model.FrostSigningItem
import com.machankura.dedun.compose.database.model.GroupKeyState
import com.machankura.dedun.compose.extensions.toHex
import com.machankura.dedun.compose.nostr.frost.GroupKeyStateEvent
import com.machankura.dedun.compose.nostr.subgroup.SubgroupBirthCertificateEvent
import com.machankura.dedun.compose.nostr.subgroup.SubgroupParentage

/**
 * What a room's key state is allowed to convince a member of.
 *
 * Two independent things have to hold, and the tests come in those two halves.
 *
 * A state has to be *true*: the room's id is the threshold key derived at the
 * path, so a state that does not rederive its own room is naming a key the room
 * was not made from. A member who acts on one signs with a share that cannot
 * aggregate, or worse, treats a key the group does not hold as the key the group
 * holds. That check knows nothing about who is speaking and never did.
 *
 * And a state has to be the *group's*: it is now produced by a FROST signing
 * session rather than announced by whoever made the room, so it carries a
 * signature no single member can make. That does not make a state truer -- the
 * derivation already settled truth -- it makes the first thing on a group's
 * record something the group agreed to.
 *
 * `GroupKeyStateManager` needs a database and so cannot be stood up here. What
 * can be is the deciding it defers to, which is where both halves live.
 */
class GroupKeyStateTest {
    private val participants = 3
    private val threshold = 2

    /** Stands in for a completed ceremony. Any valid key will do. */
    private val keyMaterial: KeyMaterial = Frost.trustedDealerKeygen(
        thresholdSecretKey = PrivateKey(
            ByteVector32("1c0ffee0000000000000000000000000000000000000000000000000000000a1")
        ),
        nParticipants = participants,
        threshold = threshold
    )

    /** A second group, entirely: its own key, its own shares, its own quorum. */
    private val otherKeyMaterial: KeyMaterial = Frost.trustedDealerKeygen(
        thresholdSecretKey = PrivateKey(
            ByteVector32("2bada550000000000000000000000000000000000000000000000000000000b2")
        ),
        nParticipants = participants,
        threshold = threshold
    )

    private val thresholdPublicKey = keyMaterial.thresholdPublicKey.value.toHex()

    /** The other group's key, for the states that name the wrong one. */
    private val otherKey = otherKeyMaterial.thresholdPublicKey.value.toHex()

    private val path = SharedKeyDerivation.MARMOT_ADMIN_GROUP_PATH

    private val chatRoomId = SharedKeyDerivation.marmotGroupId(thresholdPublicKey, path)

    private fun state(
        chatRoomId: String = this.chatRoomId,
        thresholdPublicKey: String = this.thresholdPublicKey,
        derivationPath: String = SharedKeyDerivation.formatPath(path)
    ) = GroupKeyState(
        chatRoomId = chatRoomId,
        dkgSessionId = "ceremony-1",
        thresholdPublicKey = thresholdPublicKey,
        derivationPath = derivationPath,
        announcedBy = "c00rd1na70r",
        announcedAt = Instant.fromEpochSeconds(1_700_000_000)
    )

    @Test
    fun `a state describing the room it was announced in verifies`() {
        assertTrue(state().verifies())
    }

    @Test
    fun `a state naming another group's key does not verify`() {
        // The attack this is here for: a room pointed at a key the group never
        // made, so that everything signed in it is signed by whoever holds that
        // key instead.
        assertFalse(state(thresholdPublicKey = otherKey).verifies())
    }

    @Test
    fun `a state naming the right key at the wrong path does not verify`() {
        // The path is half the derivation, so getting it wrong reaches a
        // different room just as surely as getting the key wrong does.
        assertFalse(state(derivationPath = "m/9420/0/1").verifies())
    }

    @Test
    fun `a state for one room does not verify against another`() {
        assertFalse(state(chatRoomId = otherKey).verifies())
    }

    @Test
    fun `a state carrying an unwalkable path does not verify`() {
        // Hardened derivation needs the parent private key, which nobody in a
        // threshold group has, so a hardened path was never walked to anything.
        assertFalse(state(derivationPath = "m/9420'/0/0").verifies())
        assertFalse(state(derivationPath = "9420/0/0").verifies())
        assertFalse(state(derivationPath = "").verifies())
    }

    @Test
    fun `the tags a state is proposed on read back as they were written`() {
        val tags = GroupKeyStateEvent.assembleTags(
            chatRoomId = chatRoomId,
            dkgSessionId = "ceremony-1",
            path = path
        )

        assertEquals(chatRoomId, GroupKeyStateEvent.parseChatRoomId(tags))
        assertEquals("ceremony-1", GroupKeyStateEvent.parseDkgSessionId(tags))
        assertEquals(path, GroupKeyStateEvent.parsePath(tags))
    }

    @Test
    fun `a threshold key is only read out of content that is one`() {
        assertEquals(thresholdPublicKey, GroupKeyStateEvent.parseThresholdPublicKey(thresholdPublicKey))

        // 32 bytes is an x-only key, not the 33-byte compressed point a ceremony
        // reports; anything else is not a key at all.
        assertNull(GroupKeyStateEvent.parseThresholdPublicKey(chatRoomId))
        assertNull(GroupKeyStateEvent.parseThresholdPublicKey(""))
        assertNull(GroupKeyStateEvent.parseThresholdPublicKey("not a key"))
        assertNull(GroupKeyStateEvent.parseThresholdPublicKey("z".repeat(66)))
    }

    @Test
    fun `a path survives the trip through a tag`() {
        val deep = listOf(9420L, 7L, 0L, 1L)
        val tags = GroupKeyStateEvent.assembleTags(chatRoomId, "ceremony-1", deep)

        assertEquals(deep, GroupKeyStateEvent.parsePath(tags))
    }

    @Test
    fun `a room derived at a path other than the default still verifies at it`() {
        // The reason the path is announced rather than assumed: a lookup that
        // hardcodes MARMOT_ADMIN_GROUP_PATH cannot find this room at all.
        val sibling = listOf(9420L, 0L, 1L)
        val siblingRoom = SharedKeyDerivation.marmotGroupId(thresholdPublicKey, sibling)

        assertTrue(
            state(
                chatRoomId = siblingRoom,
                derivationPath = SharedKeyDerivation.formatPath(sibling)
            ).verifies()
        )
    }

    // ---- The state as the group actually makes one -----------------------
    //
    // Everything above checks the verdict on a state already assembled. These
    // check the assembling: a real Event, signed by a real quorum against real
    // FROST, through the function the inbound path calls.

    /**
     * A quorum of [material]'s group signing [eventId], in the manager's order.
     *
     * The same shape `FrostSigningManager.advance` runs -- nonces, a signer set,
     * partial signatures, an aggregate -- because a signature assembled any
     * other way would not be evidence that this check accepts the ones the app
     * produces.
     */
    private fun groupSignature(material: KeyMaterial, eventId: String): String =
        groupSignatureAt(material, path, eventId)

    private fun groupSignatureAt(
        material: KeyMaterial,
        path: List<Long>,
        eventId: String
    ): String {
        val cache = identityOf(material, path).cache
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

    /**
     * A group's room at [path]: the cache a signature is made with and the key it
     * verifies against, which is also the room's own id.
     */
    private fun identityOf(
        material: KeyMaterial,
        path: List<Long> = this.path
    ): SharedKeyDerivation.Derived =
        SharedKeyDerivation.derive(material.thresholdPublicKey.value.toHex(), path)

    /**
     * The event a proposal carries: the key state re-authored under the room's
     * key, exactly as `FrostSigningManager.unsignedEventOf` does it.
     */
    private fun unsignedKeyState(
        content: String = thresholdPublicKey,
        tags: Array<Array<String>> = GroupKeyStateEvent.assembleTags(chatRoomId, "ceremony-1", path),
        createdAt: Long = 1_700_000_000,
        author: KeyMaterial = keyMaterial
    ): Event {
        val groupPubKey = identityOf(author).hex

        return Event(
            id = EventHasher.hashId(
                pubKey = groupPubKey,
                createdAt = createdAt,
                kind = GroupKeyStateEvent.KIND,
                tags = tags,
                content = content
            ),
            pubKey = groupPubKey,
            createdAt = createdAt,
            kind = GroupKeyStateEvent.KIND,
            tags = tags,
            content = content,
            sig = ""
        )
    }

    /**
     * A finished key state, through the same call that hands a completed session
     * its event -- so what these tests judge is what a device actually applies.
     */
    private fun signedKeyState(
        content: String = thresholdPublicKey,
        tags: Array<Array<String>> = GroupKeyStateEvent.assembleTags(chatRoomId, "ceremony-1", path),
        createdAt: Long = 1_700_000_000,
        author: KeyMaterial = keyMaterial,
        signer: KeyMaterial = author
    ): Event {
        val unsigned = unsignedKeyState(content, tags, createdAt, author)

        return FrostSigningManager.signedEvent(
            item = itemOver(unsigned),
            signature = groupSignature(signer, unsigned.id)
        )
    }

    private fun itemOver(unsignedEvent: Event) = FrostSigningItem(
        sessionId = "s".repeat(64),
        itemIndex = 0,
        unsignedEventJson = unsignedEvent.toJson(),
        eventId = unsignedEvent.id,
        nonceRandom = "f".repeat(64)
    )

    @Test
    fun `a state the group signed about the room it arrives in is taken`() {
        val state = GroupKeyStateManager.stateFrom(chatRoomId, signedKeyState())

        assertEquals(chatRoomId, state?.chatRoomId)
        assertEquals("ceremony-1", state?.dkgSessionId)
        assertEquals(thresholdPublicKey, state?.thresholdPublicKey)
        assertEquals("m/9420/0/0", state?.derivationPath)
        // Attribution and ordering come off the event, not off the clock. The
        // author is the room itself now, which is the whole point: nobody in
        // particular said this.
        assertEquals(chatRoomId, state?.announcedBy)
        assertEquals(Instant.fromEpochSeconds(1_700_000_000), state?.announcedAt)
    }

    @Test
    fun `a true state nobody signed is dropped`() {
        // The change this file exists to pin down. What is being refused here is
        // not a lie -- this state is perfectly true and rederives its own room --
        // it is one member deciding alone what the group signs with. That used to
        // be enough and is not any more.
        assertNull(GroupKeyStateManager.stateFrom(chatRoomId, unsignedKeyState()))
    }

    @Test
    fun `a state a member signed for themselves is dropped`() {
        // The obvious way around the check: sign a true state with your own nostr
        // key and hope the author is not looked at. The author has to be the
        // identity of the key in the content, and a member is not that.
        val member = PrivateKey(
            ByteVector32("3c0ffee0000000000000000000000000000000000000000000000000000000c3")
        )
        val memberPubKey = XonlyPublicKey(member.publicKey()).value.toHex()
        val tags = GroupKeyStateEvent.assembleTags(chatRoomId, "ceremony-1", path)
        val id = EventHasher.hashId(
            pubKey = memberPubKey,
            createdAt = 1_700_000_000,
            kind = GroupKeyStateEvent.KIND,
            tags = tags,
            content = thresholdPublicKey
        )

        val signed = Event(
            id = id,
            pubKey = memberPubKey,
            createdAt = 1_700_000_000,
            kind = GroupKeyStateEvent.KIND,
            tags = tags,
            content = thresholdPublicKey,
            sig = Nip01Crypto.sign(
                data = id.hexToByteArray(),
                privKey = member.value.toByteArray()
            ).toHex()
        )

        assertNull(GroupKeyStateManager.stateFrom(chatRoomId, signed))
    }

    @Test
    fun `a state one group signed about another group's key is dropped`() {
        // Only a group may say what its own key is. This one is signed, by a real
        // quorum, and still refused: the signature is not by the key it names.
        assertNull(
            GroupKeyStateManager.stateFrom(chatRoomId, signedKeyState(author = otherKeyMaterial))
        )
    }

    @Test
    fun `a state edited after the group signed it is dropped`() {
        // A signature covers the id, and the id is the hash of the fields. Swap
        // a field and the two stop agreeing, which is what the id check is for.
        val signed = signedKeyState()
        val tampered = Event(
            id = signed.id,
            pubKey = signed.pubKey,
            createdAt = signed.createdAt,
            kind = signed.kind,
            tags = GroupKeyStateEvent.assembleTags(chatRoomId, "ceremony-2", path),
            content = signed.content,
            sig = signed.sig
        )

        assertNull(GroupKeyStateManager.stateFrom(chatRoomId, tampered))
    }

    @Test
    fun `a state naming another group's key is never about this room`() {
        // Well-formed input, honestly signed, and about somebody else. Another
        // group saying what its own room signs with is a true statement, and
        // putting it through this room's door does not make it a statement about
        // this room -- the subject is the room the state names, and a state can
        // only ever name a room it derives.
        //
        // This is the check that used to be "arrived in the wrong room, drop
        // it". That rule had to go when a group started signing its state before
        // the room it describes exists, so the two are ordinarily different by
        // design. What it was protecting is here instead, and unconditionally:
        // there is no input that makes a state about a room it does not derive.
        val siblingRoom = SharedKeyDerivation.marmotGroupId(otherKey, path)

        val state = GroupKeyStateManager.stateFrom(
            chatRoomId,
            signedKeyState(
                content = otherKey,
                tags = GroupKeyStateEvent.assembleTags(siblingRoom, "ceremony-1", path),
                author = otherKeyMaterial
            )
        )

        assertNotEquals(chatRoomId, state?.chatRoomId)
        assertEquals(siblingRoom, state?.chatRoomId)
    }

    @Test
    fun `a state naming this room but another group's key is dropped`() {
        // The lie the derivation catches, with the room held fixed: this room,
        // and a key it was not made from. Refused however well it is signed --
        // the group signing is signing about its own key, which is simply not
        // the key this room derives from.
        assertNull(
            GroupKeyStateManager.stateFrom(
                chatRoomId,
                signedKeyState(
                    content = otherKey,
                    tags = GroupKeyStateEvent.assembleTags(chatRoomId, "ceremony-1", path),
                    author = otherKeyMaterial
                )
            )
        )
    }

    @Test
    fun `a state addressed to another room is dropped`() {
        val elsewhere = GroupKeyStateEvent.assembleTags(otherKey, "ceremony-1", path)

        assertNull(GroupKeyStateManager.stateFrom(chatRoomId, signedKeyState(tags = elsewhere)))
    }

    @Test
    fun `a state missing any of what it has to say is dropped`() {
        val full = GroupKeyStateEvent.assembleTags(chatRoomId, "ceremony-1", path)

        // No ceremony to reach a share through.
        assertNull(
            GroupKeyStateManager.stateFrom(
                chatRoomId,
                signedKeyState(tags = full.filterNot { it[0] == "frost_key" }.toTypedArray())
            )
        )
        // No path, so nothing to rebuild a TweakCache from.
        assertNull(
            GroupKeyStateManager.stateFrom(
                chatRoomId,
                signedKeyState(tags = full.filterNot { it[0] == "frost_path" }.toTypedArray())
            )
        )
        // No key.
        assertNull(GroupKeyStateManager.stateFrom(chatRoomId, signedKeyState(content = "")))
    }

    @Test
    fun `a state carrying no d tag is judged on its derivation alone`() {
        // The d tag is a convenience for a reader holding the event on its own.
        // Dropping it loses nothing that matters, because the room it arrived in
        // plus the derivation still settle the question.
        val undirected = arrayOf(
            arrayOf("frost_key", "ceremony-1"),
            arrayOf("frost_path", "m/9420/0/0")
        )

        assertEquals(
            chatRoomId,
            GroupKeyStateManager.stateFrom(chatRoomId, signedKeyState(tags = undirected))?.chatRoomId
        )
    }

    @Test
    fun `a path index wider than a uint32 is not a path`() {
        // tweakScalar serialises an index as its low four bytes, so m/4294967296
        // would otherwise walk exactly where m/0 does -- one room, two spellings,
        // both verifying. Rejected at the parser so formatPath stays a round trip.
        assertNull(SharedKeyDerivation.parsePathString("m/4294967296/0/0"))
        assertNull(SharedKeyDerivation.parsePathString("m/-1/0/0"))

        assertEquals(listOf(4294967295L), SharedKeyDerivation.parsePathString("m/4294967295"))
        assertEquals(listOf(0L), SharedKeyDerivation.parsePathString("m/0"))
    }

    @Test
    fun `a state whose path indices are out of range does not verify`() {
        // Reachable only by constructing the row directly; the parser above
        // refuses to build one. Checked because verifies() is what everything
        // else defers to, and it should not be the thing that trusts its input.
        assertFalse(state(derivationPath = "m/4294967296/0/0").verifies())
    }

    @Test
    fun `the room signs as its own id`() {
        // The whole point of deriving at the room's path. The author of a state
        // is the room it belongs to, so a reader checking one needs no lookup:
        // the key they expect is the id of the room they found it in.
        assertEquals(chatRoomId, signedKeyState().pubKey)

        // And it verifies as that key, which is the half a signature makes true
        // rather than merely stated.
        val signed = signedKeyState()
        assertTrue(
            Nip01Crypto.verify(
                signature = signed.sig.hexToByteArray(),
                hash = signed.id.hexToByteArray(),
                pubKey = chatRoomId.hexToByteArray()
            )
        )
    }

    @Test
    fun `a state signed at the wrong path is dropped`() {
        // The path decides which key the group signs as, so a session that walked
        // a different one produces an author this room does not answer to -- even
        // with the right group, the right key and a real quorum behind it.
        val sibling = listOf(9420L, 0L, 1L)
        val siblingIdentity = identityOf(keyMaterial, sibling)
        val tags = GroupKeyStateEvent.assembleTags(chatRoomId, "ceremony-1", path)

        val id = EventHasher.hashId(
            pubKey = siblingIdentity.hex,
            createdAt = 1_700_000_000,
            kind = GroupKeyStateEvent.KIND,
            tags = tags,
            content = thresholdPublicKey
        )

        val signed = Event(
            id = id,
            pubKey = siblingIdentity.hex,
            createdAt = 1_700_000_000,
            kind = GroupKeyStateEvent.KIND,
            tags = tags,
            content = thresholdPublicKey,
            sig = groupSignatureAt(keyMaterial, sibling, id)
        )

        assertNull(GroupKeyStateManager.stateFrom(chatRoomId, signed))
    }

    // ---- The verifier on its own -----------------------------------------
    //
    // `isSignedByGroup` is the caller that knows a threshold key and a path.
    // `isSignedByRoom` is the same three checks with the walk already done, and
    // it is what a member holding neither has to work from -- see
    // docs/member-chronicle.md. What these pin down is that a room id alone is
    // enough, and that it is enough for any kind rather than only a key state.

    /** An event of [kind] authored and signed by [author]'s room at [path]. */
    private fun signedByRoom(
        kind: Int = TextNoteEvent.KIND,
        content: String = "isiZulu",
        tags: Array<Array<String>> = arrayOf(arrayOf("name", "isiZulu")),
        createdAt: Long = 1_700_000_500,
        author: KeyMaterial = keyMaterial
    ): Event {
        val groupPubKey = identityOf(author).hex
        val id = EventHasher.hashId(
            pubKey = groupPubKey,
            createdAt = createdAt,
            kind = kind,
            tags = tags,
            content = content
        )

        return Event(
            id = id,
            pubKey = groupPubKey,
            createdAt = createdAt,
            kind = kind,
            tags = tags,
            content = content,
            sig = groupSignature(author, id)
        )
    }

    @Test
    fun `a room's id is the only key its signature verifies against`() {
        val signed = signedByRoom()

        assertTrue(GroupKeyStateEvent.isSignedByRoom(signed, chatRoomId))

        // The same group and a real quorum, but another of its rooms. A group
        // signature is evidence about one room only, which is what stops work
        // signed in one room being applied in another.
        assertFalse(
            GroupKeyStateEvent.isSignedByRoom(
                signed,
                SharedKeyDerivation.marmotGroupId(thresholdPublicKey, listOf(9420L, 0L, 1L))
            )
        )

        // And another group entirely.
        assertFalse(
            GroupKeyStateEvent.isSignedByRoom(signed, SharedKeyDerivation.marmotGroupId(otherKey, path))
        )
    }

    @Test
    fun `the verifier does not care what kind it is looking at`() {
        // The point of splitting it out: a chronicle carries the group's work
        // rather than key states, and none of the three checks knows the
        // difference.
        //
        // Note the last one. A GroupKeyStateEvent signed by the room passes here
        // exactly as a post does, which is why the chronicle needs an allowlist
        // of kinds on top of this and cannot treat "the group signed it" as
        // permission to apply it.
        listOf(
            TextNoteEvent.KIND,
            MetadataEvent.KIND,
            AdvertisedRelayListEvent.KIND,
            GroupKeyStateEvent.KIND
        ).forEach { kind ->
            assertTrue(
                GroupKeyStateEvent.isSignedByRoom(signedByRoom(kind = kind), chatRoomId),
                "kind $kind"
            )
        }
    }

    @Test
    fun `a rumor nobody signed is not a group signature`() {
        // A member's message in a room is a rumor: the signature is empty and
        // the author is the member who sent it, because what vouches for it is
        // the MLS frame. Neither half survives this check, which is
        // what makes "the group signed it" mean something narrower than "it
        // arrived from the group".
        val member = PrivateKey(
            ByteVector32("4c0ffee0000000000000000000000000000000000000000000000000000000d4")
        )
        val memberPubKey = XonlyPublicKey(member.publicKey()).value.toHex()
        val tags = arrayOf(arrayOf("name", "isiZulu"))

        val rumor = Event(
            id = EventHasher.hashId(
                pubKey = memberPubKey,
                createdAt = 1_700_000_500,
                kind = TextNoteEvent.KIND,
                tags = tags,
                content = "isiZulu"
            ),
            pubKey = memberPubKey,
            createdAt = 1_700_000_500,
            kind = TextNoteEvent.KIND,
            tags = tags,
            content = "isiZulu",
            sig = ""
        )

        assertFalse(GroupKeyStateEvent.isSignedByRoom(rumor, chatRoomId))
    }

    @Test
    fun `claiming the room as author proves nothing without the signature`() {
        // The forgery a chronicle would otherwise carry: write the room's id into
        // pubKey -- every member knows it, it is in the h tag of every kind:445
        // the group has ever sent -- and put anything at all in sig. The author
        // check and the id check both pass. The signature is the whole feature.
        val tags = arrayOf(arrayOf("name", "isiZulu"))
        val forged = Event(
            id = EventHasher.hashId(
                pubKey = chatRoomId,
                createdAt = 1_700_000_500,
                kind = TextNoteEvent.KIND,
                tags = tags,
                content = "isiZulu"
            ),
            pubKey = chatRoomId,
            createdAt = 1_700_000_500,
            kind = TextNoteEvent.KIND,
            tags = tags,
            content = "isiZulu",
            sig = "9".repeat(128)
        )

        assertFalse(GroupKeyStateEvent.isSignedByRoom(forged, chatRoomId))
    }

    @Test
    fun `an event edited after signing fails on the id not on the signature`() {
        val signed = signedByRoom()

        val tampered = Event(
            id = signed.id,
            pubKey = signed.pubKey,
            createdAt = signed.createdAt,
            kind = signed.kind,
            tags = arrayOf(arrayOf("name", "seSotho")),
            content = signed.content,
            sig = signed.sig
        )

        assertFalse(GroupKeyStateEvent.isSignedByRoom(tampered, chatRoomId))
        // The signature over the original id is still perfectly good, which is
        // why the id check is not redundant with it: without it, the fields could
        // say anything and the signature would still verify against the id they
        // no longer hash to.
        assertTrue(GroupKeyStateEvent.isSignedByRoom(signed, chatRoomId))
    }

    @Test
    fun `malformed input is a no rather than a throw`() {
        // Everything here arrives off the wire inside somebody else's chronicle, so
        // the failure mode has to be a verdict. A throw would take down the whole
        // inbound transaction the page is being applied in.
        val signed = signedByRoom()

        assertFalse(GroupKeyStateEvent.isSignedByRoom(signed, ""))
        assertFalse(GroupKeyStateEvent.isSignedByRoom(signed, "not hex"))
        assertFalse(
            GroupKeyStateEvent.isSignedByRoom(
                Event(
                    id = signed.id,
                    pubKey = signed.pubKey,
                    createdAt = signed.createdAt,
                    kind = signed.kind,
                    tags = signed.tags,
                    content = signed.content,
                    sig = "beef"
                ),
                chatRoomId
            )
        )
    }

    @Test
    fun `a threshold key that is not a point is a no rather than a throw`() {
        // The derivation walk moved when isSignedByRoom was split off, and it kept
        // a catch of its own. Without one, a malformed key in a state's content
        // stops being a refused state and becomes an exception in the middle of
        // the inbound path.
        assertFalse(GroupKeyStateEvent.isSignedByGroup(signedKeyState(), "z".repeat(66), path))
        assertFalse(GroupKeyStateEvent.isSignedByGroup(signedKeyState(), "", path))
        assertFalse(GroupKeyStateEvent.isSignedByGroup(signedKeyState(), thresholdPublicKey, emptyList()))
    }

    // ---- a state that names a parent --------------------------------------
    //
    // A subgroup's key state carries the parent group's signature over this very
    // room's id. Everything above judges a state against the room it describes,
    // which the event alone settles; a parentage is a statement about a *second*
    // group, so it is the one claim on a key state that needs somebody else's
    // signature to mean anything.
    //
    // The rule these pin down is that a claim which cannot be backed takes the
    // whole state with it. Filing it as a parentless group would be worse than
    // dropping it: the group would be recorded as top-level on this device and as
    // a subgroup on every device that could check the certificate.

    /** The parent group's room, which is `otherKeyMaterial` wearing a second hat. */
    private val parentChatRoomId get() = identityOf(otherKeyMaterial).hex

    /** A certificate by [author]'s room saying [subgroupChatRoomId] is its child. */
    private fun birthCertificate(
        subgroupChatRoomId: String = chatRoomId,
        parent: String = parentChatRoomId,
        subgroupKey: String = thresholdPublicKey,
        certificatePath: List<Long> = path,
        author: KeyMaterial = otherKeyMaterial,
        extraTags: Array<Array<String>> = emptyArray()
    ): Event = signedByRoom(
        kind = SubgroupBirthCertificateEvent.KIND,
        content = subgroupChatRoomId,
        tags = SubgroupBirthCertificateEvent.assembleTags(
            subgroupChatRoomId = subgroupChatRoomId,
            parentChatRoomId = parent,
            thresholdPublicKey = subgroupKey,
            adminPublicKeys = listOf("a".repeat(64), "b".repeat(64), "c".repeat(64)),
            name = "Translation team",
            path = certificatePath
        ) + extraTags,
        author = author
    )

    private fun keyStateWithParent(
        parent: String = parentChatRoomId,
        certificate: Event = birthCertificate()
    ): Event = signedKeyState(
        tags = GroupKeyStateEvent.assembleTags(
            chatRoomId = chatRoomId,
            dkgSessionId = "ceremony-1",
            path = path,
            parent = SubgroupParentage(parentChatRoomId = parent, certificate = certificate)
        )
    )

    @Test
    fun `a state whose parent certified it keeps the parent and the certificate`() {
        val state = GroupKeyStateManager.stateFrom(chatRoomId, keyStateWithParent())

        assertEquals(parentChatRoomId, state?.parentChatRoomId)
        assertEquals(birthCertificate().toJson(), state?.birthCertificateJson)
    }

    @Test
    fun `a state claiming no parent keeps both fields null`() {
        // Every group made before subgroups existed, and every top-level group
        // made after. Null here is the ordinary case, not missing data.
        val state = GroupKeyStateManager.stateFrom(chatRoomId, signedKeyState())

        assertNull(state?.parentChatRoomId)
        assertNull(state?.birthCertificateJson)
    }

    @Test
    fun `a state naming a parent with no certificate is dropped`() {
        // Not filed as a parentless state. A claim with the checkable part
        // removed is not a weaker claim, it is one this device cannot judge, and
        // guessing that the member meant nothing by it is still a guess.
        val halfClaim = signedKeyState(
            tags = GroupKeyStateEvent.assembleTags(chatRoomId, "ceremony-1", path) +
                arrayOf(arrayOf("parent_group", parentChatRoomId))
        )

        assertNull(GroupKeyStateManager.stateFrom(chatRoomId, halfClaim))
    }

    @Test
    fun `a state carrying a certificate but naming no parent is dropped`() {
        val halfClaim = signedKeyState(
            tags = GroupKeyStateEvent.assembleTags(chatRoomId, "ceremony-1", path) +
                arrayOf(arrayOf("birth_certificate", birthCertificate().toJson()))
        )

        assertNull(GroupKeyStateManager.stateFrom(chatRoomId, halfClaim))
    }

    @Test
    fun `a state whose certificate will not parse is dropped`() {
        // The tag is there and unreadable, which reads as absent through the
        // parser and must not read as absent through the decision.
        val unreadable = signedKeyState(
            tags = GroupKeyStateEvent.assembleTags(chatRoomId, "ceremony-1", path) +
                arrayOf(
                    arrayOf("parent_group", parentChatRoomId),
                    arrayOf("birth_certificate", "{not json")
                )
        )

        assertNull(GroupKeyStateManager.stateFrom(chatRoomId, unreadable))
    }

    @Test
    fun `a state whose certificate is for another child is dropped`() {
        // The attack: take a real certificate the parent signed for one subgroup
        // and staple it to another group's key state, so a group the parent never
        // certified inherits a lineage it was not given.
        val elsewhere = birthCertificate(subgroupChatRoomId = otherKey)

        assertNull(GroupKeyStateManager.stateFrom(chatRoomId, keyStateWithParent(certificate = elsewhere)))
    }

    @Test
    fun `a state whose certificate was signed by a room that is not the named parent is dropped`() {
        // Signed by this room's own group, which is a real quorum with a real
        // signature and no standing to say anything about its own parentage.
        assertNull(
            GroupKeyStateManager.stateFrom(
                chatRoomId,
                keyStateWithParent(certificate = birthCertificate(author = keyMaterial))
            )
        )
    }

    @Test
    fun `a state whose parent tag disagrees with its certificate is dropped`() {
        // The index and the claim have to agree. Completing one from the other
        // would be this device deciding what the group meant.
        assertNull(GroupKeyStateManager.stateFrom(chatRoomId, keyStateWithParent(parent = otherKey)))
    }

    @Test
    fun `a state whose certificate names a different key is dropped`() {
        // Belt and braces over the derivation: the certificate's own id has to
        // derive from the key it names, so this is a certificate for a room that
        // is not this one wearing this room's address.
        val wrongKey = birthCertificate(subgroupKey = otherKey)

        assertNull(GroupKeyStateManager.stateFrom(chatRoomId, keyStateWithParent(certificate = wrongKey)))
    }

    @Test
    fun `a state whose certificate's wallet policy does not read is dropped`() {
        // Present and unreadable must not read as absent: an undecided child is
        // offered both wallets, so a corrupt policy would hand it the one its
        // parent took away. docs/subgroup-wallet-policy.md, Phase 5.
        listOf(
            arrayOf(arrayOf("wallet", "maybe")),
            arrayOf(WalletPolicyTag.assemble(SubgroupWalletPolicy.GUARDED)),
            arrayOf(WalletPolicyTag.assemble(SubgroupWalletPolicy.UNGUARDED), WalletPolicyTag.assemble(SubgroupWalletPolicy.UNGUARDED)),
        ).forEach { extraTags ->
            assertNull(
                GroupKeyStateManager.stateFrom(chatRoomId, keyStateWithParent(certificate = birthCertificate(extraTags = extraTags))),
                "a certificate with ${extraTags.joinToString { it.joinToString() }} was believed",
            )
        }
    }

    @Test
    fun `a state whose certificate decides a wallet policy keeps it`() {
        val unguarded = birthCertificate(extraTags = arrayOf(WalletPolicyTag.assemble(SubgroupWalletPolicy.UNGUARDED)))
        val state = GroupKeyStateManager.stateFrom(chatRoomId, keyStateWithParent(certificate = unguarded))

        assertEquals(unguarded.toJson(), state?.birthCertificateJson)
    }

    @Test
    fun `a parentage survives the trip through the tags`() {
        val certificate = birthCertificate()
        val tags = GroupKeyStateEvent.assembleTags(
            chatRoomId = chatRoomId,
            dkgSessionId = "ceremony-1",
            path = path,
            parent = SubgroupParentage(parentChatRoomId, certificate)
        )

        assertTrue(GroupKeyStateEvent.claimsParentage(tags))
        assertEquals(parentChatRoomId, GroupKeyStateEvent.parseParentChatRoomId(tags))
        assertEquals(certificate.toJson(), GroupKeyStateEvent.parseBirthCertificate(tags)?.toJson())

        // And the tags a top-level group is proposed on carry neither, so every
        // state signed before subgroups existed reads exactly as it did.
        val plain = GroupKeyStateEvent.assembleTags(chatRoomId, "ceremony-1", path)

        assertFalse(GroupKeyStateEvent.claimsParentage(plain))
        assertNull(GroupKeyStateEvent.parseParentChatRoomId(plain))
        assertNull(GroupKeyStateEvent.parseBirthCertificate(plain))
    }
}
