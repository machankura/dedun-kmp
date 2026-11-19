package com.machankura.dedun.compose.managers

import androidx.room3.Room
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.DkgSession
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.GroupSignedEvent
import com.machankura.dedun.compose.database.model.types.DkgApprovalStep
import com.machankura.dedun.compose.database.model.types.DkgRitualStage
import com.machankura.dedun.compose.database.model.types.FrostSigningStage
import com.machankura.dedun.compose.managers.GroupFixtures.keyMaterial
import com.machankura.dedun.compose.managers.GroupFixtures.owner
import com.machankura.dedun.compose.managers.GroupFixtures.roomOf
import com.machankura.dedun.compose.managers.GroupFixtures.thresholdKeyOf
import com.machankura.dedun.compose.nostr.frost.GroupKeyStateEvent
import com.machankura.dedun.compose.nostr.subgroup.SubgroupBirthCertificateEvent
import com.machankura.dedun.compose.nostr.subgroup.SubgroupWalletPolicy
import com.machankura.dedun.compose.nostr.subgroup.WalletPolicyReading
import com.machankura.dedun.compose.repository.ChatRepository

/**
 * A finished ceremony taken on to a working room by the coordinator's device, with
 * nobody pressing anything -- docs/automatic-key-ceremony.md, Phase 6.
 *
 * The room itself is created through the repository, which is faked here: what
 * `MarmotGroupCreation` does with a call is its own test's business, and what this
 * covers is that the call is made, once, with the right room and the right admins,
 * and only when the group has signed what the room signs with.
 */
class CeremonyFollowThroughJvmTest {

    private val db: DedunDatabase = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())

    @AfterTest
    fun closeDb() = db.close()

    private val alice = KeyPair().pubKey.toHexKey()
    private val bob = KeyPair().pubKey.toHexKey()

    /** The room creations asked for, and what the next one answers. */
    private val created = mutableListOf<Pair<String, Set<HexKey>>>()
    private var outcome: (String) -> MarmotGroupCreation.Outcome = { MarmotGroupCreation.Outcome.Failed("unset") }

    private val chatRepository = object : ChatRepository by ChatRepository.NO_OP_CHAT_REPOSITORY {
        override suspend fun createMarmotGroup(
            ceremony: DkgSession,
            ceremonyRoom: LocalChatRoom,
            keyPair: KeyPair,
            parentChatRoomId: HexKey?,
        ): MarmotGroupCreation.Outcome {
            val groupId = SharedKeyDerivation.marmotGroupId(ceremony.thresholdPublicKey!!)
            val adminPublicKeys = ChillDkgRitualManager.participantsOf(ceremony, ceremonyRoom)
            created += groupId to adminPublicKeys
            return outcome(groupId)
        }
    }

    private val material = keyMaterial("5eed0000000000000000000000000000000000000000000000000000000000e5")
    private val ceremonyRoomId = "ce1e".repeat(16)

    /** A ceremony this device coordinated, finished, with [material]'s key. */
    private suspend fun finishedCeremony(): DkgSession {
        GroupFixtures.seedRoom(db, ceremonyRoomId, subject = "Checkers")
        val session = DkgSession(
            id = "finished-ceremony",
            chatRoomId = ceremonyRoomId,
            coordinatorPublicKey = owner,
            userPublicKey = owner,
            threshold = 2,
            participantCount = 3,
            participantPublicKeys = DkgSession.formatParticipants(setOf(owner, alice, bob)),
            subject = "Checkers",
            stage = DkgRitualStage.COMPLETE,
            hostPublicKey = owner,
            round1Random = "aa".repeat(32),
            round2AuxRandom = "bb".repeat(32),
            thresholdPublicKey = thresholdKeyOf(material),
            secretShare = "cc".repeat(32),
        )
        db.dkgSessionDao().upsert(session)
        return session
    }

    /** The group's signature on what [material]'s room signs with, on file. */
    private suspend fun signKeyState(session: DkgSession) {
        val event = GroupFixtures.signedAsRoom(
            material = material,
            kind = GroupKeyStateEvent.KIND,
            tags = GroupKeyStateEvent.assembleTags(chatRoomId = roomOf(material), dkgSessionId = session.id),
            content = thresholdKeyOf(material),
        )
        db.groupSignedEventDao().record(
            GroupSignedEvent.fromEvent(
                event = event,
                chatRoomId = ceremonyRoomId,
                derivationPath = SharedKeyDerivation.formatPath(GroupFixtures.path),
            )
        )
    }

    private suspend fun next(session: DkgSession, database: DedunDatabase = db) =
        CeremonyFollowThrough.takeNextStep(database, chatRepository, session, KeyPair())

    /**
     * A real ceremony, run across devices: the coordinator's device asks the group
     * to sign its key state as soon as it has a key, and then waits. The members'
     * devices propose nothing.
     */
    @Test
    fun `the coordinator's device asks the group to sign the key state`(): Unit = runBlocking {
        CeremonyNetwork.nip17(size = 3).use { network ->
            val session = network.propose(threshold = 2)
            network.deliverUntilQuiet()
            network.devices.drop(1).forEach { network.approve(it, session.id, DkgApprovalStep.HOST_KEY) }
            network.deliverUntilQuiet()

            val coordinator = network.coordinator
            val finished = assertNotNull(network.session(coordinator, session.id))
            assertEquals(DkgRitualStage.COMPLETE, finished.stage)

            assertEquals(CeremonyFollowThrough.Step.Proposed("key state"), next(finished, coordinator.db))
            assertEquals(CeremonyFollowThrough.Step.Waiting, next(finished, coordinator.db), "and asks once")
            assertEquals(1, keyStateSessions(coordinator.db, network.roomId, session.id).size)

            network.devices.drop(1).forEach { member ->
                val theirs = assertNotNull(network.session(member, session.id))
                assertEquals(CeremonyFollowThrough.Step.NothingToDo, next(theirs, member.db))
                assertTrue(keyStateSessions(member.db, network.roomId, session.id).isEmpty())
            }
        }
    }

    /** A refusal is the group's answer; asking again on its own would ask on a loop. */
    @Test
    fun `a key state the group did not sign is not asked for again`(): Unit = runBlocking {
        CeremonyNetwork.nip17(size = 3).use { network ->
            val session = network.propose(threshold = 2)
            network.deliverUntilQuiet()
            network.devices.drop(1).forEach { network.approve(it, session.id, DkgApprovalStep.HOST_KEY) }
            network.deliverUntilQuiet()

            val coordinator = network.coordinator
            val finished = assertNotNull(network.session(coordinator, session.id))
            next(finished, coordinator.db)

            val asked = keyStateSessions(coordinator.db, network.roomId, session.id).single()
            coordinator.db.frostSigningSessionDao().upsert(asked.copy(stage = FrostSigningStage.FAILED))

            assertIs<CeremonyFollowThrough.Step.Stuck>(next(finished, coordinator.db))
            assertEquals(1, keyStateSessions(coordinator.db, network.roomId, session.id).size)
        }
    }

    @Test
    fun `once the key state is signed the room is created with the ceremony's members`(): Unit =
        runBlocking {
            val session = finishedCeremony()
            signKeyState(session)
            outcome = { MarmotGroupCreation.Outcome.Failed("not needed") }

            next(session)

            assertEquals(listOf(roomOf(material) to setOf(owner, alice, bob)), created)
        }

    @Test
    fun `a room is not created before the group has signed`(): Unit = runBlocking {
        val session = finishedCeremony()

        // Nothing signed: it asks the group for the key state, or fails trying for
        // want of the messages a real ceremony leaves -- either way, no room.
        runCatching { next(session) }

        assertTrue(created.isEmpty())
    }

    @Test
    fun `a room blocked on key packages says on whom`(): Unit = runBlocking {
        val session = finishedCeremony()
        signKeyState(session)
        outcome = { MarmotGroupCreation.Outcome.BlockedOn(listOf(bob)) }

        assertEquals(CeremonyFollowThrough.Step.BlockedOn(listOf(bob)), next(session))
    }

    @Test
    fun `a room that exists is left alone`(): Unit = runBlocking {
        val session = finishedCeremony()
        signKeyState(session)
        GroupFixtures.seedRoom(db, roomOf(material))

        assertEquals(CeremonyFollowThrough.Step.NothingToDo, next(session))
        assertTrue(created.isEmpty())
    }

    /**
     * A subgroup's room needs its parent's certificate before its key state can
     * even be proposed, so that is asked for first -- and then the parent's
     * quorum decides, which nothing here does for them.
     */
    @Test
    fun `a subgroup asks its parent to certify it first`(): Unit = runBlocking {
        val parentMaterial = keyMaterial("9a7e0000000000000000000000000000000000000000000000000000000000f6")
        val parentRoom = GroupFixtures.signingRoom(db, parentMaterial, members = listOf(alice, bob))
        val parentRoomId = parentRoom.chatRoom.id

        val session = DkgSession(
            id = "subgroup-ceremony",
            chatRoomId = parentRoomId,
            coordinatorPublicKey = owner,
            userPublicKey = owner,
            threshold = 2,
            participantCount = 3,
            participantPublicKeys = DkgSession.formatParticipants(setOf(owner, alice, bob)),
            subject = "Translation",
            stage = DkgRitualStage.COMPLETE,
            hostPublicKey = owner,
            round1Random = "aa".repeat(32),
            round2AuxRandom = "bb".repeat(32),
            thresholdPublicKey = thresholdKeyOf(material),
            secretShare = "dd".repeat(32),
            parentChatRoomId = parentRoomId,
        )
        db.dkgSessionDao().upsert(session)

        assertEquals(CeremonyFollowThrough.Step.Proposed("birth certificate"), next(session))
        assertEquals(CeremonyFollowThrough.Step.Waiting, next(session), "and waits on the parent")

        val certificates = sessionsCarrying(db, parentRoomId, SubgroupBirthCertificateEvent.KIND)
        assertEquals(1, certificates.size)
        assertTrue(keyStateSessions(db, parentRoomId, session.id).isEmpty(), "no key state before it")
        assertTrue(created.isEmpty())
    }

    /**
     * Dedun's: the certificate the follow-through asks for carries the wallet policy
     * the subgroup's admins joined under, and the guardian keys a guarded policy needs,
     * at the delay its coordinator chose. The join screen shows both; the automatic
     * path must not drop either on the way to the parent. docs/giza-into-dedun.md,
     * Phase 5.
     */
    @Test
    fun `a guarded subgroup's certificate keeps its policy and its guardian keys`(): Unit = runBlocking {
        val parentMaterial = keyMaterial("9a7e0000000000000000000000000000000000000000000000000000000000f6")
        val parentRoom = GroupFixtures.signingRoom(db, parentMaterial, members = listOf(alice, bob))
        val parentRoomId = parentRoom.chatRoom.id

        val session = DkgSession(
            id = "guarded-subgroup-ceremony",
            chatRoomId = parentRoomId,
            coordinatorPublicKey = owner,
            userPublicKey = owner,
            threshold = 2,
            participantCount = 3,
            participantPublicKeys = DkgSession.formatParticipants(setOf(owner, alice, bob)),
            subject = "Treasury",
            stage = DkgRitualStage.COMPLETE,
            hostPublicKey = owner,
            round1Random = "aa".repeat(32),
            round2AuxRandom = "bb".repeat(32),
            thresholdPublicKey = thresholdKeyOf(material),
            secretShare = "dd".repeat(32),
            parentChatRoomId = parentRoomId,
            walletPolicy = SubgroupWalletPolicy.GUARDED.tagValue,
            guardianDelay = 144,
        )
        db.dkgSessionDao().upsert(session)

        assertEquals(CeremonyFollowThrough.Step.Proposed("birth certificate"), next(session))

        val certificate = sessionsCarrying(db, parentRoomId, SubgroupBirthCertificateEvent.KIND).single()
        val unsigned = Event.fromJsonOrNull(db.frostSigningSessionDao().getItems(certificate.id).single().unsignedEventJson)!!
        assertEquals(
            WalletPolicyReading.Decided(SubgroupWalletPolicy.GUARDED),
            SubgroupBirthCertificateEvent.parseWalletPolicy(unsigned.tags),
        )
        val guardians = SubgroupBirthCertificateEvent.parseGuardians(unsigned.tags)!!
        assertTrue(guardians.isNotEmpty(), "a guarded certificate with no guardian keys")
        assertEquals(144L, guardians.first().delay, "the parent's leaf lost the delay its coordinator chose")
    }

    @Test
    fun `an unguarded subgroup's certificate grants no guardian keys`(): Unit = runBlocking {
        val parentMaterial = keyMaterial("9a7e0000000000000000000000000000000000000000000000000000000000f6")
        val parentRoom = GroupFixtures.signingRoom(db, parentMaterial, members = listOf(alice, bob))
        val parentRoomId = parentRoom.chatRoom.id

        val session = DkgSession(
            id = "unguarded-subgroup-ceremony",
            chatRoomId = parentRoomId,
            coordinatorPublicKey = owner,
            userPublicKey = owner,
            threshold = 2,
            participantCount = 3,
            participantPublicKeys = DkgSession.formatParticipants(setOf(owner, alice, bob)),
            subject = "Petty cash",
            stage = DkgRitualStage.COMPLETE,
            hostPublicKey = owner,
            round1Random = "aa".repeat(32),
            round2AuxRandom = "bb".repeat(32),
            thresholdPublicKey = thresholdKeyOf(material),
            secretShare = "dd".repeat(32),
            parentChatRoomId = parentRoomId,
            walletPolicy = SubgroupWalletPolicy.UNGUARDED.tagValue,
        )
        db.dkgSessionDao().upsert(session)

        assertEquals(CeremonyFollowThrough.Step.Proposed("birth certificate"), next(session))

        val certificate = sessionsCarrying(db, parentRoomId, SubgroupBirthCertificateEvent.KIND).single()
        val unsigned = Event.fromJsonOrNull(db.frostSigningSessionDao().getItems(certificate.id).single().unsignedEventJson)!!
        assertEquals(
            WalletPolicyReading.Decided(SubgroupWalletPolicy.UNGUARDED),
            SubgroupBirthCertificateEvent.parseWalletPolicy(unsigned.tags),
        )
        assertEquals(emptyList(), SubgroupBirthCertificateEvent.parseGuardians(unsigned.tags))
    }

    private suspend fun keyStateSessions(database: DedunDatabase, roomId: String, dkgSessionId: String) =
        sessionsCarrying(database, roomId, GroupKeyStateEvent.KIND) {
            GroupKeyStateEvent.parseDkgSessionId(it.tags) == dkgSessionId
        }

    private suspend fun sessionsCarrying(
        database: DedunDatabase,
        roomId: String,
        kind: Int,
        about: (Event) -> Boolean = { true },
    ) = database.frostSigningSessionDao().getSessionsForChatRoom(roomId).filter { session ->
        database.frostSigningSessionDao().getItems(session.id).any { item ->
            Event.fromJsonOrNull(item.unsignedEventJson)?.let { it.kind == kind && about(it) } == true
        }
    }
}
