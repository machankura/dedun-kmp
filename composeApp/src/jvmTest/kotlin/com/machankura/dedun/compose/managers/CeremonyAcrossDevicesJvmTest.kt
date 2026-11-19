package com.machankura.dedun.compose.managers

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.crypto.frost.Frost
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.model.types.DkgApprovalStep
import com.machankura.dedun.compose.database.model.types.DkgRitualStage
import com.machankura.dedun.compose.extensions.toHex

/**
 * A key ceremony run from proposal to key, across devices, with real ChillDKG.
 *
 * Every other ceremony test stops at the host key, or gets its key from a trusted
 * dealer, so the rounds -- the part that makes the key -- were only ever run on a
 * phone. [CeremonyNetwork] plays the relay.
 */
class CeremonyAcrossDevicesJvmTest {

    /**
     * Today's gates, pinned: joining is not enough. Every member stops at round 1
     * and waits to be asked, and stops again at round 2.
     */
    @Test
    fun `members who have only joined are each asked to contribute`(): Unit = runBlocking {
        CeremonyNetwork.nip17(size = 3).use { network ->
            val session = network.propose(threshold = 2)
            network.deliverUntilQuiet()

            network.devices.drop(1).forEach { member ->
                assertEquals(DkgApprovalStep.HOST_KEY, network.pendingApproval(member, session.id))
                network.approve(member, session.id, DkgApprovalStep.HOST_KEY)
            }
            network.deliverUntilQuiet()

            network.devices.forEach { device ->
                assertEquals(
                    DkgApprovalStep.ROUND_1,
                    network.pendingApproval(device, session.id),
                    "$device waits to be asked to contribute",
                )
                assertEquals(DkgRitualStage.COLLECTING_HOST_KEYS, network.session(device, session.id)!!.stage)
            }
        }
    }

    /** With every approval given, every device ends with the same key. */
    @Test
    fun `a group's own ceremony makes one key across three devices`(): Unit = runBlocking {
        CeremonyNetwork.nip17(size = 3).use { network ->
            val session = network.propose(threshold = 2)
            network.deliverUntilQuiet()

            for (step in DkgApprovalStep.entries) {
                network.devices.forEach { device ->
                    if (network.pendingApproval(device, session.id) == step) {
                        network.approve(device, session.id, step)
                    }
                }
                network.deliverUntilQuiet()
            }

            assertOneKey(network, session.id, network.devices)
        }
    }

    /**
     * The same in a parent's Marmot room, for a subgroup of three of its four
     * members, with the relay reordering everything. The fourth member hears every
     * message and records nothing.
     */
    @Test
    fun `a subgroup's ceremony in its parent's room makes one key for its admins`(): Unit =
        runBlocking {
            CeremonyNetwork.marmot(size = 4, roomId = parentRoomId).use { network ->
                val picked = network.devices.take(3)
                val outsider = network.devices.last()
                val random = Random(7)

                val session = network.propose(
                    threshold = 2,
                    participants = picked.drop(1).map { it.publicKey }.toSet(),
                    parentChatRoomId = parentRoomId,
                    subject = "Translation",
                )
                network.deliverUntilQuiet(random)

                for (step in DkgApprovalStep.entries) {
                    picked.forEach { device ->
                        if (network.pendingApproval(device, session.id) == step) {
                            network.approve(device, session.id, step)
                        }
                    }
                    network.deliverUntilQuiet(random)
                }

                assertOneKey(network, session.id, picked)
                assertNull(network.session(outsider, session.id), "the member not picked records nothing")
            }
        }

    private suspend fun assertOneKey(
        network: CeremonyNetwork,
        sessionId: String,
        participants: List<CeremonyNetwork.Device>,
    ) {
        val sessions = participants.map { device ->
            assertNotNull(network.session(device, sessionId), "$device has the ceremony")
        }

        sessions.forEach { session ->
            assertEquals(DkgRitualStage.COMPLETE, session.stage, "${session.userPublicKey.take(8)}: ${session.failureReason}")
        }

        val keys = sessions.map { assertNotNull(it.thresholdPublicKey) }.toSet()
        assertEquals(1, keys.size, "every device holds the same key")

        val shares = sessions.map { assertNotNull(it.secretShare) }.toSet()
        assertEquals(participants.size, shares.size, "and a share of its own")
    }

    private companion object {
        /** A parent room's id, derived from a key the way a Marmot room's is. */
        val parentRoomId: String = SharedKeyDerivation.marmotGroupId(
            Frost.trustedDealerKeygen(
                thresholdSecretKey = PrivateKey(
                    ByteVector32("2c0ffee0000000000000000000000000000000000000000000000000000000a2")
                ),
                nParticipants = 3,
                threshold = 2
            ).thresholdPublicKey.value.toHex()
        )
    }
}
