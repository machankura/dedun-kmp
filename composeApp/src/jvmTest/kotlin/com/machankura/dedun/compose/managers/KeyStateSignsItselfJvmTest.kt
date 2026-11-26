package com.machankura.dedun.compose.managers

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.model.DkgSession
import com.machankura.dedun.compose.database.model.FrostSigningItem
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.types.DkgApprovalStep
import com.machankura.dedun.compose.database.model.types.DkgRitualStage
import com.machankura.dedun.compose.nostr.frost.GroupKeyStateEvent
import com.machankura.dedun.compose.repository.ChatRepository

/**
 * The key state of a ceremony a device finished signs itself, and nothing else
 * does -- docs/automatic-key-ceremony.md, Phase 7.
 *
 * For a ceremony a device took part in there is exactly one true statement of
 * what its room signs with. `GroupKeyStateManager.vouchesFor` is the rule that says
 * a proposal is that statement; anything it cannot vouch for is asked about as
 * every other signature is.
 */
class KeyStateSignsItselfJvmTest {

    /** The rooms the coordinator's device went on to create. */
    private val created = mutableListOf<Pair<String, Set<HexKey>>>()

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
            return MarmotGroupCreation.Outcome.Failed("the room is MarmotGroupCreation's to test")
        }
    }

    /** A finished group ceremony whose coordinator has just asked the group to sign its key state. */
    private suspend fun keyStateAsked(network: CeremonyNetwork): String {
        val session = network.propose(threshold = 2)
        network.deliverUntilQuiet()
        network.devices.drop(1).forEach { network.approve(it, session.id, DkgApprovalStep.HOST_KEY) }
        network.deliverUntilQuiet()

        val coordinator = network.coordinator
        assertEquals(
            CeremonyFollowThrough.Step.Proposed("key state"),
            CeremonyFollowThrough.takeNextStep(
                coordinator.db,
                chatRepository,
                assertNotNull(network.session(coordinator, session.id)),
                coordinator.keys,
            ),
        )
        return session.id
    }

    private suspend fun signingSessionOn(network: CeremonyNetwork, device: CeremonyNetwork.Device): FrostSigningSession =
        device.db.frostSigningSessionDao().getSessionsForChatRoom(network.roomId).single()

    /**
     * The whole of the change, end to end: after the joins nobody is asked
     * anything, and the group ends with a signed key state and its room on the way.
     */
    @Test
    fun `after the joins the group signs its key state and the room is made`(): Unit = runBlocking {
        CeremonyNetwork.nip17(size = 3).use { network ->
            val ceremonyId = keyStateAsked(network)
            network.deliverUntilQuiet()

            val key = assertNotNull(network.session(network.coordinator, ceremonyId)?.thresholdPublicKey)
            val roomId = SharedKeyDerivation.marmotGroupId(key)

            network.devices.forEach { device ->
                val signing = signingSessionOn(network, device)
                assertNotNull(signing.signApprovedAt, "$device signed without being asked")
                assertNotNull(
                    GroupKeyStateManager.stateAmong(
                        device.db.groupSignedEventDao().getByKind(GroupKeyStateEvent.KIND),
                        roomId,
                    ),
                    "$device holds the group's signed key state",
                )
            }

            CeremonyFollowThrough.followThrough(
                network.coordinator.db,
                chatRepository,
                assertNotNull(network.session(network.coordinator, ceremonyId)),
                network.coordinator.keys,
            )
            assertEquals(listOf(roomId to network.devices.map { it.publicKey }.toSet()), created)
        }
    }

    /** Each rule, broken on its own, leaves the decision with the member. */
    @Test
    fun `a member's device vouches for its ceremony's key state and nothing else`(): Unit = runBlocking {
        CeremonyNetwork.nip17(size = 3).use { network ->
            val ceremonyId = keyStateAsked(network)
            val member = network.devices[1]

            // The proposal reaches the member, and nothing after it yet.
            network.deliver()

            val session = signingSessionOn(network, member)
            val items = member.db.frostSigningSessionDao().getItems(session.id)
            val item = items.single()
            val event = Event.fromJson(item.unsignedEventJson)

            suspend fun vouches(s: FrostSigningSession = session, i: List<FrostSigningItem> = items) =
                GroupKeyStateManager.vouchesFor(member.db, s, i)

            assertTrue(vouches(), "the one true statement")

            assertFalse(vouches(i = listOf(item, item)), "never a batch")
            assertFalse(vouches(i = listOf(item.rebuilt(event, kind = 1))), "only a key state")
            assertFalse(
                vouches(i = listOf(item.rebuilt(event, content = "02" + "ab".repeat(32)))),
                "naming another key",
            )
            assertFalse(
                vouches(i = listOf(item.rebuilt(event, tags = event.tags.withD("0f".repeat(32))))),
                "naming a room the key does not derive",
            )
            assertFalse(
                vouches(s = session.copy(derivationPath = SharedKeyDerivation.formatPath(listOf(9420L, 0L, 1L)))),
                "at another path",
            )
            assertFalse(
                vouches(s = session.copy(dkgSessionId = "somebody-elses-ceremony")),
                "with a key this device holds no ceremony for",
            )

            val ceremony = assertNotNull(network.session(member, ceremonyId))
            member.db.dkgSessionDao().upsert(ceremony.copy(parentChatRoomId = "9a".repeat(32)))
            assertFalse(vouches(), "claiming no parent for a ceremony run to make a subgroup")
            member.db.dkgSessionDao().upsert(ceremony.copy(stage = DkgRitualStage.COLLECTING_ROUND_2))
            assertFalse(vouches(), "for a ceremony this device has not finished")
            member.db.dkgSessionDao().upsert(ceremony)

            assertTrue(vouches(), "and back to the one true statement")

            network.deliverUntilQuiet()
            assertFalse(vouches(), "but not once the group has signed one")
        }
    }

    /**
     * Anything a device cannot vouch for is asked about as before: the signing
     * session waits on its owner, and the transcript says so.
     */
    @Test
    fun `a key state the device cannot vouch for waits for its owner`(): Unit = runBlocking {
        CeremonyNetwork.nip17(size = 3).use { network ->
            val ceremonyId = keyStateAsked(network)
            val member = network.devices[1]

            // The member's row says the ceremony was run to make a subgroup, and
            // the state claims no parent: not a statement it can vouch for.
            val ceremony = assertNotNull(network.session(member, ceremonyId))
            member.db.dkgSessionDao().upsert(ceremony.copy(parentChatRoomId = "9a".repeat(32)))

            network.deliver()

            val signing = signingSessionOn(network, member)
            assertEquals(null, signing.signApprovedAt, "nothing signed on the member's behalf")
            assertTrue(
                FrostSigningManager.isAwaitingApproval(signing, member.db.frostSigningSessionDao().getItems(signing.id)),
                "the member is asked",
            )
        }
    }

    private fun FrostSigningItem.rebuilt(
        event: Event,
        kind: Int = event.kind,
        tags: Array<Array<String>> = event.tags,
        content: String = event.content,
    ) = copy(
        unsignedEventJson = Event(
            id = event.id,
            pubKey = event.pubKey,
            createdAt = event.createdAt,
            kind = kind,
            tags = tags,
            content = content,
            sig = event.sig,
        ).toJson()
    )

    private fun Array<Array<String>>.withD(value: String) =
        map { if (it.firstOrNull() == "d") arrayOf("d", value) else it }.toTypedArray()
}
