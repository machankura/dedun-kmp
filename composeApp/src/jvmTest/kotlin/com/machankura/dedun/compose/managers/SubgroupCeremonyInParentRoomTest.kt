package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.nostr.subgroup.tags.GuardianDelayTag
import androidx.room3.Room
import com.vitorpamplona.quartz.marmot.mip01Groups.MarmotGroupData
import com.vitorpamplona.quartz.marmot.mls.crypto.Ed25519
import com.vitorpamplona.quartz.marmot.mls.group.MlsGroup
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.tags.people.taggedUsers
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.crypto.frost.Frost
import fr.acinq.bitcoin.crypto.frost.KeyMaterial
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.DkgParticipantMessage
import com.machankura.dedun.compose.database.model.DkgSession
import com.machankura.dedun.compose.database.model.MarmotInnerEvent
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.Participant
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.types.DkgApprovalStep
import com.machankura.dedun.compose.database.model.types.DkgRitualStage
import com.machankura.dedun.compose.extensions.toHex
import com.machankura.dedun.compose.nostr.Relays
import com.machankura.dedun.compose.nostr.dkg.DkgRitualEvents
import com.machankura.dedun.compose.nostr.subgroup.SubgroupWalletPolicy
import com.machankura.dedun.compose.nostr.subgroup.tags.WalletPolicyTag
import com.machankura.dedun.compose.database.model.subgroupWalletPolicy

/**
 * A subgroup's key ceremony, held in the room of the group making it.
 *
 * It used to be held in a sibling NIP-17 room whose id was an aggregate of the
 * child's admins. That bought a transport tolerant of reordering, and it cost a
 * room per subgroup and a hard limit -- one admin set could hold one subgroup
 * ever, because asking again derived the same room and handed back the same key.
 * Now the ceremony runs where the parent already is, and everything the sibling
 * room used to answer has to be answered by the session instead.
 *
 * Three of those answers are what this covers, because each one silently reads
 * the wrong thing if it comes off the room:
 *
 * - **who the ceremony is with** is a subset of the parent's members, so the
 *   room's roster names every person the subgroup is not;
 * - **what the room it makes is called** is not the parent's name, which is the
 *   one name a child must not take;
 * - **what key the parent signs with** is not the child's, which is now a
 *   completed ceremony sitting in the parent's own room.
 *
 * The MLS group here is real but not joint: messages are handed to the manager
 * directly rather than encrypted and decrypted between two trees. That is the
 * right seam for these -- what is under test is which store a message is queued
 * in and which set it names, not whether quartz can encrypt it -- and
 * `SignedGroupKeyStateTest` is where a session is run end to end.
 */
class SubgroupCeremonyInParentRoomTest {

    private val parent: DedunDatabase = getRoomDatabase(
        Room.inMemoryDatabaseBuilder<DedunDatabase>()
    )

    /** A second device's database, so a proposal can be received rather than made. */
    private val receiver: DedunDatabase = getRoomDatabase(
        Room.inMemoryDatabaseBuilder<DedunDatabase>()
    )

    @AfterTest
    fun closeDb() {
        parent.close()
        receiver.close()
    }

    /** The parent's own key, which its room's id is derived from. */
    private val parentMaterial: KeyMaterial = Frost.trustedDealerKeygen(
        thresholdSecretKey = PrivateKey(
            ByteVector32("1c0ffee0000000000000000000000000000000000000000000000000000000a1")
        ),
        nParticipants = 4,
        threshold = 3
    )

    private val parentRoomId =
        SharedKeyDerivation.marmotGroupId(parentMaterial.thresholdPublicKey.value.toHex())

    // Real keys throughout: a host key is derived from a nostr secret, and the
    // participant set is sorted by hex, so filler exercises neither.
    private val coordinator = KeyPair()
    private val user = coordinator.pubKey.toHexKey()

    private val aliceKeys = KeyPair()
    private val alice = aliceKeys.pubKey.toHexKey()
    private val bob = KeyPair().pubKey.toHexKey()

    /** A parent member who is not picked for the subgroup. */
    private val mallory = KeyPair()
    private val malloryPublicKey = mallory.pubKey.toHexKey()

    private val members = listOf(alice, bob, malloryPublicKey)

    /** The subgroup's admins: three of the parent's four members. */
    private val admins = setOf(alice, bob)

    private val quorum = 2

    private suspend fun openCeremony(
        room: LocalChatRoom,
        walletPolicy: SubgroupWalletPolicy? = null,
        guardianDelay: Long? = null,
    ) = ChillDkgRitualManager.proposeRitual(
        database = parent,
        localChatRoom = room,
        userPublicKey = user,
        nostrPrivateKey = coordinator.privKey!!,
        threshold = quorum,
        parentChatRoomId = parentRoomId,
        participantPublicKeys = admins,
        subject = "Translation",
        walletPolicy = walletPolicy,
        guardianDelay = guardianDelay
    )

    private suspend fun innerEvents(database: DedunDatabase) = database.marmotInnerEventDao()
        .getByChatRoomAndKinds(parentRoomId, DkgRitualEvents.ALL.toList())

    /** The rumor as the manager queued it, which is what the other side receives. */
    private fun MarmotInnerEvent.asEvent() = Event(
        id = id,
        pubKey = publicKey,
        createdAt = createdAt.epochSeconds,
        kind = kind,
        tags = tags,
        content = content,
        sig = ""
    )

    /**
     * The ceremony goes out as group events, and names only the admins.
     *
     * Both halves matter. The transport is the change; the p-tags are what stops
     * the change being a disaster, because they are the participant set every
     * device derives `n` from -- so leaving them off in a Marmot room, the way
     * `FrostSigningManager` correctly does, would enrol the whole parent in the
     * child's signing quorum.
     */
    @Test
    fun `a subgroup's ceremony rides the parent's transport and names its own admins`(): Unit =
        runBlocking {
            val room = parentRoom()

            assertTrue(innerEvents(parent).isEmpty(), "nothing has gone out yet")

            val session = openCeremony(room)

            assertTrue(
                parent.giftWrapPayloadDao()
                    .getByChatRoomAndKinds(parentRoomId, DkgRitualEvents.ALL.toList())
                    .isEmpty(),
                "a ceremony in a Marmot room queues no gift wraps",
            )

            val proposal = assertNotNull(
                innerEvents(parent).firstOrNull { it.kind == DkgRitualEvents.PROPOSAL },
                "the proposal is an inner event for the parent's room",
            )
            assertEquals(user, proposal.publicKey)
            assertEquals(parentRoomId, proposal.chatRoomId)

            // The p-tags are the roster, not an address list -- the sender is in
            // the set without tagging themselves.
            assertEquals(
                admins,
                proposal.tags.taggedUsers().map { it.pubKey }.toSet(),
                "the proposal names the picked admins and nobody else",
            )
            assertEquals(
                admins + user,
                session.participantPublicKeySet(),
                "and the session records the same set, the coordinator included",
            )
            assertEquals(3, session.participantCount)
            assertEquals(parentRoomId, session.parentChatRoomId)

            // The coordinator's host key goes out on the same transport, since
            // opening a ceremony is the act of agreeing to be in it.
            assertEquals(
                1,
                innerEvents(parent).count { it.kind == DkgRitualEvents.HOST_KEY },
                "the coordinator joins the ceremony it opened",
            )
            assertNotNull(session.hostKeyApprovedAt)
        }

    /**
     * The child's name has nowhere else to live.
     *
     * It used to be the ceremony room's subject: a NIP-17 room called `Translation`
     * whose proposal carried the name to everyone else, and which every later step
     * read back off the room. The parent's room is called something else, and the
     * one name a child must not take is its parent's.
     */
    @Test
    fun `the subgroup's name travels on the proposal and lands on the session`(): Unit = runBlocking {
        val room = parentRoom()
        val session = openCeremony(room)

        assertEquals("Translation", session.subject)
        assertEquals("Ekklesia", room.chatRoom.subject, "the room keeps its own name")

        val proposal = assertNotNull(
            innerEvents(parent).firstOrNull { it.kind == DkgRitualEvents.PROPOSAL }
        )
        assertEquals("Translation", DkgRitualEvents.parseSubject(proposal.tags))

        // Only on the proposal. Every later message belongs to a session the
        // receiver already has this on, and a later say in what a ceremony makes
        // would be a second answer to a settled question.
        innerEvents(parent).filterNot { it.kind == DkgRitualEvents.PROPOSAL }.forEach {
            assertNull(DkgRitualEvents.parseSubject(it.tags), "kind ${it.kind} repeats the name")
        }
    }

    /**
     * The coordinator's choice of wallet policy reaches every admin before they
     * join -- docs/subgroup-wallet-policy.md, Phase 2. It is what the join screen
     * shows and what the certificate is later proposed under, so it has to be on
     * every admin's session, not only the coordinator's.
     */
    @Test
    fun `the wallet policy travels on the proposal and lands on every admin's session`(): Unit = runBlocking {
        val room = parentRoom()
        val session = openCeremony(room, SubgroupWalletPolicy.GUARDED)
        assertEquals(SubgroupWalletPolicy.GUARDED, session.subgroupWalletPolicy)

        val proposal = assertNotNull(
            innerEvents(parent).firstOrNull { it.kind == DkgRitualEvents.PROPOSAL }
        )
        assertEquals(
            WalletPolicyTag.Result.Present(SubgroupWalletPolicy.GUARDED),
            DkgRitualEvents.parseWalletPolicy(proposal.tags),
        )
        innerEvents(parent).filterNot { it.kind == DkgRitualEvents.PROPOSAL }.forEach {
            assertEquals(WalletPolicyTag.Result.Absent, DkgRitualEvents.parseWalletPolicy(it.tags), "kind ${it.kind} repeats the policy")
        }

        ChillDkgRitualManager.processRitualPayload(
            database = receiver,
            localChatRoom = receiverRoom(),
            innerEvent = proposal.asEvent(),
            userPublicKey = alice,
            nostrPrivateKey = aliceKeys.privKey!!
        )
        assertEquals(
            SubgroupWalletPolicy.GUARDED,
            receiver.dkgSessionDao().getSessionById(session.id)?.subgroupWalletPolicy,
        )
    }

    /** docs/guardian-delay.md, Phase 4: the delay travels as the policy does, one field further. */
    @Test
    fun `the guardian delay travels on the proposal and lands on every admin's session`(): Unit = runBlocking {
        val session = openCeremony(parentRoom(), SubgroupWalletPolicy.GUARDED, guardianDelay = 4_320)
        assertEquals(4_320L, session.guardianDelay)

        val proposal = assertNotNull(innerEvents(parent).firstOrNull { it.kind == DkgRitualEvents.PROPOSAL })
        assertEquals(GuardianDelayTag.Result.Present(4_320), DkgRitualEvents.parseGuardianDelay(proposal.tags))
        innerEvents(parent).filterNot { it.kind == DkgRitualEvents.PROPOSAL }.forEach {
            assertEquals(GuardianDelayTag.Result.Absent, DkgRitualEvents.parseGuardianDelay(it.tags), "kind ${it.kind} repeats the delay")
        }

        ChillDkgRitualManager.processRitualPayload(
            database = receiver,
            localChatRoom = receiverRoom(),
            innerEvent = proposal.asEvent(),
            userPublicKey = alice,
            nostrPrivateKey = aliceKeys.privKey!!
        )
        assertEquals(4_320L, receiver.dkgSessionDao().getSessionById(session.id)?.guardianDelay)
    }

    @Test
    fun `a guarded proposal with no delay files none`(): Unit = runBlocking {
        val session = openCeremony(parentRoom(), SubgroupWalletPolicy.GUARDED)
        val proposal = assertNotNull(innerEvents(parent).firstOrNull { it.kind == DkgRitualEvents.PROPOSAL })
        ChillDkgRitualManager.processRitualPayload(
            database = receiver,
            localChatRoom = receiverRoom(),
            innerEvent = proposal.asEvent(),
            userPublicKey = alice,
            nostrPrivateKey = aliceKeys.privKey!!
        )
        val joined = assertNotNull(receiver.dkgSessionDao().getSessionById(session.id))
        assertNull(joined.guardianDelay, "certified later at the default")
    }

    @Test
    fun `a proposal whose delay is out of range, unreadable, or unguarded is dropped`(): Unit = runBlocking {
        val receiving = receiverRoom()
        val cases = mapOf(
            "five blocks" to (SubgroupWalletPolicy.GUARDED to arrayOf(GuardianDelayTag.TAG_NAME, "5")),
            "a word" to (SubgroupWalletPolicy.GUARDED to arrayOf(GuardianDelayTag.TAG_NAME, "twelve")),
            "an unguarded subgroup" to (SubgroupWalletPolicy.UNGUARDED to arrayOf(GuardianDelayTag.TAG_NAME, "144")),
        )
        cases.entries.forEachIndexed { index, (name, case) ->
            val (policy, delayTag) = case
            val sessionId = "a${index}".padEnd(64, '0')
            val proposal = Event(
                id = "b${index}".padEnd(64, '0'),
                pubKey = user,
                createdAt = 1,
                kind = DkgRitualEvents.PROPOSAL,
                tags = admins.map { arrayOf("p", it) }.toTypedArray() +
                    DkgRitualEvents.assembleTags(
                        sessionId = sessionId,
                        threshold = quorum,
                        parentChatRoomId = parentRoomId,
                        subject = "Translation",
                        walletPolicy = policy,
                    ) + arrayOf(delayTag),
                content = "",
                sig = ""
            )
            ChillDkgRitualManager.processRitualPayload(
                database = receiver,
                localChatRoom = receiving,
                innerEvent = proposal,
                userPublicKey = alice,
                nostrPrivateKey = aliceKeys.privKey!!
            )
            assertNull(receiver.dkgSessionDao().getSessionById(sessionId), "a proposal with $name was joined")
        }
    }

    @Test
    fun `only a guarded subgroup's ceremony can carry a delay, and only one in range`(): Unit = runBlocking {
        val room = parentRoom()
        listOf(SubgroupWalletPolicy.UNGUARDED to 144L, SubgroupWalletPolicy.GUARDED to 5L, SubgroupWalletPolicy.GUARDED to 65_536L)
            .forEach { (policy, delay) ->
                assertTrue(runCatching { openCeremony(room, policy, guardianDelay = delay) }.isFailure, "$policy at $delay")
            }
        assertTrue(innerEvents(parent).isEmpty(), "and nothing goes out")
    }

    @Test
    fun `a subgroup proposal from a build before policies carries none`(): Unit = runBlocking {
        val session = openCeremony(parentRoom())
        assertNull(session.walletPolicy)

        val proposal = assertNotNull(
            innerEvents(parent).firstOrNull { it.kind == DkgRitualEvents.PROPOSAL }
        )
        ChillDkgRitualManager.processRitualPayload(
            database = receiver,
            localChatRoom = receiverRoom(),
            innerEvent = proposal.asEvent(),
            userPublicKey = alice,
            nostrPrivateKey = aliceKeys.privKey!!
        )
        val joined = assertNotNull(receiver.dkgSessionDao().getSessionById(session.id))
        assertNull(joined.walletPolicy, "joined, with nothing to show at the gate")
    }

    @Test
    fun `a proposal whose wallet policy does not read is dropped`(): Unit = runBlocking {
        val proposal = Event(
            id = "cc".repeat(32),
            pubKey = user,
            createdAt = 1,
            kind = DkgRitualEvents.PROPOSAL,
            tags = admins.map { arrayOf("p", it) }.toTypedArray() +
                DkgRitualEvents.assembleTags(
                    sessionId = "ab".repeat(32),
                    threshold = quorum,
                    parentChatRoomId = parentRoomId,
                    subject = "Translation",
                ) + arrayOf(arrayOf(WalletPolicyTag.TAG_NAME, "maybe")),
            content = "",
            sig = ""
        )

        ChillDkgRitualManager.processRitualPayload(
            database = receiver,
            localChatRoom = receiverRoom(),
            innerEvent = proposal,
            userPublicKey = alice,
            nostrPrivateKey = aliceKeys.privKey!!
        )

        assertNull(
            receiver.dkgSessionDao().getSessionById("ab".repeat(32)),
            "an admin is not enrolled in a ceremony whose policy they could not be shown",
        )
    }

    @Test
    fun `only a subgroup's ceremony can carry a wallet policy`(): Unit = runBlocking {
        val refused = runCatching {
            ChillDkgRitualManager.proposeRitual(
                database = parent,
                localChatRoom = parentRoom(),
                userPublicKey = user,
                nostrPrivateKey = coordinator.privKey!!,
                threshold = quorum,
                participantPublicKeys = admins,
                walletPolicy = SubgroupWalletPolicy.UNGUARDED,
            )
        }

        assertTrue(refused.isFailure)
        assertTrue(innerEvents(parent).isEmpty(), "and nothing goes out")
    }

    /**
     * Everybody in the parent's room can read the proposal. Only the admins are in
     * it.
     *
     * This is the price of the parent's transport and the thing that makes it
     * safe: an MLS message reaches the whole tree, so the ceremony has to say who
     * it is with, and a member who is not named drops it rather than joining. A
     * member who published a host key would be in the group's permanent signing
     * quorum, so "reads the message" and "is in the ceremony" must not be the same
     * question.
     */
    @Test
    fun `a parent member who was not picked drops the proposal`(): Unit = runBlocking {
        val room = parentRoom()
        val session = openCeremony(room)

        val proposal = assertNotNull(
            innerEvents(parent).firstOrNull { it.kind == DkgRitualEvents.PROPOSAL }
        )

        receiverRoom()
        ChillDkgRitualManager.processRitualPayload(
            database = receiver,
            localChatRoom = assertNotNull(receiver.chatRoomDao().findChatRoomById(parentRoomId)),
            innerEvent = proposal.asEvent(),
            userPublicKey = malloryPublicKey,
            nostrPrivateKey = mallory.privKey!!
        )

        assertNull(
            receiver.dkgSessionDao().getSessionById(session.id),
            "a member outside the p-tags opens no session",
        )
        assertTrue(
            innerEvents(receiver).isEmpty(),
            "and publishes nothing, so their host key never joins the quorum",
        )
    }

    /**
     * A picked admin joins, on the same transport, and only when they say so.
     *
     * The approval gate is unchanged by the move and has to stay that way: a
     * relay delivering a group event to a phone in a pocket must not enrol its
     * owner in a permanent signing quorum.
     */
    @Test
    fun `a picked admin joins the ceremony, and not before approving`(): Unit = runBlocking {
        val room = parentRoom()
        openCeremony(room)

        val proposal = assertNotNull(
            innerEvents(parent).firstOrNull { it.kind == DkgRitualEvents.PROPOSAL }
        )

        val theirRoom = receiverRoom()
        ChillDkgRitualManager.processRitualPayload(
            database = receiver,
            localChatRoom = theirRoom,
            innerEvent = proposal.asEvent(),
            userPublicKey = alice,
            nostrPrivateKey = aliceKeys.privKey!!
        )

        val joined = assertNotNull(
            receiver.dkgSessionDao().getLatestSessionForChatRoom(parentRoomId),
            "a picked admin records the ceremony",
        )
        assertEquals(3, joined.participantCount)
        assertEquals(quorum, joined.threshold)
        assertEquals(parentRoomId, joined.parentChatRoomId)
        assertEquals("Translation", joined.subject)
        assertEquals(admins + user, joined.participantPublicKeySet())

        assertTrue(innerEvents(receiver).isEmpty(), "nothing goes out before they agree")
        assertEquals(
            DkgApprovalStep.HOST_KEY,
            ChillDkgRitualManager.pendingApproval(receiver, joined),
        )

        ChillDkgRitualManager.approve(
            database = receiver,
            localChatRoom = theirRoom,
            sessionId = joined.id,
            step = DkgApprovalStep.HOST_KEY,
            nostrPrivateKey = aliceKeys.privKey!!
        )

        val published = innerEvents(receiver)
        assertEquals(1, published.size, "one message, and it is an inner event")
        assertEquals(DkgRitualEvents.HOST_KEY, published.first().kind)
        assertEquals(
            admins + user - alice,
            published.first().tags.taggedUsers().map { it.pubKey }.toSet(),
            "a later message carries the roster too, minus its own sender",
        )
    }

    /**
     * A message that beat the proposal is replayed out of the store its transport
     * writes to.
     *
     * The ritual's own resume machinery is what makes it safe to run anywhere:
     * every payload is stored as it decrypts and read back once there is a session
     * to file it under. That lookup has to make the same reading of the room that
     * `broadcast` does -- a ceremony in a Marmot room has its backlog in the inner
     * events and none at all in the gift wraps, and looking in the wrong one is a
     * stall with nothing to blame it on.
     */
    @Test
    fun `a host key that arrived before the proposal is replayed`(): Unit = runBlocking {
        val room = parentRoom()
        val session = openCeremony(room)

        val proposal = assertNotNull(
            innerEvents(parent).firstOrNull { it.kind == DkgRitualEvents.PROPOSAL }
        )

        val theirRoom = receiverRoom()

        // Bob's host key, stored by the inbound path and dropped at the time for
        // want of a session to file it under.
        val early = MarmotInnerEvent(
            id = "ee".repeat(32),
            publicKey = bob,
            kind = DkgRitualEvents.HOST_KEY,
            createdAt = Instant.fromEpochSeconds(1),
            tags = DkgRitualEvents.assembleTags(sessionId = session.id),
            content = "02".repeat(33),
            chatRoomId = parentRoomId
        )
        receiver.marmotInnerEventDao().upsert(early)

        ChillDkgRitualManager.processRitualPayload(
            database = receiver,
            localChatRoom = theirRoom,
            innerEvent = proposal.asEvent(),
            userPublicKey = alice,
            nostrPrivateKey = aliceKeys.privKey!!
        )

        assertNotNull(
            receiver.dkgSessionDao().getMessage(session.id, DkgRitualEvents.HOST_KEY, bob),
            "the message that lost the race is not lost",
        )
    }

    /**
     * A Marmot room hosts its subgroups' ceremonies and never one of its own.
     *
     * Its id is derived from the key it already has, so a key of its own is one
     * the room could never answer to. This is what opened a whole-room ceremony
     * beside a subgroup's when the shared-key screen offered "Start key ceremony"
     * in the parent's room -- see docs/ceremony-creation.md.
     */
    @Test
    fun `a Marmot room refuses a ceremony of its own`(): Unit = runBlocking {
        val room = parentRoom(withOwnKey = false)

        val refused = runCatching {
            ChillDkgRitualManager.proposeRitual(
                database = parent,
                localChatRoom = room,
                userPublicKey = user,
                nostrPrivateKey = coordinator.privKey!!,
                threshold = quorum,
            )
        }

        assertTrue(refused.isFailure, "a ceremony with no parent is refused in a Marmot room")
        assertNull(
            parent.dkgSessionDao().getLatestSessionForChatRoom(parentRoomId),
            "and no session is recorded",
        )
        assertTrue(innerEvents(parent).isEmpty(), "and nothing goes out")

        // A subgroup's, in the same room, is still what the room is for.
        openCeremony(room)
        assertNotNull(parent.dkgSessionDao().getLatestSessionForChatRoom(parentRoomId))
    }

    /**
     * The receiving half: a build from before the refusal can still send one.
     */
    @Test
    fun `a proposal naming no parent in a Marmot room is dropped`(): Unit = runBlocking {
        val theirRoom = receiverRoom()

        val proposal = Event(
            id = "dd".repeat(32),
            pubKey = user,
            createdAt = 1,
            kind = DkgRitualEvents.PROPOSAL,
            // The shape `broadcast` gives a group's own proposal: the roster as
            // p-tags, a threshold, a name, and no parent.
            tags = admins.map { arrayOf("p", it) }.toTypedArray() +
                DkgRitualEvents.assembleTags(
                    sessionId = "ff".repeat(32),
                    threshold = quorum,
                    subject = "Ekklesia",
                ),
            content = "",
            sig = ""
        )

        ChillDkgRitualManager.processRitualPayload(
            database = receiver,
            localChatRoom = theirRoom,
            innerEvent = proposal,
            userPublicKey = alice,
            nostrPrivateKey = aliceKeys.privKey!!
        )

        assertNull(
            receiver.dkgSessionDao().getSessionById("ff".repeat(32)),
            "a whole-room ceremony is not recorded in a Marmot room",
        )
        assertTrue(innerEvents(receiver).isEmpty(), "and nothing is published")
    }

    /**
     * A participant can end a ceremony for everyone in it.
     *
     * There is no "no" in ChillDKG short of this, and without it a ceremony
     * opened by mistake could only be left running -- see
     * docs/ceremony-creation.md, finding F8.
     */
    @Test
    fun `a participant who abandons ends the ceremony on every device`(): Unit = runBlocking {
        val room = parentRoom()
        val session = openCeremony(room)

        val theirRoom = receiverRoom()
        val proposal = assertNotNull(
            innerEvents(parent).firstOrNull { it.kind == DkgRitualEvents.PROPOSAL }
        )
        ChillDkgRitualManager.processRitualPayload(
            database = receiver,
            localChatRoom = theirRoom,
            innerEvent = proposal.asEvent(),
            userPublicKey = alice,
            nostrPrivateKey = aliceKeys.privKey!!
        )

        assertTrue(ChillDkgRitualManager.abandon(parent, room, session.id))
        assertEquals(DkgRitualStage.FAILED, parent.dkgSessionDao().getSessionById(session.id)?.stage)

        val failure = assertNotNull(
            innerEvents(parent).firstOrNull { it.kind == DkgRitualEvents.FAILURE },
            "the other participants are told",
        )
        ChillDkgRitualManager.processRitualPayload(
            database = receiver,
            localChatRoom = theirRoom,
            innerEvent = failure.asEvent(),
            userPublicKey = alice,
            nostrPrivateKey = aliceKeys.privKey!!
        )

        assertEquals(
            DkgRitualStage.FAILED,
            receiver.dkgSessionDao().getSessionById(session.id)?.stage,
            "and it ends for them too",
        )

        assertTrue(
            !ChillDkgRitualManager.abandon(parent, room, session.id),
            "a ceremony that is over cannot be abandoned again",
        )
    }

    /**
     * Every member of the parent can read a subgroup's proposal, and so can name
     * its session. Only the people in it can end it (finding F9).
     */
    @Test
    fun `a parent member outside the subgroup cannot end its ceremony`(): Unit = runBlocking {
        val room = parentRoom()
        val session = openCeremony(room)

        val theirRoom = receiverRoom()
        val proposal = assertNotNull(
            innerEvents(parent).firstOrNull { it.kind == DkgRitualEvents.PROPOSAL }
        )
        ChillDkgRitualManager.processRitualPayload(
            database = receiver,
            localChatRoom = theirRoom,
            innerEvent = proposal.asEvent(),
            userPublicKey = alice,
            nostrPrivateKey = aliceKeys.privKey!!
        )

        ChillDkgRitualManager.processRitualPayload(
            database = receiver,
            localChatRoom = theirRoom,
            innerEvent = Event(
                id = "ab".repeat(32),
                pubKey = malloryPublicKey,
                createdAt = 2,
                kind = DkgRitualEvents.FAILURE,
                tags = DkgRitualEvents.assembleTags(sessionId = session.id),
                content = "not mine to end",
                sig = ""
            ),
            userPublicKey = alice,
            nostrPrivateKey = aliceKeys.privKey!!
        )

        val stillRunning = assertNotNull(receiver.dkgSessionDao().getSessionById(session.id))
        assertTrue(stillRunning.stage != DkgRitualStage.FAILED, "a non-participant's FAILURE is ignored")
    }

    /**
     * The parent does not start signing as its own child.
     *
     * `completedKey`'s last fallback is a ceremony held in this very room, and it
     * is load-bearing: it is what every member welcomed after the group's own
     * ceremony falls back to, since they hold no key state and no share. The
     * parent's room now holds a completed ceremony whose key belongs to the child,
     * and handing that back would have the parent author events as its subgroup,
     * silently.
     */
    @Test
    fun `a subgroup's completed ceremony is not the parent's key`(): Unit = runBlocking {
        // A member with no key state and no share of the parent's key: exactly the
        // position that reaches the fallback.
        parentRoom(withOwnKey = false)

        val childMaterial = Frost.trustedDealerKeygen(
            thresholdSecretKey = PrivateKey(
                ByteVector32("2bada550000000000000000000000000000000000000000000000000000000b2")
            ),
            nParticipants = 3,
            threshold = 2
        )

        parent.dkgSessionDao().upsert(
            DkgSession(
                id = "child-ceremony",
                chatRoomId = parentRoomId,
                coordinatorPublicKey = user,
                userPublicKey = user,
                threshold = quorum,
                participantCount = 3,
                participantPublicKeys = DkgSession.formatParticipants(admins + user),
                subject = "Translation",
                stage = DkgRitualStage.COMPLETE,
                hostPublicKey = user,
                round1Random = "aa".repeat(32),
                round2AuxRandom = "bb".repeat(32),
                thresholdPublicKey = childMaterial.thresholdPublicKey.value.toHex(),
                secretShare = "cc".repeat(32),
                parentChatRoomId = parentRoomId
            )
        )

        assertNull(
            FrostSigningManager.completedKey(parent, parentRoomId),
            "a ceremony run to make a subgroup is never the room's own key",
        )

        // And the child's own room still resolves it, by rederiving the id from
        // the key -- which is the check that actually binds a room to a key.
        assertEquals(
            "child-ceremony",
            FrostSigningManager.completedKey(
                parent,
                SharedKeyDerivation.marmotGroupId(
                    childMaterial.thresholdPublicKey.value.toHex()
                )
            )?.id,
        )
    }

    /**
     * The parent's admin room, as the device that made it holds it.
     *
     * [withOwnKey] seeds the group's own completed ceremony, which is how a real
     * admin room resolves the key it signs with. Turned off for the member
     * welcomed later, who holds neither a share nor a key state.
     */
    private suspend fun parentRoom(withOwnKey: Boolean = true): LocalChatRoom {
        (members + user).forEach { seedProfile(parent, it) }

        val metadata = MarmotGroupData(
            nostrGroupId = parentRoomId,
            name = "Ekklesia",
            description = SharedKeyDerivation.describe("Admins of Ekklesia."),
            adminPubkeys = members + user,
            relays = Relays.DefaultDMRelayList.map { it.url }
        )
        val mlsGroup = MlsGroup.create(
            KeyPair().pubKey,
            Ed25519.generateKeyPair().privateKey,
            listOf(metadata.toExtension())
        )

        parent.chatRoomDao().upsert(
            ChatRoom(
                id = parentRoomId,
                userPublicKey = user,
                subject = "Ekklesia",
                description = metadata.description,
                mlsGroupState = mlsGroup.saveState().encodeTls().toHex(),
            )
        )
        parent.participantDao().upsert(
            (members + user).map {
                Participant(participantPublicKey = it, chatRoomId = parentRoomId, relayHint = null)
            }
        )

        if (withOwnKey) {
            parent.dkgSessionDao().upsert(
                DkgSession(
                    id = "parent-ceremony",
                    // Filed under the parent's own room. In life it ran in the
                    // NIP-17 room the group's members shared, and either way it
                    // claims no parent -- which is the only thing that has to be
                    // true for it to be found as the room's own key.
                    chatRoomId = parentRoomId,
                    coordinatorPublicKey = user,
                    userPublicKey = user,
                    threshold = 3,
                    participantCount = 4,
                    stage = DkgRitualStage.COMPLETE,
                    hostPublicKey = user,
                    round1Random = "aa".repeat(32),
                    round2AuxRandom = "bb".repeat(32),
                    thresholdPublicKey = parentMaterial.thresholdPublicKey.value.toHex(),
                    secretShare = "cc".repeat(32),
                )
            )
            (members + user).forEachIndexed { index, member ->
                parent.dkgSessionDao().upsert(
                    DkgParticipantMessage(
                        sessionId = "parent-ceremony",
                        participantPublicKey = member,
                        kind = DkgRitualEvents.HOST_KEY,
                        payload = "0${index + 1}".repeat(33)
                    )
                )
            }
        }

        return assertNotNull(parent.chatRoomDao().findChatRoomById(parentRoomId))
    }

    /**
     * The same room on somebody else's device.
     *
     * Its own MLS group rather than a copy of the coordinator's: nothing here is
     * encrypted between the two, and a shared state object would make the two
     * databases one device wearing two hats.
     */
    private suspend fun receiverRoom(): LocalChatRoom {
        (members + user).forEach { seedProfile(receiver, it) }

        val metadata = MarmotGroupData(
            nostrGroupId = parentRoomId,
            name = "Ekklesia",
            description = SharedKeyDerivation.describe("Admins of Ekklesia."),
            adminPubkeys = members + user,
            relays = Relays.DefaultDMRelayList.map { it.url }
        )
        val mlsGroup = MlsGroup.create(
            KeyPair().pubKey,
            Ed25519.generateKeyPair().privateKey,
            listOf(metadata.toExtension())
        )

        receiver.chatRoomDao().upsert(
            ChatRoom(
                id = parentRoomId,
                userPublicKey = alice,
                subject = "Ekklesia",
                description = metadata.description,
                mlsGroupState = mlsGroup.saveState().encodeTls().toHex(),
            )
        )
        receiver.participantDao().upsert(
            (members + user).map {
                Participant(participantPublicKey = it, chatRoomId = parentRoomId, relayHint = null)
            }
        )

        return assertNotNull(receiver.chatRoomDao().findChatRoomById(parentRoomId))
    }

    private suspend fun seedProfile(database: DedunDatabase, publicKey: String) {
        if (database.profileDao().getProfileByPublicKey(publicKey) != null) return

        val nostrEventId = publicKey.take(63) + "f"
        database.nostrEventDao().upsert(
            NostrEvent(
                id = nostrEventId,
                pubKey = publicKey,
                kind = 0,
                tags = emptyArray(),
                content = "{}",
                sig = "0".repeat(128),
            )
        )
        database.profileDao().upsert(
            Profile(publicKey = publicKey, userName = "member", nostrEventId = nostrEventId)
        )
    }
}
