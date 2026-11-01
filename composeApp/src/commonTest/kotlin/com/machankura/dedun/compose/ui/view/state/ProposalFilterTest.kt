package com.machankura.dedun.compose.ui.view.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.types.FrostSigningStage

/**
 * What a filter selects, and what the chips count.
 *
 * The two are deliberately different lists: the rows come from the selection,
 * the numbers come from the room. A chip reading its own count off the filtered
 * list would say 0 for every filter but the one already chosen, which is the
 * one number the reader does not need.
 */
class ProposalFilterTest {
    private val roomId = "11".repeat(32)
    private val ada = "a".repeat(64)
    private val bob = "b".repeat(64)

    private val room = LocalChatRoom(
        chatRoom = ChatRoom(
            id = roomId,
            userPublicKey = bob,
            subject = "#Ekklesia",
            description = null,
            mlsGroupState = null,
        )
    )

    private fun session(id: String, stage: FrostSigningStage = FrostSigningStage.COLLECTING_NONCES) =
        FrostSigningSession(
            id = id,
            chatRoomId = roomId,
            coordinatorPublicKey = ada,
            userPublicKey = bob,
            dkgSessionId = "ceremony",
            threshold = 2,
            participantCount = 3,
            signerId = 2,
            stage = stage,
        )

    /**
     * `awaitsYou` is passed rather than derived here.
     *
     * `ProposalStateTest` is where it is held to `isAwaitingApproval`; this file
     * is about what the filter does with a classification once it has one, and
     * building each state through the manager would make every case here depend
     * on the protocol's rules for no extra coverage.
     */
    private fun proposal(
        id: String,
        stage: FrostSigningStage = FrostSigningStage.COLLECTING_NONCES,
        awaitsYou: Boolean = false,
    ) = ProposalListUIState.Proposal(
        session = session(id, stage),
        proposerName = "Ada",
        awaitsYou = awaitsYou,
    )

    private val needsYou = proposal("needs", awaitsYou = true)
    private val alsoNeedsYou = proposal("also", awaitsYou = true)
    private val open = proposal("open").copy(
        session = session("open").copy(signApprovedAt = Clock.System.now())
    )
    private val beingSigned = proposal(
        "signing",
        stage = FrostSigningStage.COLLECTING_PARTIAL_SIGNATURES,
    ).copy(
        session = session("signing", FrostSigningStage.COLLECTING_PARTIAL_SIGNATURES)
            .copy(signApprovedAt = Clock.System.now(), signerIds = "1,2")
    )
    private val signed = proposal("signed", stage = FrostSigningStage.COMPLETE)
    private val abandoned = proposal("abandoned", stage = FrostSigningStage.FAILED)

    private fun loaded(filter: ProposalFilter = ProposalFilter.ALL) = ProposalListUIState.Loaded(
        localChatRoom = room,
        proposals = listOf(needsYou, alsoNeedsYou, open, beingSigned, signed, abandoned),
        filter = filter,
    )

    /**
     * The chips run in lifecycle order behind "all", which is the order a reader
     * scans them in and the order the states actually happen in.
     *
     * Pinned here rather than on the screen: the row scrolls, so a chip past the
     * right edge has no measurable position, and the order is a property of the
     * enum anyway.
     */
    @Test
    fun `the chips run in lifecycle order with all of them first`() {
        assertEquals(
            listOf(
                ProposalFilter.ALL,
                ProposalFilter.NEEDS_YOU,
                ProposalFilter.WAITING_ON_MEMBERS,
                ProposalFilter.BEING_SIGNED,
                ProposalFilter.SIGNED,
                ProposalFilter.ABANDONED,
            ),
            ProposalFilter.entries
        )

        // And the states they select are in their own declaration order, so the
        // two enums cannot drift into disagreeing about what follows what.
        assertEquals(
            ProposalState.entries,
            ProposalFilter.entries.mapNotNull { it.state }
        )
    }

    @Test
    fun `every filter but ALL names exactly one state and ALL names none`() {
        assertEquals(null, ProposalFilter.ALL.state)

        assertEquals(
            ProposalState.entries.toSet(),
            ProposalFilter.entries.mapNotNull { it.state }.toSet(),
            "every state should be reachable by a chip"
        )
        assertEquals(
            ProposalFilter.entries.size - 1,
            ProposalFilter.entries.mapNotNull { it.state }.distinct().size,
            "no two chips should select the same state"
        )

        for (state in ProposalState.entries) {
            assertEquals(state, ProposalFilter.of(state).state)
        }
    }

    /**
     * The two open stages are two chips.
     *
     * Short of members, a proposal can still die of indifference and the
     * coordinator's resend is the lever for it; once the signer set is fixed
     * there is nothing to do but wait. One chip over both would file "somebody
     * may have to chase this" with "nothing to do".
     */
    @Test
    fun `an open proposal is filed by whether it is still short of members`() {
        assertEquals(ProposalState.WAITING_ON_MEMBERS, open.state)
        assertEquals(ProposalState.BEING_SIGNED, beingSigned.state)

        assertEquals(
            listOf(ProposalFilter.WAITING_ON_MEMBERS, ProposalFilter.BEING_SIGNED),
            ProposalFilter.entries.filter { it.state in setOf(open.state, beingSigned.state) }
        )
    }

    @Test
    fun `the default shows everything in the order it arrived`() {
        val state = loaded()

        assertEquals(ProposalFilter.ALL, state.filter)
        assertEquals(state.proposals, state.visible)
    }

    @Test
    fun `a filter narrows the rows to its own state`() {
        assertEquals(listOf(needsYou, alsoNeedsYou), loaded(ProposalFilter.NEEDS_YOU).visible)
        assertEquals(listOf(open), loaded(ProposalFilter.WAITING_ON_MEMBERS).visible)
        assertEquals(listOf(beingSigned), loaded(ProposalFilter.BEING_SIGNED).visible)
        assertEquals(listOf(signed), loaded(ProposalFilter.SIGNED).visible)
        assertEquals(listOf(abandoned), loaded(ProposalFilter.ABANDONED).visible)
    }

    @Test
    fun `filtering narrows the rows without narrowing the room`() {
        val state = loaded(ProposalFilter.SIGNED)

        assertEquals(1, state.visible.size)
        assertEquals(6, state.proposals.size)

        // The split is the room's, not the selection's: it is what ALL renders,
        // and a filtered screen does not draw it at all.
        assertEquals(2, state.waitingForYou.size)
        assertEquals(4, state.rest.size)
    }

    @Test
    fun `the counts are of the room so they do not move when the filter does`() {
        val expected = mapOf(
            ProposalState.NEEDS_YOU to 2,
            ProposalState.WAITING_ON_MEMBERS to 1,
            ProposalState.BEING_SIGNED to 1,
            ProposalState.SIGNED to 1,
            ProposalState.ABANDONED to 1,
        )

        for (filter in ProposalFilter.entries) {
            assertEquals(expected, loaded(filter).counts, "counts changed under $filter")
        }
    }

    @Test
    fun `the counts sum to the room so no proposal is counted twice or lost`() {
        val state = loaded()

        assertEquals(state.proposals.size, state.counts.values.sum())

        for (each in ProposalState.entries) {
            assertEquals(
                state.counts[each],
                state.proposals.count { it.state == each },
                "$each was miscounted"
            )
        }
    }

    /**
     * A chip is offered for a state the room has none of, reading 0.
     *
     * Deliberate: the alternative is a chip row that gains and loses chips as
     * sessions advance, and sessions advance while this screen is open --
     * someone else signing is exactly when the reader is looking at it. A row
     * that reflows under the thumb is worse than an honest zero.
     */
    @Test
    fun `every state gets a count including the ones the room has never been in`() {
        val quiet = ProposalListUIState.Loaded(
            localChatRoom = room,
            proposals = listOf(needsYou),
        )

        assertEquals(ProposalState.entries.toSet(), quiet.counts.keys)
        assertEquals(0, quiet.counts[ProposalState.ABANDONED])
        assertEquals(1, quiet.counts[ProposalState.NEEDS_YOU])
    }

    @Test
    fun `a filter the room has nothing for selects nothing rather than everything`() {
        val quiet = ProposalListUIState.Loaded(
            localChatRoom = room,
            proposals = listOf(needsYou, open),
            filter = ProposalFilter.ABANDONED,
        )

        assertTrue(quiet.visible.isEmpty())
        assertEquals(2, quiet.proposals.size, "the room is still there to go back to")
    }

    @Test
    fun `an empty room has an empty everything under any filter`() {
        for (filter in ProposalFilter.entries) {
            val empty = ProposalListUIState.Loaded(localChatRoom = room, filter = filter)

            assertTrue(empty.visible.isEmpty())
            assertEquals(0, empty.counts.values.sum())
            assertEquals(ProposalState.entries.toSet(), empty.counts.keys)
        }
    }
}
