package com.machankura.dedun.compose.managers

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import com.vitorpamplona.quartz.nip01Core.crypto.Nip01Crypto
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.crypto.frost.Frost
import fr.acinq.bitcoin.crypto.frost.IndividualNonce
import fr.acinq.bitcoin.crypto.frost.KeyMaterial
import fr.acinq.bitcoin.crypto.frost.SecretNonce
import fr.acinq.bitcoin.crypto.frost.Session
import fr.acinq.bitcoin.crypto.frost.TweakCache
import fr.acinq.secp256k1.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import com.machankura.dedun.compose.database.model.DkgSession
import com.machankura.dedun.compose.database.model.FrostSigningItem
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.types.FrostSigningStage
import com.machankura.dedun.compose.extensions.toHex
import com.machankura.dedun.compose.nostr.frost.FrostSigningEvents

/**
 * The two rounds a signing session runs, against real FROST.
 *
 * `FrostSigningManager` spreads these steps across arriving messages, several
 * devices and a database, none of which a unit test can stand up. What it can
 * do is run the same calls in the same order with the same arguments and check
 * that what comes out is a signature nostr will accept — which is the part
 * that was written from reading the library rather than from a working example,
 * and so the part most likely to be subtly wrong.
 *
 * A signature that verifies is the whole contract: if these calls are wired up
 * incorrectly the aggregate simply fails to verify, silently, on every device.
 */
class FrostSigningRoundTest {
    private val participants = 3
    private val threshold = 2

    /** Stands in for a completed ceremony. A trusted dealer is fine here: the test is about signing. */
    private val keyMaterial: KeyMaterial = Frost.trustedDealerKeygen(
        thresholdSecretKey = PrivateKey(
            ByteVector32("1c0ffee0000000000000000000000000000000000000000000000000000000a1")
        ),
        nParticipants = participants,
        threshold = threshold
    )

    /**
     * The room the group signs in, derived at the admin path.
     *
     * The cache carries the path's tweaks, and every call below is given it
     * rather than a bare `TweakCache.create` -- because that is what the manager
     * does, and because a tweaked cache is precisely the part most likely to be
     * wired up wrong without saying so.
     */
    private val room: SharedKeyDerivation.Derived = SharedKeyDerivation.derive(
        thresholdPublicKey = keyMaterial.thresholdPublicKey.value.toHex(),
        path = SharedKeyDerivation.MARMOT_ADMIN_GROUP_PATH
    )

    private val tweakCache: TweakCache = room.cache

    /** The group's nostr identity: the room's own id, which is what it signs as. */
    private val groupPubKey = room.hex

    /** The 32 bytes actually signed — a nostr event id, exactly as the manager computes it. */
    private fun eventId(content: String): String = EventHasher.hashId(
        pubKey = groupPubKey,
        createdAt = 1_700_000_000L,
        kind = 1,
        tags = arrayOf(),
        content = content
    )

    /**
     * One signer's half of the protocol, in the manager's order: regenerate the
     * nonce from stored randomness, then sign once the set is known.
     */
    private fun nonceOf(signerId: Int, message: ByteVector, random: String): Pair<SecretNonce, IndividualNonce> =
        SecretNonce.generate(
            sessionRandom = ByteVector32(random),
            secretShare = keyMaterial.secretShares[signerId],
            publicShare = keyMaterial.publicShares[signerId],
            tweakedThresholdPublicKey = tweakCache.tweakedPublicKey,
            message = message,
            extraInput = null
        )

    private fun sessionFor(signerIds: List<Int>, nonces: List<IndividualNonce>, message: ByteVector): Session {
        val aggregated = IndividualNonce.aggregate(nonces).right!!

        return Session.create(
            aggregatedNonce = aggregated,
            signerIds = signerIds.map { it.toUInt() },
            signerPublicShares = signerIds.map { keyMaterial.publicShares[it] },
            nParticipants = participants,
            threshold = threshold,
            tweakCache = tweakCache,
            message = message
        )
    }

    @Test
    fun `a threshold of signers produces a signature nostr accepts`() {
        val id = eventId("the group agrees")
        val message = ByteVector(id.hexToByteArray())

        // Two of the three sign, which is the point of a 2-of-3 key.
        val signerIds = listOf(0, 1)
        val nonces = signerIds.map { nonceOf(it, message, "a".repeat(63) + "${it + 1}") }

        val session = sessionFor(signerIds, nonces.map { it.second }, message)

        val partials = signerIds.mapIndexed { position, signerId ->
            session.sign(nonces[position].first, keyMaterial.secretShares[signerId], signerId.toUInt()).right!!
        }

        val signature = session.aggregateSigs(partials).right!!

        assertTrue(
            Nip01Crypto.verify(
                signature = signature.toByteArray(),
                hash = id.hexToByteArray(),
                pubKey = groupPubKey.hexToByteArray()
            ),
            "the aggregated signature must verify against the room's own key"
        )
    }

    @Test
    fun `a different pair of signers signs the same event just as well`() {
        val id = eventId("the group agrees")
        val message = ByteVector(id.hexToByteArray())

        // Whoever happens to be available. The coordinator picks; the signature
        // that comes out must not depend on which t it picked.
        val signerIds = listOf(1, 2)
        val nonces = signerIds.map { nonceOf(it, message, "b".repeat(63) + "${it + 1}") }

        val session = sessionFor(signerIds, nonces.map { it.second }, message)
        val partials = signerIds.mapIndexed { position, signerId ->
            session.sign(nonces[position].first, keyMaterial.secretShares[signerId], signerId.toUInt()).right!!
        }

        val signature = session.aggregateSigs(partials).right!!

        assertTrue(
            Nip01Crypto.verify(
                signature = signature.toByteArray(),
                hash = id.hexToByteArray(),
                pubKey = groupPubKey.hexToByteArray()
            )
        )
    }

    @Test
    fun `one signer set signs a batch of three and every signature verifies`() {
        // The manager's batch, with the database taken out of the way: one signer
        // set and one tweak cache shared, and a nonce, a Session and a signature
        // per event. If any of that is wired up wrongly the aggregate simply
        // fails to verify, which is the whole reason this file exists.
        val ids = listOf("first", "second", "third").map(::eventId)
        val messages = ids.map { ByteVector(it.hexToByteArray()) }

        val signerIds = listOf(0, 1)

        // A seed per signer per item. The manager mints these independently; here
        // they only have to differ, which is the property under test.
        val nonces = signerIds.map { signerId ->
            messages.mapIndexed { index, message ->
                nonceOf(signerId, message, "f".repeat(62) + "$index${signerId + 1}")
            }
        }

        val signatures = messages.mapIndexed { index, message ->
            val session = sessionFor(signerIds, nonces.map { it[index].second }, message)

            val partials = signerIds.mapIndexed { position, signerId ->
                session.sign(
                    nonces[position][index].first,
                    keyMaterial.secretShares[signerId],
                    signerId.toUInt()
                ).right!!
            }

            session.aggregateSigs(partials).right!!
        }

        ids.forEachIndexed { index, id ->
            assertTrue(
                Nip01Crypto.verify(
                    signature = signatures[index].toByteArray(),
                    hash = id.hexToByteArray(),
                    pubKey = groupPubKey.hexToByteArray()
                ),
                "item $index of the batch must verify against the room's own key"
            )
        }
    }

    @Test
    fun `a batch signature does not carry to another item of the same batch`() {
        // What keeps a batch k independent signatures rather than one loose one.
        // Signing three events in lockstep must not make any of them
        // interchangeable.
        val ids = listOf("first", "second").map(::eventId)
        val messages = ids.map { ByteVector(it.hexToByteArray()) }
        val signerIds = listOf(0, 1)

        val nonces = signerIds.map { signerId ->
            messages.mapIndexed { index, message ->
                nonceOf(signerId, message, "9".repeat(62) + "$index${signerId + 1}")
            }
        }

        val session = sessionFor(signerIds, nonces.map { it[0].second }, messages[0])
        val partials = signerIds.mapIndexed { position, signerId ->
            session.sign(
                nonces[position][0].first,
                keyMaterial.secretShares[signerId],
                signerId.toUInt()
            ).right!!
        }
        val first = session.aggregateSigs(partials).right!!

        assertFalse(
            Nip01Crypto.verify(
                signature = first.toByteArray(),
                hash = ids[1].hexToByteArray(),
                pubKey = groupPubKey.hexToByteArray()
            ),
            "item 0's signature must not verify against item 1"
        )
    }

    @Test
    fun `two items of a batch never share a nonce`() {
        // The one mistake in this whole design that loses the key, asserted at the
        // level where it would be made. `SecretNonce.generate` mixes the message
        // in, so two items of a batch cannot collide even given the same seed --
        // but the manager gives them distinct seeds as well, and both halves are
        // checked here because either alone is enough to be relied on by accident.
        val messages = listOf("first", "second").map { ByteVector(eventId(it).hexToByteArray()) }

        val sameSeed = messages.map { nonceOf(0, it, "7".repeat(64)).second.data.toHex() }
        assertEquals(2, sameSeed.toSet().size, "one seed under two messages must give two nonces")

        val distinctSeeds = messages.mapIndexed { index, message ->
            nonceOf(0, message, "8".repeat(63) + "$index").second.data.toHex()
        }
        assertEquals(2, distinctSeeds.toSet().size)
        assertEquals(emptySet(), sameSeed.toSet().intersect(distinctSeeds.toSet()))
    }

    @Test
    fun `a signature over one event does not verify against another`() {
        val id = eventId("the group agrees")
        val message = ByteVector(id.hexToByteArray())

        val signerIds = listOf(0, 1)
        val nonces = signerIds.map { nonceOf(it, message, "c".repeat(63) + "${it + 1}") }
        val session = sessionFor(signerIds, nonces.map { it.second }, message)
        val partials = signerIds.mapIndexed { position, signerId ->
            session.sign(nonces[position].first, keyMaterial.secretShares[signerId], signerId.toUInt()).right!!
        }
        val signature = session.aggregateSigs(partials).right!!

        assertFalse(
            Nip01Crypto.verify(
                signature = signature.toByteArray(),
                hash = eventId("the group agrees to something else").hexToByteArray(),
                pubKey = groupPubKey.hexToByteArray()
            ),
            "a signature is over one event id and must not carry to another"
        )
    }

    @Test
    fun `regenerating a nonce from the same seed and message gives the same nonce`() {
        val id = eventId("the group agrees")
        val message = ByteVector(id.hexToByteArray())
        val random = "d".repeat(63) + "1"

        // What makes a signing session restart-safe: SecretNonce cannot be stored,
        // so the manager keeps its seed and derives again. If that were not
        // reproducible a device that restarted mid-session would publish a partial
        // signature against a nonce nobody aggregated.
        val first = nonceOf(0, message, random).second
        val second = nonceOf(0, message, random).second

        assertEquals(first.data.toHex(), second.data.toHex())
    }

    @Test
    fun `the same seed under a different message gives a different nonce`() {
        val random = "e".repeat(63) + "1"

        // The safety property behind reusing the seed at all: one session signs one
        // message. Were the nonce independent of the message, a session that could
        // be re-pointed at another event would sign twice under one nonce, which
        // hands over the secret share.
        val first = nonceOf(0, ByteVector(eventId("one thing").hexToByteArray()), random).second
        val second = nonceOf(0, ByteVector(eventId("another thing").hexToByteArray()), random).second

        assertFalse(first.data.toHex() == second.data.toHex())
    }
}

/**
 * The pure bits of a signing session's bookkeeping: who is signing, and with
 * which key.
 */
class FrostSigningSessionTest {
    private fun session(signerId: Int, signerIds: String?) = FrostSigningSession(
        id = "s".repeat(64),
        chatRoomId = "room",
        coordinatorPublicKey = "c".repeat(64),
        userPublicKey = "u".repeat(64),
        dkgSessionId = "k".repeat(64),
        threshold = 2,
        participantCount = 3,
        signerId = signerId,
        signerIds = signerIds
    )

    /** The one event such a session signs, signed or not. */
    private fun items(signature: String? = null) = listOf(
        FrostSigningItem(
            sessionId = "s".repeat(64),
            itemIndex = 0,
            unsignedEventJson = "{}",
            eventId = "e".repeat(64),
            nonceRandom = "f".repeat(64),
            signature = signature
        )
    )

    @Test
    fun `a session waits on its owner until they answer`() {
        val open = session(signerId = 2, signerIds = null)

        assertTrue(FrostSigningManager.isAwaitingApproval(open, items()))
        assertFalse(
            FrostSigningManager.isAwaitingApproval(
                open.copy(signApprovedAt = Instant.fromEpochSeconds(1)),
                items()
            )
        )
    }

    @Test
    fun `a session that has settled asks its owner nothing`() {
        val open = session(signerId = 2, signerIds = null)

        assertFalse(
            FrostSigningManager.isAwaitingApproval(open.copy(stage = FrostSigningStage.COMPLETE), items())
        )
        assertFalse(
            FrostSigningManager.isAwaitingApproval(open.copy(stage = FrostSigningStage.FAILED), items())
        )
    }

    @Test
    fun `a signature the group already made asks its owner nothing either`() {
        // A t-of-n key does not need everybody, so a quorum can finish while one
        // member's phone is still in a pocket. The session stays at its opening
        // stage on their device until it next advances, and offering them the
        // decision in that window offers two bad answers: a nonce nobody is
        // waiting for, or a refusal that abandons a signature that exists.
        val signedWithoutThem = session(signerId = 2, signerIds = "0,1")

        assertFalse(
            FrostSigningManager.isAwaitingApproval(signedWithoutThem, items("a".repeat(128)))
        )
    }

    @Test
    fun `a member who declined is not asked again while the session runs on`() {
        // The other answer to the one question. The session is still open --
        // a t-of-n key can do without this member -- but the decision was theirs
        // to make once, and offering it again would be offering a refusal that
        // has already been counted, or an approval contradicting it.
        val open = session(signerId = 2, signerIds = null)

        assertFalse(
            FrostSigningManager.isAwaitingApproval(
                open.copy(signDeclinedAt = Instant.fromEpochSeconds(1)),
                items()
            )
        )
    }

    @Test
    fun `it takes one more refusal than the key can do without to abandon a session`() {
        // A t-of-n key needs t willing members, so n - t may refuse and the rest
        // still sign; the refusal after that is the one that ends it. In a 2-of-3
        // group one member may decline and two still sign, which is the whole
        // point of a threshold; only the second refusal abandons it.
        val twoOfThree = session(signerId = 0, signerIds = null)
        assertEquals(2, twoOfThree.declinesThatAbandon())

        // Every member is needed in an n-of-n group, so any refusal is fatal.
        assertEquals(1, twoOfThree.copy(threshold = 3, participantCount = 3).declinesThatAbandon())
        assertEquals(1, twoOfThree.copy(threshold = 2, participantCount = 2).declinesThatAbandon())

        // And a 3-of-5 group can lose two members before a third refusal ends it.
        assertEquals(3, twoOfThree.copy(threshold = 3, participantCount = 5).declinesThatAbandon())
    }

    @Test
    fun `a member left out of the signer set is not a signer`() {
        assertTrue(session(signerId = 1, signerIds = "0,1").isSigner())
        assertFalse(session(signerId = 2, signerIds = "0,1").isSigner())
    }

    @Test
    fun `nobody is a signer until the coordinator has chosen`() {
        assertFalse(session(signerId = 0, signerIds = null).isSigner())
    }

    @Test
    fun `the signer set keeps the order it was aggregated in`() {
        // FROST binds the set into the challenge, so this list is not a set of ids
        // but a sequence positionally matched to the aggregated nonce.
        assertEquals(listOf(2, 0, 1), session(signerId = 0, signerIds = "2,0,1").signerIdList())
    }

    @Test
    fun `a signer set tag survives the trip through a tag array`() {
        val tags = FrostSigningEvents.assembleTags(
            sessionId = "session",
            dkgSessionId = "ceremony",
            signerIds = listOf(2, 0, 1)
        )

        assertEquals("session", FrostSigningEvents.parseSessionId(tags))
        assertEquals("ceremony", FrostSigningEvents.parseKey(tags))
        assertEquals(listOf(2, 0, 1), FrostSigningEvents.parseSignerIds(tags))
    }

    @Test
    fun `a ceremony that recorded no public shares reads back null rather than empty`() {
        // Ceremonies completed before the column existed. Signing falls back to not
        // cross-checking shares, which the FROST API allows, rather than refusing.
        val ceremony = DkgSession(
            id = "k".repeat(64),
            chatRoomId = "room",
            coordinatorPublicKey = "c".repeat(64),
            userPublicKey = "u".repeat(64),
            threshold = 2,
            participantCount = 3,
            hostPublicKey = "h".repeat(66),
            round1Random = "1".repeat(64),
            round2AuxRandom = "2".repeat(64)
        )

        assertEquals(null, ceremony.publicShareList())
        assertEquals(
            2,
            ceremony.copy(
                publicShares = listOf(
                    Hex.encode(ByteArray(33) { 2 }),
                    Hex.encode(ByteArray(33) { 3 })
                ).joinToString(",")
            ).publicShareList()?.size
        )
    }
}

/**
 * What a device needs on its row to finish a session it never took part in.
 *
 * `FrostSigningManager.advance` completes on an arrived signature ahead of the
 * approval gate, and that hoist rests on one claim: closing a session needs
 * nothing secret and nothing the member would have had to publish. Were it
 * false -- were the aggregated nonce, the signer set or a share needed to check
 * the result -- the gate would have to stay where it was, and a member the
 * quorum did not need would be stuck being asked to sign something already
 * signed.
 *
 * So the claim is spelled out here against a real 2-of-3 signature, from the
 * row of the member who was left out of it.
 */
class FrostSigningCompletionTest {
    private val keyMaterial: KeyMaterial = Frost.trustedDealerKeygen(
        thresholdSecretKey = PrivateKey(
            ByteVector32("2decade0000000000000000000000000000000000000000000000000000000b2")
        ),
        nParticipants = 3,
        threshold = 2
    )

    private val room: SharedKeyDerivation.Derived = SharedKeyDerivation.derive(
        thresholdPublicKey = keyMaterial.thresholdPublicKey.value.toHex(),
        path = SharedKeyDerivation.MARMOT_ADMIN_GROUP_PATH
    )

    private val tweakCache: TweakCache = room.cache

    /** The group's nostr identity, exactly as `unsignedEventOf` derives it. */
    private val groupPubKey = room.hex

    /** The event the group is asked to sign, built the way the manager builds it. */
    private val unsignedEvent = Event(
        id = EventHasher.hashId(
            pubKey = groupPubKey,
            createdAt = 1_700_000_000L,
            kind = 1,
            tags = arrayOf(),
            content = "a dialect the group agreed on"
        ),
        pubKey = groupPubKey,
        createdAt = 1_700_000_000L,
        kind = 1,
        tags = arrayOf(),
        content = "a dialect the group agreed on",
        sig = ""
    )

    /** A real signature from members 0 and 1. Member 2 is not in it and never was. */
    private val signature: String = run {
        val message = ByteVector(unsignedEvent.id.hexToByteArray())
        val signerIds = listOf(0, 1)

        val nonces = signerIds.map { signerId ->
            SecretNonce.generate(
                sessionRandom = ByteVector32("c".repeat(63) + "${signerId + 1}"),
                secretShare = keyMaterial.secretShares[signerId],
                publicShare = keyMaterial.publicShares[signerId],
                tweakedThresholdPublicKey = tweakCache.tweakedPublicKey,
                message = message,
                extraInput = null
            )
        }

        val session = Session.create(
            aggregatedNonce = IndividualNonce.aggregate(nonces.map { it.second }).right!!,
            signerIds = signerIds.map { it.toUInt() },
            signerPublicShares = signerIds.map { keyMaterial.publicShares[it] },
            nParticipants = 3,
            threshold = 2,
            tweakCache = tweakCache,
            message = message
        )

        val partials = signerIds.mapIndexed { position, signerId ->
            session.sign(nonces[position].first, keyMaterial.secretShares[signerId], signerId.toUInt()).right!!
        }

        session.aggregateSigs(partials).right!!.toHex()
    }

    /**
     * Member 2's row, as it stands when the signature reaches them: they never
     * approved, so nothing of theirs was ever published, and the coordinator
     * never named them. Every column the completion path reads is here; the ones
     * it must not need are deliberately left null.
     */
    private fun leftOutMemberSession() = FrostSigningSession(
        id = "s".repeat(64),
        chatRoomId = "room",
        coordinatorPublicKey = "c".repeat(64),
        userPublicKey = "u".repeat(64),
        dkgSessionId = "k".repeat(64),
        threshold = 2,
        participantCount = 3,
        signerId = 2,
        derivationPath = SharedKeyDerivation.formatPath(),
        signerIds = null,
        signApprovedAt = null
    )

    /** The event that session signs: everything completing it needs, and nothing more. */
    private fun leftOutMemberItem(signature: String? = null) = FrostSigningItem(
        sessionId = "s".repeat(64),
        itemIndex = 0,
        unsignedEventJson = unsignedEvent.toJson(),
        eventId = unsignedEvent.id,
        nonceRandom = "f".repeat(64),
        aggregatedNonce = null,
        signature = signature
    )

    @Test
    fun `a member who never took part can still check what the group signed`() {
        val item = leftOutMemberItem(signature)
        val signed = FrostSigningManager.signedEvent(item)!!

        assertTrue(
            Nip01Crypto.verify(
                signature = signed.sig.hexToByteArray(),
                hash = item.eventId.hexToByteArray(),
                pubKey = signed.pubKey.hexToByteArray()
            ),
            "completing must need only the row: the event, its id and the signature"
        )
    }

    @Test
    fun `the finished event is the one that was proposed with a signature on it`() {
        // Not rebuilt and not rehashed: the id a session is pinned to is the id
        // the signature is over, so anything that changed here would produce an
        // event whose signature verifies against nothing.
        val signed = FrostSigningManager.signedEvent(leftOutMemberItem(signature))!!

        assertEquals(unsignedEvent.id, signed.id)
        assertEquals(unsignedEvent.pubKey, signed.pubKey)
        assertEquals(unsignedEvent.createdAt, signed.createdAt)
        assertEquals(unsignedEvent.kind, signed.kind)
        assertEquals(unsignedEvent.content, signed.content)
        assertEquals(signature, signed.sig)
    }

    @Test
    fun `there is no finished event until the signature arrives`() {
        assertEquals(null, FrostSigningManager.signedEvent(leftOutMemberItem()))
    }

    @Test
    fun `the arrived signature is what stops the session asking`() {
        // The pair that matters to the screen and the transcript: the same row,
        // before and after the group finished without this member.
        assertTrue(
            FrostSigningManager.isAwaitingApproval(leftOutMemberSession(), listOf(leftOutMemberItem()))
        )
        assertFalse(
            FrostSigningManager.isAwaitingApproval(
                leftOutMemberSession(),
                listOf(leftOutMemberItem(signature))
            )
        )
    }

    @Test
    fun `a signature over a different event is refused`() {
        // What the check is for. A coordinator passing off something else must not
        // get it applied and announced as the group's, and the row is all there is
        // to catch it with.
        val other = leftOutMemberItem(signature).copy(
            eventId = EventHasher.hashId(
                pubKey = groupPubKey,
                createdAt = 1_700_000_000L,
                kind = 1,
                tags = arrayOf(),
                content = "something else entirely"
            )
        )

        val signed = FrostSigningManager.signedEvent(other)!!

        assertFalse(
            Nip01Crypto.verify(
                signature = signed.sig.hexToByteArray(),
                hash = other.eventId.hexToByteArray(),
                pubKey = signed.pubKey.hexToByteArray()
            )
        )
    }
}
