package com.machankura.dedun.compose.managers

import androidx.room3.Room
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.crypto.frost.Frost
import fr.acinq.bitcoin.crypto.frost.IndividualNonce
import fr.acinq.bitcoin.crypto.frost.KeyMaterial
import fr.acinq.bitcoin.crypto.frost.SecretNonce
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.DkgParticipantMessage
import com.machankura.dedun.compose.database.model.DkgSession
import com.machankura.dedun.compose.database.model.FrostSignerMessage
import com.machankura.dedun.compose.database.model.FrostSigningItem
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.GiftWrapPayload
import com.machankura.dedun.compose.database.model.MarmotInnerEvent
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.Participant
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.intermdiate.LocalParticipant
import com.machankura.dedun.compose.database.model.types.DkgRitualStage
import com.machankura.dedun.compose.database.model.types.FrostSigningStage
import com.machankura.dedun.compose.extensions.toHex
import com.machankura.dedun.compose.nostr.dkg.DkgRitualEvents
import com.machankura.dedun.compose.nostr.frost.FrostSigningEvents

/**
 * What a resend does, against a real database and the real manager.
 *
 * A session can be left waiting forever on a member who would have agreed,
 * because the message asking them never arrived -- MLS moved its ratchet past
 * the generation it came in at (`docs/mls-skipped-keys.md`), or a
 * disappearing-message group expired it off the relay. Neither heals on its own
 * and neither is visible to the sender.
 *
 * Two halves, and the second is the one worth testing hardest. The coordinator
 * repeats what the coordinator wrote; a member named as silent repeats what
 * *they* wrote, because a relayed copy would be filed under the relayer. The
 * tests below are mostly about the limits of that: the same bytes and not new
 * ones, nothing at all from a device whose owner has not decided, and nothing
 * from a nudge that did not come from the coordinator.
 *
 * And a third repair at the end, for the stall neither half reaches: a device
 * killed part way through its own turn, waiting on a message that has already
 * been delivered. See `FrostSigningManager.resumeAnswered`.
 */
class FrostSigningResendJvmTest {
    private val participants = 3
    private val threshold = 2

    /** Stands in for a completed ceremony; the test is about resending, not the DKG. */
    private val keyMaterial: KeyMaterial = Frost.trustedDealerKeygen(
        thresholdSecretKey = PrivateKey(
            ByteVector32("1c0ffee0000000000000000000000000000000000000000000000000000000a1")
        ),
        nParticipants = participants,
        threshold = threshold
    )

    private val thresholdPublicKey = keyMaterial.thresholdPublicKey.value.toHex()

    /** The admin room: the group's key walked to the admin path, which is its id. */
    private val roomId = SharedKeyDerivation.marmotGroupId(thresholdPublicKey)

    private val ceremonyId = "ceremony".padEnd(64, '0')
    private val members = listOf("a", "b", "c").map { it.repeat(64) }
    private val hostKeys = listOf("2a", "2b", "2c").map { it.padEnd(66, '0') }

    private val alice = members[0]
    private val bob = members[1]
    private val carol = members[2]

    private lateinit var db: DedunDatabase
    private lateinit var room: LocalChatRoom

    @AfterTest
    fun closeDatabase() {
        if (::db.isInitialized) db.close()
    }

    /**
     * One member's device: a room it can sign in, and their share to sign with.
     *
     * The room carries its roster, unlike the decline test's, because this one
     * is about who a message is addressed to: the room here is NIP-17 -- no MLS
     * state -- so a payload is sealed per p-tag, and narrowing a resend to the
     * quiet members is visible only if there is somebody to leave out.
     */
    private suspend fun openDevice(owner: HexKey = alice, marmot: Boolean = false) {
        db = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())

        val index = members.indexOf(owner)

        val nostrEventId = "e$owner".take(64)
        db.nostrEventDao().upsert(
            NostrEvent(
                id = nostrEventId,
                pubKey = owner,
                kind = 0,
                tags = emptyArray(),
                content = "{}",
                sig = "0".repeat(128)
            )
        )
        db.profileDao().upsert(Profile(publicKey = owner, nostrEventId = nostrEventId))

        val chatRoom = ChatRoom(
            id = roomId,
            userPublicKey = owner,
            subject = "#Ekklesia",
            description = SharedKeyDerivation.describe("Admins of the group."),
            // No MLS state is what marks a room NIP-17, and it is the only thing
            // `broadcast` reads to choose a transport. A dummy blob is what every
            // other test in this project uses to stand a Marmot room up.
            mlsGroupState = if (marmot) "state" else null
        )
        db.chatRoomDao().upsert(chatRoom)

        db.dkgSessionDao().upsert(
            DkgSession(
                id = ceremonyId,
                chatRoomId = roomId,
                coordinatorPublicKey = alice,
                userPublicKey = owner,
                threshold = threshold,
                participantCount = participants,
                stage = DkgRitualStage.COMPLETE,
                hostPublicKey = hostKeys[index],
                round1Random = "1".repeat(64),
                round2AuxRandom = "2".repeat(64),
                thresholdPublicKey = thresholdPublicKey,
                secretShare = keyMaterial.secretShares[index].value.toHex(),
                publicShares = keyMaterial.publicShares.joinToString(",") { it.value.toHex() }
            )
        )

        members.forEachIndexed { i, member ->
            db.dkgSessionDao().upsert(
                DkgParticipantMessage(
                    sessionId = ceremonyId,
                    participantPublicKey = member,
                    kind = DkgRitualEvents.HOST_KEY,
                    payload = hostKeys[i]
                )
            )
        }

        room = LocalChatRoom(
            chatRoom = chatRoom,
            localParticipants = members.map { member ->
                LocalParticipant(
                    Participant(
                        participantPublicKey = member,
                        chatRoomId = roomId,
                        relayHint = null
                    ),
                    null
                )
            }
        )
    }

    /** Alice proposes, which makes her device the coordinator and agrees on her behalf. */
    private suspend fun propose(): FrostSigningSession = FrostSigningManager.proposeSigning(
        database = db,
        localChatRoom = room,
        userPublicKey = alice,
        kind = 1,
        tags = arrayOf(),
        content = "Something for the group to say"
    )

    /**
     * A session as it sits on a member's device once Alice's proposal has
     * arrived: recorded, awaiting the owner's answer, and nothing published.
     *
     * The item carries a real unsigned event rather than `{}`, because a resend
     * refuses to repeat a proposal whose stored events will not parse -- a
     * proposal that quietly lost one would have every signer's contribution
     * rejected for being the wrong length.
     */
    private suspend fun proposalArrivedAt(owner: HexKey): FrostSigningSession {
        val session = FrostSigningSession(
            id = "s".repeat(64),
            chatRoomId = roomId,
            coordinatorPublicKey = alice,
            userPublicKey = owner,
            dkgSessionId = ceremonyId,
            threshold = threshold,
            participantCount = participants,
            signerId = members.indexOf(owner),
            derivationPath = SharedKeyDerivation.formatPath(SharedKeyDerivation.MARMOT_ADMIN_GROUP_PATH)
        )
        db.frostSigningSessionDao().upsert(session)
        db.frostSigningSessionDao().upsertItems(
            listOf(
                FrostSigningItem(
                    sessionId = session.id,
                    itemIndex = 0,
                    unsignedEventJson = unsignedEvent().toJson(),
                    eventId = unsignedEvent().id,
                    nonceRandom = "f".repeat(64)
                )
            )
        )
        return session
    }

    /** The one event these sessions sign, as the room's own key would author it. */
    private fun unsignedEvent(): Event = Event(
        id = "e".repeat(64),
        pubKey = roomId,
        createdAt = 1_700_000_000L,
        kind = 1,
        tags = arrayOf(),
        content = "Something for the group to say",
        sig = ""
    )

    /**
     * One signing message, as the inbound path would hand it over.
     *
     * [silent] is what a resent proposal carries: the members the coordinator
     * says it has heard nothing from.
     */
    private suspend fun receive(
        sessionId: String,
        kind: Kind,
        member: HexKey,
        content: String = "",
        silent: List<HexKey>? = null,
        includeKey: Boolean = false
    ) {
        FrostSigningManager.processSigningPayload(
            database = db,
            localChatRoom = room,
            innerEvent = Event(
                id = "$kind$member$content$silent".hashCode().toUInt().toString(16).padStart(64, '0'),
                pubKey = member,
                createdAt = Clock.System.now().epochSeconds,
                kind = kind,
                tags = FrostSigningEvents.assembleTags(
                    sessionId = sessionId,
                    dkgSessionId = if (includeKey) ceremonyId else null,
                    silentMembers = silent
                ),
                content = content,
                sig = ""
            ),
            userPublicKey = room.chatRoom.userPublicKey
        )
    }

    private suspend fun resend(sessionId: String) =
        FrostSigningManager.resend(database = db, localChatRoom = room, sessionId = sessionId)

    /**
     * A public nonce as [member]'s own device would have published it.
     *
     * Real rather than filler, because the coordinator aggregates what arrives:
     * a nonce that does not parse leaves the session where it was, so a test
     * wanting the stage after this one has to offer something genuine.
     *
     * [sessionRandom] is the one thing a remote member's device does not share
     * with this one. Theirs is theirs; any value gives a usable nonce, and the
     * seed only has to differ per member so two of them never collide.
     */
    private fun publicNonceOf(member: HexKey): String {
        val index = members.indexOf(member)
        val cache = SharedKeyDerivation
            .derive(thresholdPublicKey, SharedKeyDerivation.MARMOT_ADMIN_GROUP_PATH)
            .cache

        val (_, publicNonce) = SecretNonce.generate(
            sessionRandom = ByteVector32("b".repeat(63) + "${index + 1}"),
            secretShare = keyMaterial.secretShares[index],
            publicShare = keyMaterial.publicShares[index],
            tweakedThresholdPublicKey = cache.tweakedPublicKey,
            message = ByteVector(unsignedEvent().id.hexToByteArray()),
            extraInput = null
        )

        return publicNonce.data.toHex()
    }

    /**
     * Alice's session once Bob has offered a nonce: the signer set chosen, her
     * own part signed, and the session waiting on his.
     *
     * The stage is reached rather than written. A session put into
     * [FrostSigningStage.COLLECTING_PARTIAL_SIGNATURES] by hand, with no nonce
     * on file from anybody, is a state the protocol cannot produce -- and it is
     * precisely the difference: the members this stage waits on are the ones who
     * have already been heard from once.
     */
    private suspend fun waitingOnBobsPart(): FrostSigningSession {
        val session = propose()
        receive(session.id, FrostSigningEvents.NONCE, bob, publicNonceOf(bob))

        val reached = session(session.id)
        assertEquals(FrostSigningStage.COLLECTING_PARTIAL_SIGNATURES, reached.stage)
        assertEquals(listOf(0, 1), reached.signerIdList())

        return reached
    }

    /** Files a signer's message without running the session, the way a stored one would read. */
    private suspend fun fileMessage(sessionId: String, kind: Kind, member: HexKey, payload: String) {
        db.frostSigningSessionDao().upsert(
            FrostSignerMessage(
                sessionId = sessionId,
                signerPublicKey = member,
                kind = kind,
                payload = payload
            )
        )
    }

    private suspend fun session(id: String) = db.frostSigningSessionDao().getSessionById(id)!!

    private suspend fun transcript(type: String) = db.chatMessageDao()
        .getChatMessagesByChatRoomId(roomId)
        .map { it.chatMessage }
        .filter { it.messageType == type }

    /** What this device has queued for the group, of one kind. The room is NIP-17 here. */
    private suspend fun queued(kind: Kind) = db.giftWrapPayloadDao()
        .getByChatRoomAndKinds(roomId, listOf(kind))
        .filter { it.publicKey == room.chatRoom.userPublicKey }

    /** What this device has queued for a Marmot room, of one kind. */
    private suspend fun queuedInner(kind: Kind) = db.marmotInnerEventDao()
        .getByChatRoomAndKinds(roomId, listOf(kind))
        .filter { it.publicKey == room.chatRoom.userPublicKey }

    /** [queuedBy]'s twin for the Marmot transport, and by id for the same reason. */
    private suspend fun queuedInnerBy(kind: Kind, publish: suspend () -> Unit): MarmotInnerEvent {
        val before = queuedInner(kind).map { it.id }.toSet()
        publish()
        val after = queuedInner(kind).filter { it.id !in before }
        assertEquals(1, after.size, "expected exactly one new queued inner event of kind $kind")
        return after.single()
    }

    private fun pTags(tags: Array<Array<String>>): Set<HexKey> =
        tags.filter { it[0] == "p" }.map { it[1] }.toSet()

    /**
     * The row [publish] added to the queue, found by id rather than by time.
     *
     * `createdAt` is stored to the second, so a resend and the publish it
     * repeats routinely share one -- picking "the newest" is a coin toss
     * between them, and the coin came up wrong often enough to be worth not
     * tossing.
     */
    private suspend fun queuedBy(kind: Kind, publish: suspend () -> Unit): GiftWrapPayload {
        val before = queued(kind).map { it.id }.toSet()
        publish()
        val after = queued(kind).filter { it.id !in before }
        assertEquals(1, after.size, "expected exactly one new queued row of kind $kind")
        return after.single()
    }

    // ---- the coordinator's half -------------------------------------------

    @Test
    fun `a resend repeats the proposal and names who has not answered`() = runBlocking {
        openDevice()
        val session = propose()

        // Alice proposed, so her nonce is on file. Nobody else has said anything.
        assertEquals(1, queued(FrostSigningEvents.PROPOSAL).size)

        val resent = queuedBy(FrostSigningEvents.PROPOSAL) { assertTrue(resend(session.id)) }

        assertEquals(
            2,
            queued(FrostSigningEvents.PROPOSAL).size,
            "the resend is a second proposal, not an edit of the first"
        )

        assertEquals(
            setOf(bob, carol),
            FrostSigningEvents.parseSilentMembers(resent.tags)?.toSet(),
            "both members who have said nothing are named"
        )

        // The proposer is never in the silent set: it is their own session.
        assertFalse(alice in FrostSigningEvents.parseSilentMembers(resent.tags)!!)
    }

    @Test
    fun `a resend carries the key, so a device with no session can open one`() = runBlocking {
        openDevice()
        val session = propose()

        val resent = queuedBy(FrostSigningEvents.PROPOSAL) { assertTrue(resend(session.id)) }

        // Without this a receiver that never saw the first proposal drops the
        // second: `acceptProposal` refuses a proposal that names no key.
        assertEquals(ceremonyId, FrostSigningEvents.parseKey(resent.tags))
    }

    @Test
    fun `a resend repeats the same events, not new ones`() = runBlocking {
        openDevice()
        val session = propose()
        val signing = db.frostSigningSessionDao().getItems(session.id).map { it.eventId }

        val resent = queuedBy(FrostSigningEvents.PROPOSAL) { assertTrue(resend(session.id)) }
        val proposed = FrostSigningEvents.decodeProposal(resent.content)

        // The load-bearing one. A resend that proposed different events under the
        // same session id would be asking for two signatures out of one secret
        // nonce, which `acceptProposal` refuses -- so it would be a resend that
        // repairs nothing while looking like it worked.
        assertEquals(signing, proposed?.map { it.id })
        assertEquals(signing, db.frostSigningSessionDao().getItems(session.id).map { it.eventId })
    }

    @Test
    fun `a resend names the whole room, because the p-tags are the room's identity`() = runBlocking {
        openDevice()
        val session = propose()

        val first = queued(FrostSigningEvents.PROPOSAL).single()

        // Bob answers; Carol does not. A refusal rather than a nonce, because
        // Alice already holds her own: a second nonce reaches the threshold and
        // sends her off to aggregate, which this test has nothing valid to give
        // her. One refusal in a 2-of-3 group leaves the session open.
        receive(session.id, FrostSigningEvents.DECLINE, bob, "Declined to sign")
        assertEquals(FrostSigningStage.COLLECTING_NONCES, session(session.id).stage)

        val resent = queuedBy(FrostSigningEvents.PROPOSAL) { assertTrue(resend(session.id)) }

        // Tempting to send this to Carol alone, and wrong. A receiver works out
        // which room a NIP-17 payload belongs to by hashing the sender together
        // with the p-tags -- `GiftWrapPayload.aggregatedParticipantsPublicKey`.
        // Name fewer members and the id changes, so the resend is filed under a
        // room that does not exist, and `getOrCreateChatRoom` mints it rather
        // than rejecting it: a phantom conversation instead of a repaired
        // session. Who the resend is *for* travels in the silent tag, which is
        // not part of that hash.
        assertEquals(pTags(first.tags), pTags(resent.tags))
        assertEquals(setOf(bob, carol), pTags(resent.tags))
        assertEquals(setOf(carol), FrostSigningEvents.parseSilentMembers(resent.tags)?.toSet())
    }

    @Test
    fun `a member who declined is not silent`() = runBlocking {
        openDevice()
        val session = propose()

        receive(session.id, FrostSigningEvents.DECLINE, bob, "Declined to sign")

        val resent = queuedBy(FrostSigningEvents.PROPOSAL) { assertTrue(resend(session.id)) }

        // Bob answered. Knocking again would be asking a question he has closed.
        assertEquals(setOf(carol), FrostSigningEvents.parseSilentMembers(resent.tags)?.toSet())
    }

    @Test
    fun `a resend with nobody silent does nothing`() = runBlocking {
        openDevice()
        val session = propose()

        // Bob refuses, and Carol's nonce is written straight to the message table
        // rather than delivered: a second nonce reaching Alice would send her off
        // to aggregate it, and this test has no valid nonce to give her. What is
        // being checked is who counts as silent, which is read from these rows.
        receive(session.id, FrostSigningEvents.DECLINE, bob, "Declined to sign")
        db.frostSigningSessionDao().upsert(
            FrostSignerMessage(
                sessionId = session.id,
                signerPublicKey = carol,
                kind = FrostSigningEvents.NONCE,
                payload = "c".repeat(132)
            )
        )

        assertFalse(resend(session.id))
        assertEquals(1, queued(FrostSigningEvents.PROPOSAL).size)
        assertTrue(transcript(ChatMessage.TYPE_FROST_RESENT).isEmpty())
        assertNull(session(session.id).resentAt)
    }

    @Test
    fun `only the device that proposed may resend`() = runBlocking {
        openDevice(bob)
        val session = proposalArrivedAt(bob)

        assertFalse(resend(session.id))
        assertTrue(queued(FrostSigningEvents.PROPOSAL).isEmpty())
    }

    @Test
    fun `an abandoned session is not resent`() = runBlocking {
        openDevice()
        val session = propose()

        receive(session.id, FrostSigningEvents.FAILURE, bob, "Something went wrong")
        assertEquals(FrostSigningStage.FAILED, session(session.id).stage)

        // Its items' nonce seeds have been published against an aggregate
        // already. The retry is a fresh proposal, never this one again.
        assertFalse(resend(session.id))
        assertEquals(1, queued(FrostSigningEvents.PROPOSAL).size)
    }

    @Test
    fun `the cooldown stops a second resend`() = runBlocking {
        openDevice()
        val session = propose()

        assertTrue(resend(session.id))
        val resentAt = session(session.id).resentAt
        assertNotNull(resentAt)

        assertFalse(resend(session.id), "a second tap inside the cooldown publishes nothing")
        assertEquals(2, queued(FrostSigningEvents.PROPOSAL).size)
        assertEquals(1, transcript(ChatMessage.TYPE_FROST_RESENT).size)
        assertEquals(resentAt, session(session.id).resentAt)
    }

    @Test
    fun `a resend past the cooldown goes out again`() = runBlocking {
        openDevice()
        val session = propose()

        assertTrue(resend(session.id))

        // Wound back rather than waited out: the cooldown is minutes.
        db.frostSigningSessionDao().upsert(
            session(session.id).copy(
                resentAt = Clock.System.now() - FrostSigningManager.RESEND_COOLDOWN * 2
            )
        )

        assertTrue(resend(session.id))
        assertEquals(3, queued(FrostSigningEvents.PROPOSAL).size)
        assertEquals(2, transcript(ChatMessage.TYPE_FROST_RESENT).size)
    }

    @Test
    fun `two resends in the same second do not collide`() = runBlocking {
        openDevice()
        val session = propose()

        assertTrue(resend(session.id))
        db.frostSigningSessionDao().upsert(session(session.id).copy(resentAt = null))
        assertTrue(resend(session.id))

        val proposals = queued(FrostSigningEvents.PROPOSAL)

        // An outbound row's id is a hash over its `created_at`, which is stored
        // to the second. Two identical publishes inside one second would hash
        // the same, and the upsert would put the already-sent row back on the
        // queue with its mapping cleared -- double-sending it and losing the
        // group event it became. So the second steps a second forward instead.
        assertEquals(3, proposals.size)
        assertEquals(3, proposals.map { it.id }.toSet().size, "three rows, three ids")
    }

    @Test
    fun `a resend writes one line in the transcript`() = runBlocking {
        openDevice()
        val session = propose()

        assertTrue(resend(session.id))

        val lines = transcript(ChatMessage.TYPE_FROST_RESENT)
        assertEquals(1, lines.size)
        assertEquals(alice, lines.single().senderPublicKey)
        assertTrue(lines.single().content.contains("2 members"))
    }

    // ---- the member's half ------------------------------------------------

    @Test
    fun `a member named as silent republishes the nonce they already sent`() = runBlocking {
        openDevice(bob)
        val session = proposalArrivedAt(bob)

        FrostSigningManager.approve(database = db, localChatRoom = room, sessionId = session.id)

        val first = queued(FrostSigningEvents.NONCE)
        assertEquals(1, first.size)

        // Alice never got it, and says so.
        val resent = queuedBy(FrostSigningEvents.NONCE) {
            receive(session.id, FrostSigningEvents.PROPOSAL, alice, proposalContent(), silent = listOf(bob))
        }

        assertEquals(2, queued(FrostSigningEvents.NONCE).size, "the nonce goes out a second time")

        // The load-bearing assertion in this file. Two different nonces for one
        // message against two aggregates is how a secret share is extracted, so
        // a republish has to be the same bytes and not a fresh nonce.
        assertEquals(first.single().content, resent.content, "the same bytes, not a new nonce")
    }

    @Test
    fun `a republished nonce names the whole room`() = runBlocking {
        openDevice(bob)
        val session = proposalArrivedAt(bob)

        FrostSigningManager.approve(database = db, localChatRoom = room, sessionId = session.id)
        val first = queued(FrostSigningEvents.NONCE).single()

        val resent = queuedBy(FrostSigningEvents.NONCE) {
            receive(session.id, FrostSigningEvents.PROPOSAL, alice, proposalContent(), silent = listOf(bob))
        }

        // Only the coordinator reads a nonce, so addressing one would be the
        // honest shape -- and it would file the message under a two-person room
        // that is not the one the session is running in. The p-tag set is the
        // room's identity on this transport, not a recipient list.
        assertEquals(pTags(first.tags), pTags(resent.tags))
        assertEquals(setOf(alice, carol), pTags(resent.tags))
    }

    @Test
    fun `a member named as silent who has not decided publishes nothing`() = runBlocking {
        openDevice(bob)
        val session = proposalArrivedAt(bob)

        assertTrue(queued(FrostSigningEvents.NONCE).isEmpty())

        receive(session.id, FrostSigningEvents.PROPOSAL, alice, proposalContent(), silent = listOf(bob))

        // The strongest limit here. A message from the coordinator must never be
        // able to put this device's share behind events its owner has not agreed
        // to; the session is still waiting on a person, and says so.
        assertTrue(queued(FrostSigningEvents.NONCE).isEmpty())
        assertNull(session(session.id).signApprovedAt)
        assertTrue(
            FrostSigningManager.isAwaitingApproval(
                session(session.id),
                db.frostSigningSessionDao().getItems(session.id)
            )
        )
    }

    @Test
    fun `a member named as silent republishes the refusal they already sent`() = runBlocking {
        openDevice(bob)
        val session = proposalArrivedAt(bob)

        FrostSigningManager.decline(database = db, localChatRoom = room, sessionId = session.id)
        val first = queued(FrostSigningEvents.DECLINE)
        assertEquals(1, first.size)

        val resent = queuedBy(FrostSigningEvents.DECLINE) {
            receive(session.id, FrostSigningEvents.PROPOSAL, alice, proposalContent(), silent = listOf(bob))
        }

        assertEquals(2, queued(FrostSigningEvents.DECLINE).size)
        assertEquals(first.single().content, resent.content)
    }

    @Test
    fun `a member not named stays quiet`() = runBlocking {
        openDevice(bob)
        val session = proposalArrivedAt(bob)

        FrostSigningManager.approve(database = db, localChatRoom = room, sessionId = session.id)
        assertEquals(1, queued(FrostSigningEvents.NONCE).size)

        receive(session.id, FrostSigningEvents.PROPOSAL, alice, proposalContent(), silent = listOf(carol))

        assertEquals(1, queued(FrostSigningEvents.NONCE).size)
    }

    @Test
    fun `a nudge from somebody who is not the coordinator is ignored`() = runBlocking {
        openDevice(bob)
        val session = proposalArrivedAt(bob)

        FrostSigningManager.approve(database = db, localChatRoom = room, sessionId = session.id)
        assertEquals(1, queued(FrostSigningEvents.NONCE).size)

        // Carol naming Bob as silent. The bytes Bob would send are ones he has
        // already sent, so nothing on the wire changes -- but a publish anybody
        // in the room can trigger is not a thing to leave lying around.
        receive(session.id, FrostSigningEvents.PROPOSAL, carol, proposalContent(), silent = listOf(bob))

        assertEquals(1, queued(FrostSigningEvents.NONCE).size)
    }

    @Test
    fun `a resent proposal for a session already held changes nothing about it`() = runBlocking {
        openDevice(bob)
        val session = proposalArrivedAt(bob)
        val before = db.frostSigningSessionDao().getItems(session.id)

        receive(session.id, FrostSigningEvents.PROPOSAL, alice, proposalContent(), silent = listOf(bob))

        val after = db.frostSigningSessionDao().getItems(session.id)
        assertEquals(before.map { it.eventId }, after.map { it.eventId })
        assertEquals(before.map { it.nonceRandom }, after.map { it.nonceRandom })
        // `acceptProposal` returns the session it already holds before it
        // announces anything, so a resend writes no second "asked the group"
        // line -- and this device's session was written straight to the row,
        // so there is no first one either.
        assertTrue(transcript(ChatMessage.TYPE_FROST_STARTED).isEmpty())
    }

    // ---- the transport every ordinary session actually uses ---------------

    @Test
    fun `a resend in a Marmot room is one group event that names nobody`() = runBlocking {
        openDevice(alice, marmot = true)
        val session = propose()

        // The transport the admin room uses, and so the one almost every
        // signature is made on -- NIP-17 is only a group's first. One group
        // event reaches everybody, and every receiver folds the duplicate away.
        val resent = queuedInnerBy(FrostSigningEvents.PROPOSAL) { assertTrue(resend(session.id)) }

        assertEquals(2, queuedInner(FrostSigningEvents.PROPOSAL).size)
        assertTrue(queued(FrostSigningEvents.PROPOSAL).isEmpty(), "no gift wrap on this transport")

        // A group event is encrypted to the group, and who is in it is the MLS
        // tree's business -- so this names nobody, and there is nothing here to
        // narrow or to get wrong the way the NIP-17 p-tags were.
        assertTrue(pTags(resent.tags).isEmpty())
        assertEquals(
            setOf(bob, carol),
            FrostSigningEvents.parseSilentMembers(resent.tags)?.toSet(),
            "who it is for still travels, in the tag rather than the addressing"
        )
        assertEquals(ceremonyId, FrostSigningEvents.parseKey(resent.tags))
    }

    @Test
    fun `a nudged member in a Marmot room republishes as a group event`() = runBlocking {
        openDevice(bob, marmot = true)
        val session = proposalArrivedAt(bob)

        FrostSigningManager.approve(database = db, localChatRoom = room, sessionId = session.id)
        val first = queuedInner(FrostSigningEvents.NONCE)
        assertEquals(1, first.size)

        val resent = queuedInnerBy(FrostSigningEvents.NONCE) {
            receive(session.id, FrostSigningEvents.PROPOSAL, alice, proposalContent(), silent = listOf(bob))
        }

        assertEquals(2, queuedInner(FrostSigningEvents.NONCE).size)
        assertEquals(first.single().content, resent.content, "the same bytes on this transport too")
    }

    @Test
    fun `two resends in the same second do not collide in a Marmot room`() = runBlocking {
        openDevice(alice, marmot = true)
        val session = propose()

        assertTrue(resend(session.id))
        db.frostSigningSessionDao().upsert(session(session.id).copy(resentAt = null))
        assertTrue(resend(session.id))

        // The id guard reads whichever store the room's transport writes to, so
        // it has to be checked on both. A collision here would put an
        // already-sent inner event back on the queue with its
        // marmotGroupEventId cleared, losing the group event it became.
        val proposals = queuedInner(FrostSigningEvents.PROPOSAL)
        assertEquals(3, proposals.size)
        assertEquals(3, proposals.map { it.id }.toSet().size)
    }

    // ---- the branches the stage table picks -------------------------------

    @Test
    fun `a resend while collecting partial signatures repeats the signer set`() = runBlocking {
        openDevice()
        val session = waitingOnBobsPart()

        // The middle branch of the stage table, and the one a member stalls on:
        // they have the proposal and their nonce is in, but the signer set that
        // tells them what to sign against never arrived.
        val aggregated = db.frostSigningSessionDao().getItems(session.id).single().aggregatedNonce

        assertTrue(resend(session.id))

        // Two: the one `advance` sent when it chose the signers, and the repeat.
        val signerSet = queued(FrostSigningEvents.SIGNER_SET)
        assertEquals(2, signerSet.size)
        assertTrue(signerSet.all { it.content == aggregated })
        assertTrue(signerSet.all { FrostSigningEvents.parseSignerIds(it.tags) == listOf(0, 1) })

        // And the proposal with it, because a device holding neither can file
        // neither: a signer set naming ids it has no items for is dropped.
        assertEquals(2, queued(FrostSigningEvents.PROPOSAL).size)
        assertTrue(queued(FrostSigningEvents.SIGNATURE).isEmpty())
    }

    @Test
    fun `the knock at the signing stage is for the signer who has not signed`() = runBlocking {
        openDevice()
        val session = waitingOnBobsPart()

        // Bob offered a nonce, which is why the session got this far, and that
        // used to be enough to count him as answered -- leaving the knock for
        // Carol, who was never chosen and owes nothing.
        assertEquals(setOf(bob), FrostSigningManager.membersOwing(db, session))

        // By id rather than by time: the proposal and its repeat routinely share
        // a `createdAt`, since it is stored to the second.
        val resent = queuedBy(FrostSigningEvents.PROPOSAL) { assertTrue(resend(session.id)) }

        assertEquals(listOf(bob), FrostSigningEvents.parseSilentMembers(resent.tags))
    }

    @Test
    fun `a signer who has sent their part is not knocked on again`() = runBlocking {
        openDevice()
        val session = waitingOnBobsPart()

        // Filed rather than received: a partial signature that is not Bob's real
        // one would fail the aggregation and abandon the session, and what this
        // is about is the bookkeeping either side of that.
        fileMessage(session.id, FrostSigningEvents.PARTIAL_SIGNATURE, bob, "9".repeat(64))

        // Carol never answered at all, and is still not who this stage waits on.
        assertTrue(FrostSigningManager.membersOwing(db, session).isEmpty())
        assertFalse(resend(session.id))

        assertEquals(1, queued(FrostSigningEvents.PROPOSAL).size)
    }

    @Test
    fun `a chosen signer who refused is not knocked on either`() = runBlocking {
        openDevice()
        val session = waitingOnBobsPart()

        fileMessage(session.id, FrostSigningEvents.DECLINE, bob, "Declined to sign")

        // They answered the question this stage asks, even though the answer
        // leaves the session needing a different signer.
        assertTrue(FrostSigningManager.membersOwing(db, session).isEmpty())
        assertFalse(resend(session.id))
    }

    @Test
    fun `the transcript says what the signers have not sent`() = runBlocking {
        openDevice()
        val session = waitingOnBobsPart()

        assertTrue(resend(session.id))

        val line = transcript(ChatMessage.TYPE_FROST_RESENT).single()
        assertTrue(line.content.contains("has not signed their part"), "got: ${line.content}")
        assertFalse(line.content.contains("not answered"), "got: ${line.content}")
    }

    @Test
    fun `a resend sends no signer set before one has been chosen`() = runBlocking {
        openDevice()
        val session = propose()

        assertTrue(resend(session.id))

        // COLLECTING_NONCES: there is nothing but the proposal to repeat.
        assertEquals(2, queued(FrostSigningEvents.PROPOSAL).size)
        assertTrue(queued(FrostSigningEvents.SIGNER_SET).isEmpty())
        assertTrue(queued(FrostSigningEvents.SIGNATURE).isEmpty())
    }

    @Test
    fun `a nudged member republishes the partial signature they already sent`() = runBlocking {
        openDevice(bob)
        val session = proposalArrivedAt(bob)

        // The third kind `republishOwn` repeats. A partial signature is read by
        // nobody but the coordinator, so one lost on the way to them stalls a
        // session that has every other piece it needs.
        db.frostSigningSessionDao().upsert(
            FrostSignerMessage(
                sessionId = session.id,
                signerPublicKey = bob,
                kind = FrostSigningEvents.PARTIAL_SIGNATURE,
                payload = "9".repeat(64)
            )
        )

        val resent = queuedBy(FrostSigningEvents.PARTIAL_SIGNATURE) {
            receive(session.id, FrostSigningEvents.PROPOSAL, alice, proposalContent(), silent = listOf(bob))
        }

        assertEquals("9".repeat(64), resent.content)
    }

    // ---- refusals and wording ---------------------------------------------

    @Test
    fun `a resend refuses a proposal whose stored events will not parse`() = runBlocking {
        openDevice()
        val session = propose()

        db.frostSigningSessionDao().upsertItems(
            db.frostSigningSessionDao().getItems(session.id).map {
                it.copy(unsignedEventJson = "not an event")
            }
        )

        // Refused rather than shortened. A proposal that quietly lost an event
        // would have every signer's contribution rejected for being the wrong
        // length, which is a stall with nothing to blame.
        assertFalse(resend(session.id))
        assertEquals(1, queued(FrostSigningEvents.PROPOSAL).size)
        assertNull(session(session.id).resentAt)
    }

    @Test
    fun `a silent tag on anything but a proposal is ignored`() = runBlocking {
        openDevice(bob)
        val session = proposalArrivedAt(bob)

        FrostSigningManager.approve(database = db, localChatRoom = room, sessionId = session.id)
        assertEquals(1, queued(FrostSigningEvents.NONCE).size)

        // The nudge rides a proposal because a device with no session has
        // nowhere to file a bare one. A tag on another kind is not a nudge, and
        // honouring it would be a publish triggered by any message at all.
        receive(session.id, FrostSigningEvents.NONCE, alice, "f".repeat(132), silent = listOf(bob))

        assertEquals(1, queued(FrostSigningEvents.NONCE).size)
    }

    @Test
    fun `the transcript says one member when only one is quiet`() = runBlocking {
        openDevice()
        val session = propose()

        receive(session.id, FrostSigningEvents.DECLINE, bob, "Declined to sign")

        assertTrue(resend(session.id))

        val line = transcript(ChatMessage.TYPE_FROST_RESENT).single()
        assertTrue(line.content.contains("the member"), "got: ${line.content}")
        assertFalse(line.content.contains("1 members"))
    }

    /** What a resent proposal carries: the events of [proposalArrivedAt]'s session. */
    private fun proposalContent(): String =
        FrostSigningEvents.encodeProposal(listOf(unsignedEvent()))

    // ---- the case the feature exists for ----------------------------------

    /**
     * A real resent proposal, taken off a coordinator device and handed to a
     * member's.
     *
     * Two databases rather than a hand-written row, because this is the one test
     * that goes through [FrostSigningManager.acceptProposal] from the outside. An
     * event's id is rebuilt from its own fields under the author the *receiver*
     * resolves -- the room's key at the room's derivation path, never the
     * proposer's word for it -- and a proposal whose id does not survive that is
     * dropped. A fabricated event cannot pass it, which is the point of the
     * check, so the proposal has to be one a coordinator really made.
     *
     * Returns the session id and the resent proposal's content, with [db] and
     * [room] left pointing at [owner]'s freshly opened device.
     */
    private suspend fun resentProposalFor(owner: HexKey): Pair<String, String> {
        openDevice(alice)
        val session = propose()
        val resent = queuedBy(FrostSigningEvents.PROPOSAL) {
            assertTrue(FrostSigningManager.resend(db, room, session.id))
        }
        val content = resent.content
        db.close()

        openDevice(owner)
        return session.id to content
    }

    @Test
    fun `a resend opens the session on a device that never saw the proposal`() = runBlocking {
        val (sessionId, content) = resentProposalFor(bob)

        // The case the whole feature exists for. Bob's copy of the first
        // proposal decrypted to nothing -- MLS had moved its ratchet past the
        // generation it arrived at, which is terminal and which the sender
        // cannot see (docs/mls-skipped-keys.md). He has no session at all.
        assertNull(db.frostSigningSessionDao().getSessionById(sessionId))

        receive(sessionId, FrostSigningEvents.PROPOSAL, alice, content, silent = listOf(bob), includeKey = true)

        val session = db.frostSigningSessionDao().getSessionById(sessionId)
        assertNotNull(session, "the resent proposal opens the session Bob was missing")
        assertEquals(alice, session.coordinatorPublicKey)
        assertEquals(bob, session.userPublicKey)
        assertEquals(1, db.frostSigningSessionDao().getItems(sessionId).size)

        // And he is asked, rather than signed for. A relay delivering an event
        // to a phone in a pocket must not be enough to make a signature happen.
        assertTrue(
            FrostSigningManager.isAwaitingApproval(
                session,
                db.frostSigningSessionDao().getItems(sessionId)
            )
        )
        assertTrue(queued(FrostSigningEvents.NONCE).isEmpty())
        assertEquals(1, transcript(ChatMessage.TYPE_FROST_APPROVAL_NEEDED).size)
    }

    @Test
    fun `being named as silent does not sign for a device that has just learned of the session`() =
        runBlocking {
            val (sessionId, content) = resentProposalFor(bob)

            receive(sessionId, FrostSigningEvents.PROPOSAL, alice, content, silent = listOf(bob), includeKey = true)

            // Named *and* new. There is nothing of Bob's on file to republish,
            // so the nudge finds nothing -- and must not reach for the nonce it
            // could generate instead.
            assertTrue(queued(FrostSigningEvents.NONCE).isEmpty())
            assertTrue(queued(FrostSigningEvents.DECLINE).isEmpty())
            assertNull(db.frostSigningSessionDao().getSessionById(sessionId)!!.signApprovedAt)
        }

    @Test
    fun `a resent proposal delivered twice opens one session`() = runBlocking {
        val (sessionId, content) = resentProposalFor(bob)

        receive(sessionId, FrostSigningEvents.PROPOSAL, alice, content, silent = listOf(bob), includeKey = true)
        val items = db.frostSigningSessionDao().getItems(sessionId)

        // Relays redeliver, and a resend makes duplicates routine rather than
        // exceptional. A second copy must not mint new nonce seeds: that is one
        // secret nonce replaced under a message that has not changed, which is
        // the stall the write-once rule exists to prevent.
        receive(sessionId, FrostSigningEvents.PROPOSAL, alice, content, silent = listOf(bob), includeKey = true)

        val after = db.frostSigningSessionDao().getItems(sessionId)
        assertEquals(items.map { it.nonceRandom }, after.map { it.nonceRandom })
        assertEquals(items.map { it.eventId }, after.map { it.eventId })
        assertEquals(1, transcript(ChatMessage.TYPE_FROST_STARTED).size)
        assertEquals(1, transcript(ChatMessage.TYPE_FROST_APPROVAL_NEEDED).size)
    }

    @Test
    fun `a republished refusal is counted once`() = runBlocking {
        openDevice()
        val session = propose()

        // What the coordinator sees when a nudged member says their refusal
        // again: the same message twice. Counting it twice would abandon a
        // 2-of-3 session on one member's single no.
        receive(session.id, FrostSigningEvents.DECLINE, bob, "Declined to sign")
        receive(session.id, FrostSigningEvents.DECLINE, bob, "Declined to sign")

        assertEquals(FrostSigningStage.COLLECTING_NONCES, session(session.id).stage)
        assertEquals(1, transcript(ChatMessage.TYPE_FROST_DECLINED).size)
        assertEquals(
            1,
            db.frostSigningSessionDao()
                .getMessagesByKind(session.id, FrostSigningEvents.DECLINE)
                .size
        )
    }

    @Test
    fun `a session the group has already signed can still be resent`() = runBlocking {
        openDevice()
        val session = propose()

        // A member who never received the signature never applied the events.
        // Repeating it is the only thing that gets their store right, so COMPLETE
        // is worth resending rather than treated as finished business.
        db.frostSigningSessionDao().upsert(
            session(session.id).copy(stage = FrostSigningStage.COMPLETE)
        )
        db.frostSigningSessionDao().upsertItems(
            db.frostSigningSessionDao().getItems(session.id).map {
                it.copy(signature = "d".repeat(128))
            }
        )

        assertTrue(resend(session.id))
        assertEquals(1, queued(FrostSigningEvents.SIGNATURE).size)
        assertEquals(2, queued(FrostSigningEvents.PROPOSAL).size)
    }

    // ---- the step no message drives ---------------------------------------

    /**
     * Bob's device at the moment process death is worst: he agreed, his nonce
     * went out, the coordinator's signer set arrived and was filed -- and the
     * pass that would have signed his part never finished.
     *
     * Written into the row rather than received, because that is what the state
     * is: the signer set has been *recorded*, so redelivering it changes
     * nothing, and nothing else in the protocol will ever ask Bob again.
     */
    private suspend fun killedBeforeSigning(): FrostSigningSession {
        val session = proposalArrivedAt(bob)

        FrostSigningManager.approve(database = db, localChatRoom = room, sessionId = session.id)

        // The aggregate the coordinator would have sent: Alice's nonce and the
        // one Bob's device published from the seed on its item row.
        val stored = db.frostSigningSessionDao().getItems(session.id).single()
        val bobsNonce = db.frostSigningSessionDao()
            .getMessage(session.id, FrostSigningEvents.NONCE, bob)!!
            .payload

        val aggregated = IndividualNonce.aggregate(
            listOf(
                IndividualNonce(publicNonceOf(alice).hexToByteArray()),
                IndividualNonce(bobsNonce.hexToByteArray())
            )
        ).right!!

        db.frostSigningSessionDao().upsertItems(
            listOf(stored.copy(aggregatedNonce = aggregated.toByteArray().toHex()))
        )
        db.frostSigningSessionDao().upsert(
            session(session.id).copy(
                signerIds = "0,1",
                stage = FrostSigningStage.COLLECTING_PARTIAL_SIGNATURES
            )
        )

        return session(session.id)
    }

    @Test
    fun `an answered session signs its part when the room is next opened`() = runBlocking {
        openDevice(bob)
        val session = killedBeforeSigning()

        assertTrue(queued(FrostSigningEvents.PARTIAL_SIGNATURE).isEmpty())

        FrostSigningManager.resumeAnswered(database = db, localChatRoom = room)

        // The step the session had everything for and no way to reach.
        val part = queued(FrostSigningEvents.PARTIAL_SIGNATURE).single()
        val filed = db.frostSigningSessionDao()
            .getMessage(session.id, FrostSigningEvents.PARTIAL_SIGNATURE, bob)

        assertEquals(64, part.content.length)
        assertEquals(part.content, filed?.payload)
    }

    @Test
    fun `resuming twice publishes one partial signature`() = runBlocking {
        openDevice(bob)
        killedBeforeSigning()

        FrostSigningManager.resumeAnswered(database = db, localChatRoom = room)
        FrostSigningManager.resumeAnswered(database = db, localChatRoom = room)

        // Every step asks whether its output exists rather than whether a stage
        // says it ran, which is what makes a sweep safe to call on every open.
        assertEquals(1, queued(FrostSigningEvents.PARTIAL_SIGNATURE).size)
    }

    @Test
    fun `a session its owner has not answered is left alone`() = runBlocking {
        openDevice(bob)
        val session = proposalArrivedAt(bob)

        // The gate a resume must not reach past. Bob has the proposal and
        // nothing else; a sweep that signed here would have a relay delivering
        // an event to a phone in a pocket be enough to make the group sign.
        db.frostSigningSessionDao().upsert(
            session(session.id).copy(
                signerIds = "0,1",
                stage = FrostSigningStage.COLLECTING_PARTIAL_SIGNATURES
            )
        )

        FrostSigningManager.resumeAnswered(database = db, localChatRoom = room)

        assertTrue(queued(FrostSigningEvents.NONCE).isEmpty())
        assertTrue(queued(FrostSigningEvents.PARTIAL_SIGNATURE).isEmpty())
    }

    @Test
    fun `a refusal decided but never sent goes out on the next open`() = runBlocking {
        openDevice(bob)
        val session = proposalArrivedAt(bob)

        // Killed between deciding and publishing: the row says no, the group has
        // not been told, and the coordinator is waiting on a member who has
        // already made up their mind.
        db.frostSigningSessionDao().upsert(
            session(session.id).copy(signDeclinedAt = Clock.System.now())
        )

        FrostSigningManager.resumeAnswered(database = db, localChatRoom = room)

        assertEquals(1, queued(FrostSigningEvents.DECLINE).size)
        assertTrue(queued(FrostSigningEvents.NONCE).isEmpty())
    }

    @Test
    fun `a settled session is not swept`() = runBlocking {
        openDevice(bob)
        val session = proposalArrivedAt(bob)

        FrostSigningManager.approve(database = db, localChatRoom = room, sessionId = session.id)
        db.frostSigningSessionDao().upsert(
            session(session.id).copy(stage = FrostSigningStage.FAILED)
        )

        val before = queued(FrostSigningEvents.NONCE).size

        FrostSigningManager.resumeAnswered(database = db, localChatRoom = room)

        assertEquals(before, queued(FrostSigningEvents.NONCE).size)
    }

    // --- asking about several at once ------------------------------------------------

    private suspend fun resendAll(vararg sessionIds: String) =
        FrostSigningManager.resendAll(
            database = db,
            localChatRoom = room,
            sessionIds = sessionIds.toList()
        )

    @Test
    fun `asking about several sessions resends each of them`() = runBlocking<Unit> {
        openDevice()
        val one = propose()
        val two = propose()

        assertEquals(2, resendAll(one.id, two.id))

        // Two proposals of their own, plus the resend of each.
        assertEquals(4, queued(FrostSigningEvents.PROPOSAL).size)
        assertEquals(2, transcript(ChatMessage.TYPE_FROST_RESENT).size)
        assertNotNull(session(one.id).resentAt)
        assertNotNull(session(two.id).resentAt)
    }

    /**
     * The spacing, and why it is a second.
     *
     * A resend publishes two messages, which is a burst the room survives --
     * `MlsGroupCache` covers a burst decrypted in one sync, and `resend` keeps to
     * two deliberately for that reason. Several at once would be outside that
     * budget, in the same second that "exactly one survives" was written about.
     * So the sessions are spaced, and the `created_at` seconds are where that
     * shows.
     */
    @Test
    fun `the sessions are spaced a second apart, not published together`() = runBlocking<Unit> {
        openDevice()
        val one = propose()
        val two = propose()
        val three = propose()

        val before = Clock.System.now()
        assertEquals(3, resendAll(one.id, two.id, three.id))
        val elapsed = Clock.System.now() - before

        assertTrue(
            elapsed >= FrostSigningManager.RESEND_SPACING * 2,
            "three sessions went out in $elapsed, faster than the spacing allows"
        )

        // And the seconds they claim differ, which is what a relay with no
        // ordering reads: two events in one second race and one survives.
        val seconds = listOf(one, two, three)
            .mapNotNull { session(it.id).resentAt?.epochSeconds }

        assertEquals(3, seconds.size)
        assertEquals(3, seconds.toSet().size, "two sessions were resent in the same second")
    }

    /**
     * One session declining is not a reason to abandon the others -- a cooldown
     * that has not run out is the ordinary way for that to happen, and the count
     * returned is of the ones that actually went out.
     */
    @Test
    fun `a session inside its cooldown is stepped over, not a reason to stop`() = runBlocking<Unit> {
        openDevice()
        val one = propose()
        val two = propose()
        val three = propose()

        // The middle one has just been asked about on its own.
        assertTrue(resend(two.id))
        val alreadyAsked = session(two.id).resentAt

        assertEquals(2, resendAll(one.id, two.id, three.id), "the skipped one was counted")

        assertNotNull(session(one.id).resentAt)
        assertNotNull(session(three.id).resentAt)
        assertEquals(alreadyAsked, session(two.id).resentAt, "the cooling session was resent")
    }

    @Test
    fun `a session that is not ours is stepped over too`() = runBlocking<Unit> {
        openDevice(owner = bob)
        val theirs = proposalArrivedAt(bob)

        assertEquals(0, resendAll(theirs.id))
        assertNull(session(theirs.id).resentAt)
    }

    @Test
    fun `asking about nothing publishes nothing`() = runBlocking<Unit> {
        openDevice()
        propose()

        assertEquals(0, resendAll())
        assertEquals(1, queued(FrostSigningEvents.PROPOSAL).size)
    }
}
