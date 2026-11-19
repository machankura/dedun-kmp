package com.machankura.dedun.compose.managers

import com.vitorpamplona.quartz.nip01Core.core.Event
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.crypto.frost.Frost
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.model.types.DkgApprovalStep
import com.machankura.dedun.compose.database.model.types.DkgRitualStage
import com.machankura.dedun.compose.extensions.toHex
import com.machankura.dedun.compose.nostr.dkg.DkgRitualEvents

/**
 * A key ceremony run from proposal to key, across devices, with real ChillDKG.
 *
 * Every other ceremony test stops at the host key, or gets its key from a trusted
 * dealer, so the rounds -- the part that makes the key -- were only ever run on a
 * phone. [CeremonyNetwork] plays the relay.
 *
 * Joining is the only thing a member is asked (docs/automatic-key-ceremony.md), so
 * every ceremony here is driven by joins and deliveries and nothing else.
 */
class CeremonyAcrossDevicesJvmTest {

    /** The whole change: after the joins, nobody is asked anything and the key exists. */
    @Test
    fun `members who have only joined finish the ceremony`(): Unit = runBlocking {
        CeremonyNetwork.nip17(size = 3).use { network ->
            val session = network.propose(threshold = 2)
            network.deliverUntilQuiet()

            joinAll(network, session.id, network.devices)
            network.deliverUntilQuiet()

            network.devices.forEach { device ->
                assertNull(network.pendingApproval(device, session.id), "$device is asked nothing more")
            }
            assertOneKey(network, session.id, network.devices)
        }
    }

    /** Opening a ceremony is joining it, and the coordinator is never asked a thing. */
    @Test
    fun `the coordinator is never asked`(): Unit = runBlocking {
        CeremonyNetwork.nip17(size = 3).use { network ->
            val session = network.propose(threshold = 2)
            assertNull(network.pendingApproval(network.coordinator, session.id))

            network.deliverUntilQuiet()
            assertNull(network.pendingApproval(network.coordinator, session.id))

            joinAll(network, session.id, network.devices)
            network.deliverUntilQuiet()

            assertEquals(DkgRitualStage.COMPLETE, network.session(network.coordinator, session.id)!!.stage)
        }
    }

    /**
     * The last member to join already holds everyone else's host key, so the one
     * approval carries them straight on into contributing.
     */
    @Test
    fun `the last member to join contributes in the same approval`(): Unit = runBlocking {
        CeremonyNetwork.nip17(size = 3).use { network ->
            val (_, first, last) = network.devices
            val session = network.propose(threshold = 2)
            network.deliverUntilQuiet()

            network.approve(first, session.id, DkgApprovalStep.HOST_KEY)
            network.deliverUntilQuiet()

            assertTrue(network.sent(last, DkgRitualEvents.HOST_KEY).isEmpty())
            network.approve(last, session.id, DkgApprovalStep.HOST_KEY)

            assertEquals(1, network.sent(last, DkgRitualEvents.HOST_KEY).size)
            assertEquals(
                1,
                network.sent(last, DkgRitualEvents.ROUND_1).size,
                "joining last puts the contribution out with the host key",
            )
        }
    }

    /**
     * What the round-2 approval was said to protect against, and the reason it was
     * never a person's to give: a combined result that is not the one every member
     * contributed to never becomes anybody's key. ChillDKG catches it -- a member
     * whose share it breaks refuses at round 2, and otherwise the confirmations
     * disagree and no certificate can be made over them -- and the ceremony ends
     * for everyone with nobody asked anything.
     *
     * Here the change is in transit, so the coordinator is honest and is the one
     * that finds the confirmations disagree and says so.
     */
    @Test
    fun `a combined result that was tampered with becomes nobody's key`(): Unit =
        runBlocking {
            CeremonyNetwork.nip17(size = 3).use { network ->
                network.tamper = { sender, _, event ->
                    if (sender === network.coordinator && event.kind == DkgRitualEvents.COORDINATOR_ROUND_1) {
                        Event(
                            id = event.id,
                            pubKey = event.pubKey,
                            createdAt = event.createdAt,
                            kind = event.kind,
                            tags = event.tags,
                            content = flipLastByte(event.content),
                            sig = "",
                        )
                    } else {
                        event
                    }
                }

                val session = network.propose(threshold = 2)
                network.deliverUntilQuiet()
                joinAll(network, session.id, network.devices)
                network.deliverUntilQuiet()

                network.devices.forEach { device ->
                    val theirs = assertNotNull(network.session(device, session.id))
                    assertEquals(DkgRitualStage.FAILED, theirs.stage, "$device: the ceremony ended")
                    assertNull(theirs.thresholdPublicKey, "$device holds no key")
                    assertNull(theirs.secretShare, "$device keeps no share of one")
                    assertNull(network.pendingApproval(device, session.id), "$device was asked nothing")
                }

                // Which device notices depends on which bytes were changed. That
                // somebody did, out loud, is what lets the rest stop waiting.
                assertTrue(
                    network.devices.any { network.sent(it, DkgRitualEvents.FAILURE).isNotEmpty() },
                    "somebody told the group",
                )
            }
        }

    /**
     * A message is dated by when it first arrived. The ritual screen dates a stall
     * from the newest of them, and a relay repeating itself is not the ceremony
     * moving.
     */
    @Test
    fun `a redelivered message keeps the time it first arrived`(): Unit = runBlocking {
        CeremonyNetwork.nip17(size = 3).use { network ->
            val member = network.devices[1]
            val session = network.propose(threshold = 2)
            network.deliverUntilQuiet()

            fun stored() = runBlocking {
                assertNotNull(
                    member.db.dkgSessionDao()
                        .getMessage(session.id, DkgRitualEvents.HOST_KEY, network.coordinator.publicKey)
                )
            }
            val first = stored().createdAt

            kotlinx.coroutines.delay(20)
            ChillDkgRitualManager.processRitualPayload(
                database = member.db,
                localChatRoom = network.room(member),
                innerEvent = network.sent(network.coordinator, DkgRitualEvents.HOST_KEY).single(),
                userPublicKey = member.publicKey,
                nostrPrivateKey = member.privateKey,
            )

            assertEquals(first, stored().createdAt)
        }
    }

    /** A round approval from a screen left open across the upgrade publishes nothing. */
    @Test
    fun `approving a round does nothing`(): Unit = runBlocking {
        CeremonyNetwork.nip17(size = 3).use { network ->
            val member = network.devices[1]
            val session = network.propose(threshold = 2)
            network.deliverUntilQuiet()

            network.approve(member, session.id, DkgApprovalStep.ROUND_1)
            network.approve(member, session.id, DkgApprovalStep.ROUND_2)

            assertTrue(network.sent(member, DkgRitualEvents.HOST_KEY).isEmpty(), "it has not joined")
            assertTrue(network.sent(member, DkgRitualEvents.ROUND_1).isEmpty())
            assertEquals(DkgApprovalStep.HOST_KEY, network.pendingApproval(member, session.id))
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

                joinAll(network, session.id, picked)
                network.deliverUntilQuiet(random)

                assertOneKey(network, session.id, picked)
                assertNull(network.session(outsider, session.id), "the member not picked records nothing")
            }
        }

    private suspend fun joinAll(
        network: CeremonyNetwork,
        sessionId: String,
        devices: List<CeremonyNetwork.Device>,
    ) = devices.forEach { device ->
        if (network.pendingApproval(device, sessionId) == DkgApprovalStep.HOST_KEY) {
            network.approve(device, sessionId, DkgApprovalStep.HOST_KEY)
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

        /** The same bytes with the last one changed: well-formed, and wrong. */
        fun flipLastByte(hex: String): String {
            val last = hex.takeLast(2).toInt(16) xor 0x01
            return hex.dropLast(2) + last.toString(16).padStart(2, '0')
        }
    }
}
