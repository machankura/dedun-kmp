package com.machankura.dedun.compose.ui.view.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.DkgSession
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.types.DkgRitualStage

/**
 * When the ritual screen calls a ceremony stalled, and who it says it is waiting on.
 *
 * Once everyone has joined nobody is asked anything, so the only thing a running
 * ceremony can wait on is a device that is not answering, and abandoning is how
 * that ends. These are the rules for putting that beside the name.
 */
class DkgRitualStallTest {

    private val ada = "a".repeat(64)
    private val bob = "b".repeat(64)
    private val cal = "c".repeat(64)

    private val started = Instant.fromEpochSeconds(1_800_000_000)

    private fun loaded(
        stage: DkgRitualStage = DkgRitualStage.COLLECTING_HOST_KEYS,
        user: String = ada,
        hostKeys: Set<String> = emptySet(),
        round1: Set<String> = emptySet(),
        round2: Set<String> = emptySet(),
        coordinatorRound1: String? = null,
        lastProgressAt: Instant? = null,
    ) = DkgRitualUIState.Loaded(
        localChatRoom = LocalChatRoom(
            chatRoom = ChatRoom(
                id = "room",
                userPublicKey = user,
                subject = "Checkers",
                description = null,
                mlsGroupState = null,
            )
        ),
        session = DkgSession(
            id = "ceremony",
            chatRoomId = "room",
            coordinatorPublicKey = ada,
            userPublicKey = user,
            threshold = 2,
            participantCount = 3,
            participantPublicKeys = DkgSession.formatParticipants(setOf(ada, bob, cal)),
            stage = stage,
            hostPublicKey = "02".repeat(33),
            round1Random = "aa".repeat(32),
            round2AuxRandom = "bb".repeat(32),
            coordinatorRound1 = coordinatorRound1,
            createdAt = started,
        ),
        hostKeyParticipants = hostKeys,
        round1Participants = round1,
        round2Participants = round2,
        lastProgressAt = lastProgressAt,
    )

    @Test
    fun `a ceremony still for less than a day is not stalled`() {
        val state = loaded(hostKeys = setOf(ada), lastProgressAt = started)

        assertNull(state.stalledSince(started + 23.hours))
    }

    @Test
    fun `a ceremony still for a day is stalled since it last moved`() {
        val moved = started + 2.hours
        val state = loaded(hostKeys = setOf(ada, bob), lastProgressAt = moved)

        assertEquals(moved, state.stalledSince(moved + 24.hours))
        assertEquals(setOf(cal), state.waitingOn, "on the member who has not joined")
    }

    @Test
    fun `a ceremony that has heard nothing dates from when it was recorded`() {
        val state = loaded()

        assertEquals(started, state.stalledSince(started + 30.hours))
    }

    /** Only a participant can abandon, so only a participant is told to. */
    @Test
    fun `a member who is not in it is not told it has stalled`() {
        val state = loaded(user = "d".repeat(64), lastProgressAt = started)

        assertNull(state.stalledSince(started + 48.hours))
    }

    @Test
    fun `a finished or failed ceremony has not stalled`() {
        listOf(DkgRitualStage.COMPLETE, DkgRitualStage.FAILED).forEach { stage ->
            assertNull(loaded(stage = stage, lastProgressAt = started).stalledSince(started + 48.hours), "$stage")
        }
    }

    /**
     * With every contribution in, nobody is missing from the round, and the device
     * that has to act is the coordinator's, which combines them.
     */
    @Test
    fun `with every contribution in it is waiting on the coordinator`() {
        val everyone = setOf(ada, bob, cal)
        val state = loaded(
            stage = DkgRitualStage.COLLECTING_ROUND_1,
            user = bob,
            hostKeys = everyone,
            round1 = everyone,
            lastProgressAt = started,
        )

        assertEquals(setOf(ada), state.waitingOn)
    }

    @Test
    fun `mid-round it is waiting on whoever has not sent theirs`() {
        val everyone = setOf(ada, bob, cal)
        val state = loaded(
            stage = DkgRitualStage.COLLECTING_ROUND_2,
            hostKeys = everyone,
            round1 = everyone,
            round2 = setOf(ada),
            coordinatorRound1 = "00",
        )

        assertEquals(setOf(bob, cal), state.waitingOn)
    }
}
