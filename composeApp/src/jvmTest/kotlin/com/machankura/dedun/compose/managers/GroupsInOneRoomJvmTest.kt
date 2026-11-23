package com.machankura.dedun.compose.managers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.DkgSession
import com.machankura.dedun.compose.database.model.types.DkgApprovalStep
import com.machankura.dedun.compose.database.model.types.DkgRitualStage
import com.machankura.dedun.compose.database.repository.DatabaseChatRepository
import com.machankura.dedun.compose.repository.AdminRoom
import com.machankura.dedun.compose.repository.GroupMadeHere
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * What a NIP-17 room answers once the same people have made more than one group in
 * it: every group's admin room, and no single key of its own.
 * docs/nip17-multiple-ceremonies.md, Phase 3.
 *
 * Two real ceremonies, run to the end on [CeremonyNetwork], so the keys and shares
 * are the ones a device would hold.
 */
class GroupsInOneRoomJvmTest {

    private suspend fun completed(network: CeremonyNetwork, subject: String?, threshold: Int = 2): DkgSession {
        val session = network.propose(threshold = threshold, subject = subject)
        network.deliverUntilQuiet()
        network.devices.forEach { device ->
            if (network.pendingApproval(device, session.id) == DkgApprovalStep.HOST_KEY) {
                network.approve(device, session.id, DkgApprovalStep.HOST_KEY)
            }
        }
        network.deliverUntilQuiet()
        return assertNotNull(network.session(network.coordinator, session.id)).also {
            assertEquals(DkgRitualStage.COMPLETE, it.stage, it.failureReason)
        }
    }

    /** The room [ceremony]'s key makes, as `MarmotGroupCreation` would have filed it. */
    private suspend fun standUpRoom(network: CeremonyNetwork, ceremony: DkgSession, name: String): String {
        val roomId = SharedKeyDerivation.marmotGroupId(assertNotNull(ceremony.thresholdPublicKey))
        network.coordinator.db.chatRoomDao().upsert(
            ChatRoom(
                id = roomId,
                userPublicKey = network.coordinator.publicKey,
                subject = name,
                description = null,
                mlsGroupState = "00",
            )
        )
        return roomId
    }

    @Test
    fun `a room's members are admins of every group they made in it`(): Unit = runBlocking {
        CeremonyNetwork.nip17(size = 3, subject = "Ekklesia").use { network ->
            val repository = DatabaseChatRepository(network.coordinator.db, CoroutineScope(Dispatchers.Default))

            val ekklesia = completed(network, subject = null)
            assertEquals(emptyList(), repository.adminRoomsOf(network.roomId), "no room made yet, none to offer")

            val ekklesiaRoom = standUpRoom(network, ekklesia, "&Ekklesia")
            assertEquals(listOf(AdminRoom("&Ekklesia", ekklesiaRoom)), repository.adminRoomsOf(network.roomId))

            // A second group, by the same people, in the same room. While its ceremony
            // runs it has no room to offer; the first group's is still there.
            val agora = network.propose(threshold = 3, subject = "Agora")
            assertEquals(listOf(AdminRoom("&Ekklesia", ekklesiaRoom)), repository.adminRoomsOf(network.roomId))

            network.deliverUntilQuiet()
            network.devices.forEach { device ->
                if (network.pendingApproval(device, agora.id) == DkgApprovalStep.HOST_KEY) {
                    network.approve(device, agora.id, DkgApprovalStep.HOST_KEY)
                }
            }
            network.deliverUntilQuiet()
            val agoraDone = assertNotNull(network.session(network.coordinator, agora.id))
            assertEquals(DkgRitualStage.COMPLETE, agoraDone.stage, agoraDone.failureReason)

            val agoraRoom = standUpRoom(network, agoraDone, "&Agora")
            assertEquals(
                setOf(AdminRoom("&Ekklesia", ekklesiaRoom), AdminRoom("&Agora", agoraRoom)),
                repository.adminRoomsOf(network.roomId).toSet(),
                "both groups, not the newest alone",
            )
        }
    }

    /** The room's details list each group, from its ceremony to its room. */
    @Test
    fun `each group the room's members made is listed with where it stands`(): Unit = runBlocking {
        CeremonyNetwork.nip17(size = 3, subject = "Ekklesia").use { network ->
            val repository = DatabaseChatRepository(network.coordinator.db, CoroutineScope(Dispatchers.Default))

            val ekklesia = completed(network, subject = null)
            val agora = network.propose(threshold = 3, subject = "Agora")

            assertEquals(
                setOf(
                    GroupMadeHere(ekklesia.id, "Ekklesia", GroupMadeHere.Status.WaitingForRoom),
                    GroupMadeHere(agora.id, "Agora", GroupMadeHere.Status.KeyCeremonyRunning),
                ),
                repository.groupsMadeIn(network.roomId).toSet(),
            )

            val ekklesiaRoom = standUpRoom(network, ekklesia, "&Ekklesia")
            assertEquals(
                GroupMadeHere.Status.Ready(ekklesiaRoom),
                repository.groupsMadeIn(network.roomId).single { it.dkgSessionId == ekklesia.id }.status,
            )

            // A failed one is gone from the list; it is there to be tried again.
            network.coordinator.db.dkgSessionDao().upsert(
                assertNotNull(network.session(network.coordinator, agora.id)).copy(stage = DkgRitualStage.FAILED)
            )
            assertEquals(listOf(ekklesia.id), repository.groupsMadeIn(network.roomId).map { it.dkgSessionId })
        }
    }

    /**
     * "The key this room signs with" has no answer in a NIP-17 room holding two, and
     * guessing the newest would sign as a group nobody named. Nothing asks it of a
     * NIP-17 room today; this is what keeps a future caller from being handed one.
     */
    @Test
    fun `a room holding two keys of its own has no one key to sign with`(): Unit = runBlocking {
        CeremonyNetwork.nip17(size = 3, subject = "Ekklesia").use { network ->
            val db = network.coordinator.db

            val ekklesia = completed(network, subject = null)
            assertEquals(ekklesia.id, FrostSigningManager.completedKey(db, network.roomId)?.id, "one key is the answer")

            completed(network, subject = "Agora", threshold = 3)
            assertNull(FrostSigningManager.completedKey(db, network.roomId), "two keys are not one")
        }
    }
}
