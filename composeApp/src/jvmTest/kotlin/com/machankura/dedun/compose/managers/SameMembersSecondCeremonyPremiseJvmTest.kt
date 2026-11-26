package com.machankura.dedun.compose.managers

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip59Giftwrap.seals.SealEvent
import com.vitorpamplona.quartz.nip59Giftwrap.wraps.GiftWrapEvent
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.types.DkgApprovalStep
import com.machankura.dedun.compose.database.model.types.DkgRitualStage
import com.machankura.dedun.compose.nostr.dkg.DkgRitualEvents
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What has to already be true before the same people can make a second group.
 * docs/nip17-multiple-ceremonies.md, Phase 0.
 *
 * Each test pins one premise the later phases build on, against the code as it was
 * before any of them. A premise that fails here changes the plan, not the test.
 *
 * Today a coordinator cannot open a second ceremony in a room that holds one, so
 * these reach a second ceremony the two ways the code allows: two members opening
 * one each before either hears of the other, and -- for the receiving side alone --
 * a coordinator whose first ceremony is set aside on its own device for the moment
 * it takes to propose.
 */
class SameMembersSecondCeremonyPremiseJvmTest {

    /**
     * P1. ChillDKG has no session id of its own: its parameters are the sorted host
     * keys and the threshold, and a member brings the same host key to every
     * ceremony. Two ceremonies over the same people at the same threshold are the
     * same parameters, and only the fresh randomness each ceremony mints keeps them
     * apart. They have to come out as two keys.
     */
    @Test
    fun `two ceremonies over the same host keys and threshold make two keys`(): Unit = runBlocking {
        CeremonyNetwork.nip17(size = 3, subject = "Ekklesia").use { network ->
            val (alice, bob, _) = network.devices

            // Opened before either hears of the other, so neither folds into the
            // other: the only way two exist in one room on today's code.
            val ekklesia = network.propose(threshold = 2, from = alice)
            val agora = network.propose(threshold = 2, from = bob)
            assertNotEquals(ekklesia.id, agora.id)

            network.deliverUntilQuiet()
            joinAll(network, ekklesia.id)
            joinAll(network, agora.id)
            network.deliverUntilQuiet()

            val ekklesiaKey = assertOneKey(network, ekklesia.id)
            val agoraKey = assertOneKey(network, agora.id)
            assertNotEquals(ekklesiaKey, agoraKey, "the same parameters, and still two keys")

            network.devices.forEach { device ->
                assertEquals(
                    2,
                    device.db.dkgSessionDao().getKeyHoldingSessions().size,
                    "$device holds a share of each",
                )
            }
        }
    }

    /**
     * P1, the other half. Nothing ChillDKG checks tells the two ceremonies apart --
     * only the `dkg_session` tag does -- so a message of one handed to the other has
     * to make that one fail, never complete. Carol's contribution to Ekklesia goes
     * out labelled as her contribution to Agora.
     */
    @Test
    fun `a contribution to one ceremony fed to the other makes nobody a key`(): Unit = runBlocking {
        CeremonyNetwork.nip17(size = 3, subject = "Ekklesia").use { network ->
            val (alice, bob, carol) = network.devices

            val ekklesia = network.propose(threshold = 2, from = alice)
            val agora = network.propose(threshold = 2, from = bob)
            network.deliverUntilQuiet()

            // Ekklesia first, to the end, so Carol's contribution to it exists.
            joinAll(network, ekklesia.id)
            network.deliverUntilQuiet()
            val ekklesiaKey = assertOneKey(network, ekklesia.id)

            val carolsEkklesiaContribution = network.sent(carol, DkgRitualEvents.ROUND_1)
                .single { DkgRitualEvents.parseSessionId(it.tags) == ekklesia.id }
                .content

            network.tamper = { sender, _, event ->
                if (sender === carol &&
                    event.kind == DkgRitualEvents.ROUND_1 &&
                    DkgRitualEvents.parseSessionId(event.tags) == agora.id
                ) {
                    Event(event.id, event.pubKey, event.createdAt, event.kind, event.tags, carolsEkklesiaContribution, "")
                } else {
                    event
                }
            }

            joinAll(network, agora.id)
            network.deliverUntilQuiet()

            network.devices.forEach { device ->
                val theirs = assertNotNull(network.session(device, agora.id))
                assertNotEquals(DkgRitualStage.COMPLETE, theirs.stage, "$device: Agora must not complete")
                assertNull(theirs.thresholdPublicKey, "$device holds no Agora key")
                assertNull(theirs.secretShare, "$device keeps no share of one")
            }

            // And Ekklesia is untouched by any of it.
            network.devices.forEach { device ->
                val theirs = assertNotNull(network.session(device, ekklesia.id))
                assertEquals(DkgRitualStage.COMPLETE, theirs.stage)
                assertEquals(ekklesiaKey, theirs.thresholdPublicKey)
            }
        }
    }

    /**
     * P2. The receiving side already takes a second ceremony in a room whose first
     * one finished: `acceptProposal` returns early for a session it holds and for
     * nothing else. The member is asked to join, and the line asking says which
     * ceremony it is about.
     */
    @Test
    fun `a member holding a finished key still records a second proposal`(): Unit = runBlocking {
        CeremonyNetwork.nip17(size = 3, subject = "Ekklesia").use { network ->
            val (alice, bob, carol) = network.devices

            val ekklesia = network.propose(threshold = 2, from = alice)
            network.deliverUntilQuiet()
            joinAll(network, ekklesia.id)
            network.deliverUntilQuiet()
            assertOneKey(network, ekklesia.id)

            val agora = proposeAgain(network, ekklesia.id, subject = "Agora", threshold = 3)
            network.deliverUntilQuiet()

            listOf(bob, carol).forEach { member ->
                val theirs = assertNotNull(network.session(member, agora.id), "$member recorded Agora")
                assertEquals(3, theirs.threshold)
                assertEquals("Agora", theirs.subject)
                assertEquals(DkgApprovalStep.HOST_KEY, network.pendingApproval(member, agora.id))

                val asks = member.db.chatMessageDao().getChatMessagesByChatRoomId(network.roomId)
                    .map { it.chatMessage }
                    .filter { it.messageType == ChatMessage.TYPE_DKG_APPROVAL_NEEDED_HOST_KEY }
                assertTrue(asks.any { it.dkgSessionId == agora.id }, "$member is asked about Agora")

                assertEquals(
                    DkgRitualStage.COMPLETE,
                    network.session(member, ekklesia.id)?.stage,
                    "$member still holds Ekklesia",
                )
            }
        }
    }

    /**
     * P3. A ceremony's proposal carries the group's name in the same `subject` tag
     * NIP-14 uses, and a kind-14 message carrying one renames a NIP-17 room on every
     * client. A proposal is not kind 14: it names a room the device has never seen and
     * leaves one it has alone. If that changed, every second group would rename the
     * room on its members' devices and not on its coordinator's.
     *
     * Through the real inbound path, sealed and wrapped, because that is where the
     * rename lives.
     */
    @Test
    fun `a proposal's name does not rename a room that already exists`(): Unit = runBlocking {
        CeremonyNetwork.nip17(size = 3, subject = "Ekklesia").use { network ->
            val (alice, bob, _) = network.devices

            val agora = network.propose(threshold = 2, from = alice, subject = "Agora")
            val proposal = network.sent(alice, DkgRitualEvents.PROPOSAL)
                .single { DkgRitualEvents.parseSessionId(it.tags) == agora.id }
            assertEquals("Agora", DkgRitualEvents.parseSubject(proposal.tags))

            val sync = NostrSignerSync(alice.keys)
            val seal = sync.signNormal<SealEvent>(
                createdAt = proposal.createdAt,
                kind = SealEvent.KIND,
                tags = emptyArray(),
                content = sync.nip44Encrypt(proposal.toJson(), bob.publicKey),
            )
            val wrap = GiftWrapEvent.create(event = seal, recipientPubKey = bob.publicKey, createdAt = proposal.createdAt)
            MarmotStandIn.storeInApp(bob.db, bob.keys, wrap)

            assertNotNull(
                bob.db.giftWrapPayloadDao().getByChatRoomAndKinds(network.roomId, listOf(DkgRitualEvents.PROPOSAL))
                    .singleOrNull { DkgRitualEvents.parseSessionId(it.tags) == agora.id },
                "the proposal reached Bob's room through the inbound path",
            )
            assertEquals("Agora", network.session(bob, agora.id)?.subject, "and Bob recorded the ceremony it opens")
            assertEquals("Ekklesia", network.room(bob).chatRoom.subject, "Bob's room keeps its name")
        }
    }

    /**
     * Opens a second ceremony from the coordinator of [firstSessionId], which today's
     * `proposeRitual` would fold into the first. The first is set aside on the
     * coordinator's own device for the call and put back after, so what the others
     * receive is exactly a second proposal into a room whose first ceremony finished.
     */
    private suspend fun proposeAgain(
        network: CeremonyNetwork,
        firstSessionId: String,
        subject: String,
        threshold: Int,
    ) = network.coordinator.db.dkgSessionDao().let { dao ->
        val first = assertNotNull(dao.getSessionById(firstSessionId))
        dao.upsert(first.copy(stage = DkgRitualStage.FAILED))
        try {
            network.propose(threshold = threshold, subject = subject)
        } finally {
            dao.upsert(first)
        }
    }

    private suspend fun joinAll(network: CeremonyNetwork, sessionId: String) =
        network.devices.forEach { device ->
            if (network.pendingApproval(device, sessionId) == DkgApprovalStep.HOST_KEY) {
                network.approve(device, sessionId, DkgApprovalStep.HOST_KEY)
            }
        }

    /** Every device holds [sessionId]'s one key and a share of its own; returns the key. */
    private suspend fun assertOneKey(network: CeremonyNetwork, sessionId: String): String {
        val sessions = network.devices.map { device ->
            assertNotNull(network.session(device, sessionId), "$device has the ceremony")
        }
        sessions.forEach {
            assertEquals(DkgRitualStage.COMPLETE, it.stage, "${it.userPublicKey.take(8)}: ${it.failureReason}")
        }
        val keys = sessions.map { assertNotNull(it.thresholdPublicKey) }.toSet()
        assertEquals(1, keys.size, "every device holds the same key")
        assertEquals(sessions.size, sessions.map { assertNotNull(it.secretShare) }.toSet().size)
        return keys.single()
    }
}
