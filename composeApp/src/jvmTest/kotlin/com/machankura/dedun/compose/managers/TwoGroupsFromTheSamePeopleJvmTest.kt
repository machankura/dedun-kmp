package com.machankura.dedun.compose.managers

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.DkgSession
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.types.DkgApprovalStep
import com.machankura.dedun.compose.database.model.types.DkgRitualStage
import com.machankura.dedun.compose.nostr.frost.GroupKeyStateEvent
import com.machankura.dedun.compose.repository.ChatRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The same three people make two groups, end to end, with real ChillDKG and real
 * FROST: two ceremonies in their one NIP-17 room, two key states signed there, and
 * two rooms asked for. docs/nip17-multiple-ceremonies.md, Phase 6.
 *
 * The rooms themselves are `MarmotGroupCreation`'s to test; here the coordinator's
 * request to create each one is recorded, with the name and admins it carries.
 */
class TwoGroupsFromTheSamePeopleJvmTest {

    /** The rooms the coordinator's device went on to create: id, name, admins. */
    private val created = mutableListOf<Triple<String, String, Set<HexKey>>>()

    private val chatRepository = object : ChatRepository by ChatRepository.NO_OP_CHAT_REPOSITORY {
        override suspend fun createMarmotGroup(
            ceremony: DkgSession,
            ceremonyRoom: LocalChatRoom,
            keyPair: KeyPair,
            parentChatRoomId: HexKey?,
        ): MarmotGroupCreation.Outcome {
            created += Triple(
                SharedKeyDerivation.marmotGroupId(ceremony.thresholdPublicKey!!),
                CeremonyFollowThrough.roomName(ceremony),
                ChillDkgRitualManager.participantsOf(ceremony, ceremonyRoom),
            )
            return MarmotGroupCreation.Outcome.Failed("the room is MarmotGroupCreation's to test")
        }
    }

    private suspend fun joinAll(network: CeremonyNetwork, sessionId: String) =
        network.devices.forEach { device ->
            if (network.pendingApproval(device, sessionId) == DkgApprovalStep.HOST_KEY) {
                network.approve(device, sessionId, DkgApprovalStep.HOST_KEY)
            }
        }

    /** The coordinator's follow-through for [sessionId], to the point it waits on nobody. */
    private suspend fun followThrough(network: CeremonyNetwork, sessionId: String) =
        CeremonyFollowThrough.followThrough(
            network.coordinator.db,
            chatRepository,
            assertNotNull(network.session(network.coordinator, sessionId)),
            network.coordinator.keys,
        )

    /** Every device holds [sessionId]'s one key; returns it. */
    private suspend fun oneKey(network: CeremonyNetwork, sessionId: String): String {
        val sessions = network.devices.map { assertNotNull(network.session(it, sessionId), "$it has $sessionId") }
        sessions.forEach { assertEquals(DkgRitualStage.COMPLETE, it.stage, it.failureReason) }
        return sessions.map { assertNotNull(it.thresholdPublicKey) }.toSet().single()
    }

    /** Asserts every device holds a signed key state for [key]'s room naming [sessionId]; returns the room. */
    private suspend fun assertKeyStateSigned(network: CeremonyNetwork, sessionId: String, key: String): String {
        val roomId = SharedKeyDerivation.marmotGroupId(key)
        network.devices.forEach { device ->
            val state = assertNotNull(
                GroupKeyStateManager.stateAmong(
                    device.db.groupSignedEventDao().getByKind(GroupKeyStateEvent.KIND),
                    roomId,
                ),
                "$device holds $roomId's signed key state",
            )
            assertEquals(sessionId, state.dkgSessionId, "$device: $roomId's state names its own ceremony")
            assertEquals(key, state.thresholdPublicKey)
        }
        return roomId
    }

    @Test
    fun `the same people make a second group after the first, and both stand`(): Unit = runBlocking {
        CeremonyNetwork.nip17(size = 3, subject = "Ekklesia").use { network ->
            // Ekklesia, the room's first group, named for the room.
            val ekklesia = network.propose(threshold = 2)
            network.deliverUntilQuiet()
            joinAll(network, ekklesia.id)
            network.deliverUntilQuiet()
            val ekklesiaKey = oneKey(network, ekklesia.id)
            followThrough(network, ekklesia.id)
            network.deliverUntilQuiet()
            followThrough(network, ekklesia.id)
            val ekklesiaRoom = assertKeyStateSigned(network, ekklesia.id, ekklesiaKey)

            // Agora, a week later, by the same people, at a different quorum.
            val agora = network.propose(threshold = 3, subject = "Agora")
            assertNotEquals(ekklesia.id, agora.id, "a second group, not the first handed back")
            network.deliverUntilQuiet()

            network.devices.drop(1).forEach { member ->
                assertEquals(DkgApprovalStep.HOST_KEY, network.pendingApproval(member, agora.id))
                val ask = member.db.chatMessageDao().getChatMessagesByChatRoomId(network.roomId)
                    .map { it.chatMessage }
                    .single {
                        it.messageType == ChatMessage.TYPE_DKG_APPROVAL_NEEDED_HOST_KEY && it.dkgSessionId == agora.id
                    }
                assertTrue("Agora" in ask.content, "$member is asked to join Agora by name: ${ask.content}")
            }

            joinAll(network, agora.id)
            network.deliverUntilQuiet()
            val agoraKey = oneKey(network, agora.id)
            followThrough(network, agora.id)
            network.deliverUntilQuiet()
            followThrough(network, agora.id)
            val agoraRoom = assertKeyStateSigned(network, agora.id, agoraKey)

            assertNotEquals(ekklesiaKey, agoraKey)
            assertNotEquals(ekklesiaRoom, agoraRoom)

            val everyone = network.devices.map { it.publicKey }.toSet()
            assertEquals(
                setOf(
                    Triple(ekklesiaRoom, "Ekklesia", everyone),
                    Triple(agoraRoom, "Agora", everyone),
                ),
                created.toSet(),
                "two rooms, each named for its group, each with all three as admins",
            )

            network.devices.forEach { device ->
                assertEquals(
                    setOf(ekklesia.id, agora.id),
                    device.db.dkgSessionDao().getOwnSessionsForChatRoom(network.roomId).map { it.id }.toSet(),
                )
                assertEquals(3, network.session(device, agora.id)?.threshold)
                assertEquals("Ekklesia", network.room(device).chatRoom.subject, "$device: the room keeps its name")

                // Each room signs with its own group's key, and the NIP-17 room with
                // neither: it holds two.
                assertEquals(ekklesia.id, FrostSigningManager.completedKey(device.db, ekklesiaRoom)?.id)
                assertEquals(agora.id, FrostSigningManager.completedKey(device.db, agoraRoom)?.id)
                assertNull(FrostSigningManager.completedKey(device.db, network.roomId))
            }
        }
    }

    /** Agora opened while Ekklesia is still collecting host keys, with the relay reordering everything. */
    @Test
    fun `two groups made at once both stand`(): Unit = runBlocking {
        CeremonyNetwork.nip17(size = 3, subject = "Ekklesia").use { network ->
            val random = Random(17)
            val ekklesia = network.propose(threshold = 2)
            val agora = network.propose(threshold = 3, subject = "Agora")
            assertNotEquals(ekklesia.id, agora.id)

            network.deliverUntilQuiet(random)
            joinAll(network, agora.id)
            joinAll(network, ekklesia.id)
            network.deliverUntilQuiet(random)

            val ekklesiaKey = oneKey(network, ekklesia.id)
            val agoraKey = oneKey(network, agora.id)
            assertNotEquals(ekklesiaKey, agoraKey)

            // Both key states asked for in the one room at once.
            followThrough(network, ekklesia.id)
            followThrough(network, agora.id)
            network.deliverUntilQuiet(random)
            assertKeyStateSigned(network, ekklesia.id, ekklesiaKey)
            assertKeyStateSigned(network, agora.id, agoraKey)
        }
    }

    /** Abandoning one group's ceremony ends that one and touches nothing of the other's. */
    @Test
    fun `abandoning one group leaves the other to finish`(): Unit = runBlocking {
        CeremonyNetwork.nip17(size = 3, subject = "Ekklesia").use { network ->
            val ekklesia = network.propose(threshold = 2)
            val agora = network.propose(threshold = 3, subject = "Agora")
            network.deliverUntilQuiet()

            assertTrue(ChillDkgRitualManager.abandon(network.coordinator.db, network.room(network.coordinator), agora.id))
            joinAll(network, ekklesia.id)
            joinAll(network, agora.id)
            network.deliverUntilQuiet()

            oneKey(network, ekklesia.id)
            network.devices.forEach { device ->
                val theirs = assertNotNull(network.session(device, agora.id))
                assertEquals(DkgRitualStage.FAILED, theirs.stage, "$device: Agora ended")
                assertNull(theirs.thresholdPublicKey)
            }
        }
    }
}
