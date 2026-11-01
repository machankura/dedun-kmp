package com.machankura.dedun.compose.ui.composable.navigation.routes

import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import com.machankura.dedun.compose.ui.view.state.ProposalFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Where a tap on a proposal in the transcript lands, and which list it opens on.
 *
 * Three answers from two facts and a null, and they used to be a condition
 * inside a lambda nine levels down a `LazyColumn`. The decision matters more
 * than its depth suggested: one of the three is a member being handed their
 * queue instead of the proposal they asked for, which is only right because
 * answering the one they tapped and leaving would look exactly like being done.
 *
 * The filter is the new part. A tap held back *because other decisions are
 * waiting* should open on those decisions -- opening on the room's whole signing
 * history would be the same mistake in the other direction, since it was the
 * other decisions and not the history that the one proposal was withheld for.
 * An unnamed line gets the room, because it could be about anything.
 */
class ProposalDestinationTest {
    private val me = "a".repeat(64)
    private val roomId = "d4c3b2a1".repeat(8)

    private fun destination(
        sessionId: String?,
        waiting: Set<String> = emptySet(),
    ) = proposalDestination(
        activeUserPublicKey = me,
        chatRoomId = roomId,
        sessionId = sessionId,
        hasOtherDecisionsWaiting = { it in waiting },
    )

    @Test
    fun `a line too old to name its proposal opens the room unfiltered`() {
        assertEquals(
            ProposalListRoute(activeUserPublicKey = me, chatRoomId = roomId),
            destination(sessionId = null)
        )
        assertEquals(
            ProposalFilter.ALL,
            (destination(sessionId = null) as ProposalListRoute).filter
        )
    }

    /** Even when the room is busy: an unnamed line is not a claim about the queue. */
    @Test
    fun `an unnamed line opens the room even with decisions waiting`() {
        assertEquals(
            ProposalListRoute(activeUserPublicKey = me, chatRoomId = roomId),
            destination(sessionId = null, waiting = setOf("one", "two"))
        )
    }

    @Test
    fun `one named proposal and nothing else being asked opens that proposal`() {
        assertEquals(
            FrostSigningRoute(
                activeUserPublicKey = me,
                chatRoomId = roomId,
                sessionId = "one"
            ),
            destination(sessionId = "one")
        )
    }

    @Test
    fun `a proposal held back for the others opens on the queue not the history`() {
        val route = destination(sessionId = "one", waiting = setOf("one"))

        assertEquals(
            ProposalListRoute(
                activeUserPublicKey = me,
                chatRoomId = roomId,
                filter = ProposalFilter.NEEDS_YOU
            ),
            route
        )
    }

    /**
     * The decision is asked of the session that was tapped, and of no other.
     *
     * `hidesOtherDecisions` only answers true for a proposal that is itself
     * waiting on this member -- a line about something already signed is
     * history, and a reader who taps one is asking to see that rather than to be
     * handed the queue. Passing the wrong id here would quietly invert that.
     */
    @Test
    fun `the queue is offered only for the proposal that was tapped`() {
        assertEquals(
            FrostSigningRoute(
                activeUserPublicKey = me,
                chatRoomId = roomId,
                sessionId = "settled"
            ),
            destination(sessionId = "settled", waiting = setOf("one", "two"))
        )
    }

    @Test
    fun `the route carries the room and the reader through every branch`() {
        for (route in listOf(
            destination(sessionId = null),
            destination(sessionId = "one", waiting = setOf("one")),
            destination(sessionId = "one"),
        )) {
            when (route) {
                is ProposalListRoute -> {
                    assertEquals(me, route.activeUserPublicKey)
                    assertEquals(roomId, route.chatRoomId)
                }

                is FrostSigningRoute -> {
                    assertEquals(me, route.activeUserPublicKey)
                    assertEquals(roomId, route.chatRoomId)
                }

                else -> error("unexpected destination $route")
            }
        }
    }

    // --- the filter as a navigation argument -----------------------------------------

    /**
     * The filter rides on a type-safe navigation route, which means the route's
     * serializer has to describe it.
     *
     * Navigation derives an argument's `NavType` from the serial descriptor, and
     * takes an [SerialKind.ENUM] as a string. The round trip below is what that
     * rests on; it fails if the enum is ever replaced by something navigation
     * cannot place -- a sealed hierarchy, say -- which is the shape a filter
     * carrying more than a state would want.
     */
    @Test
    fun `the filter survives the route's serializer which is what navigation reads`() {
        val descriptor = serializer<ProposalListRoute>().descriptor
        val filterIndex = descriptor.getElementIndex("filter")

        assertTrue(filterIndex >= 0, "the route stopped carrying a filter")
        assertEquals(
            SerialKind.ENUM,
            descriptor.getElementDescriptor(filterIndex).kind,
            "navigation takes an enum argument as a string; this is no longer an enum"
        )

        for (filter in ProposalFilter.entries) {
            val route = ProposalListRoute(
                activeUserPublicKey = me,
                chatRoomId = roomId,
                filter = filter,
            )

            assertEquals(route, Json.decodeFromString(Json.encodeToString(route)))
        }
    }

    /**
     * A route built without one shows the room.
     *
     * The default is what the group's own Proposals row relies on, and what every
     * caller predating the argument reads back as -- which is what they all were.
     */
    @Test
    fun `a route that names no filter means all of them`() {
        assertEquals(
            ProposalFilter.ALL,
            ProposalListRoute(activeUserPublicKey = me, chatRoomId = roomId).filter
        )

        // And an encoding that omits it, which is what a saved back stack from
        // before the argument existed looks like.
        val withoutFilter = """{"activeUserPublicKey":"$me","chatRoomId":"$roomId"}"""

        assertEquals(
            ProposalFilter.ALL,
            Json.decodeFromString<ProposalListRoute>(withoutFilter).filter
        )
    }

    /** Named so a stored route reads as the state it means, not as an ordinal. */
    @Test
    fun `the filter is stored by name`() {
        val encoded = Json.encodeToString(
            ProposalListRoute(
                activeUserPublicKey = me,
                chatRoomId = roomId,
                filter = ProposalFilter.NEEDS_YOU,
            )
        )

        assertTrue(encoded.contains("NEEDS_YOU"), "the filter was not stored by name: $encoded")
    }
}
