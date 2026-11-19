package com.machankura.dedun.compose.managers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.model.DkgParticipantMessage
import com.machankura.dedun.compose.database.model.types.DkgApprovalStep
import com.machankura.dedun.compose.database.model.types.DkgRitualStage
import com.machankura.dedun.compose.extensions.toHex
import com.machankura.dedun.compose.nostr.dkg.DkgRitualEvents

/**
 * A ceremony that holds everything it needs and will be sent nothing more moves
 * when the app starts, not never.
 *
 * Until rounds 1 and 2 stopped being asked about, a member's tap was the other
 * thing besides an arriving message that ran a ritual. The sweep is what replaces
 * it -- docs/automatic-key-ceremony.md, Phase 3.
 */
class CeremonyResumeJvmTest {

    /**
     * Where a build that still asked leaves a ceremony: every member joined, every
     * host key on every device, everybody waiting to be asked to contribute. No
     * message is outstanding, so nothing arriving will ever move it.
     */
    private suspend fun pausedByAnOlderBuild(network: CeremonyNetwork): String {
        val session = network.propose(threshold = 2)
        network.deliverUntilQuiet()

        val hostKeys = network.devices.associate { device ->
            device.publicKey to ChillDkgRitualManager.deriveHostPublicKey(device.privateKey).value.toHex()
        }

        network.devices.forEach { device ->
            val theirs = assertNotNull(network.session(device, session.id))
            device.db.dkgSessionDao().upsert(
                theirs.copy(
                    hostKeyApprovedAt = theirs.hostKeyApprovedAt ?: Clock.System.now(),
                    approvalRequestedThrough = DkgApprovalStep.ROUND_1,
                )
            )
            hostKeys.forEach { (participant, hostKey) ->
                device.db.dkgSessionDao().upsert(
                    DkgParticipantMessage(
                        sessionId = session.id,
                        participantPublicKey = participant,
                        kind = DkgRitualEvents.HOST_KEY,
                        payload = hostKey,
                    )
                )
            }
        }

        return session.id
    }

    @Test
    fun `a ceremony an older build left waiting finishes from the sweep alone`(): Unit = runBlocking {
        CeremonyNetwork.nip17(size = 3).use { network ->
            val sessionId = pausedByAnOlderBuild(network)

            assertEquals(0, network.deliver(), "nothing is on its way to anybody")
            network.devices.forEach { device ->
                assertEquals(DkgRitualStage.COLLECTING_HOST_KEYS, network.session(device, sessionId)!!.stage)
            }

            network.devices.forEach { device ->
                assertEquals(1, network.resume(device), "$device has one ceremony to resume")
            }
            network.deliverUntilQuiet()

            val keys = network.devices.map { device ->
                val theirs = assertNotNull(network.session(device, sessionId))
                assertEquals(DkgRitualStage.COMPLETE, theirs.stage, "$device: ${theirs.failureReason}")
                assertNotNull(theirs.thresholdPublicKey)
            }
            assertEquals(1, keys.toSet().size)

            network.devices.forEach { device ->
                assertEquals(0, network.resume(device), "a finished ceremony is not resumed")
            }
        }
    }

    @Test
    fun `sweeping twice publishes nothing twice`(): Unit = runBlocking {
        CeremonyNetwork.nip17(size = 3).use { network ->
            val sessionId = pausedByAnOlderBuild(network)

            network.devices.forEach { network.resume(it) }
            network.devices.forEach { network.resume(it) }

            network.devices.forEach { device ->
                assertEquals(
                    1,
                    network.sent(device, DkgRitualEvents.ROUND_1).size,
                    "$device contributed once",
                )
            }
            assertTrue(network.session(network.coordinator, sessionId) != null)
        }
    }

    /** Nothing goes out for a member who has not agreed to take part. */
    @Test
    fun `a ceremony nobody here joined is left alone`(): Unit = runBlocking {
        CeremonyNetwork.nip17(size = 3).use { network ->
            val member = network.devices[1]
            val session = network.propose(threshold = 2)
            network.deliverUntilQuiet()

            assertEquals(0, network.resume(member))
            assertTrue(network.sent(member, DkgRitualEvents.HOST_KEY).isEmpty())
            assertEquals(DkgApprovalStep.HOST_KEY, network.pendingApproval(member, session.id))
        }
    }

    @Test
    fun `an abandoned ceremony is not resumed`(): Unit = runBlocking {
        CeremonyNetwork.nip17(size = 3).use { network ->
            val session = network.propose(threshold = 2)
            network.deliverUntilQuiet()

            ChillDkgRitualManager.abandon(
                database = network.coordinator.db,
                localChatRoom = network.room(network.coordinator),
                sessionId = session.id,
            )

            assertEquals(0, network.resume(network.coordinator))
        }
    }
}
