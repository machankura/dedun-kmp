package com.machankura.dedun.compose.ui.view.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.types.FrostSigningStage
import com.machankura.dedun.compose.managers.FrostSigningManager

/**
 * When a row offers to ask the quiet members again, and when it only looks like
 * it could.
 *
 * The offer is the coordinator's and nobody else's -- `acceptProposal` takes a
 * session's coordinator from the author of the proposal that opened it, so a
 * resend from anybody else would have a member who was missing the session
 * believe the resender coordinates, and then drop the real coordinator's signer
 * set. The row expresses that as a null rather than a zero: not ours to send is
 * a different fact from nobody left to send to, and only one of them could ever
 * become a button.
 */
class ProposalAskAgainTest {
    private val roomId = "11".repeat(32)
    private val me = "a".repeat(64)
    private val somebodyElse = "b".repeat(64)

    private fun session(
        coordinator: String = me,
        stage: FrostSigningStage = FrostSigningStage.COLLECTING_PARTIAL_SIGNATURES,
        resentAt: kotlin.time.Instant? = null,
    ) = FrostSigningSession(
        id = "session",
        chatRoomId = roomId,
        coordinatorPublicKey = coordinator,
        userPublicKey = me,
        dkgSessionId = "ceremony",
        threshold = 2,
        participantCount = 3,
        signerId = 1,
        stage = stage,
        resentAt = resentAt,
    )

    private fun proposal(
        session: FrostSigningSession = session(),
        owing: Int? = null,
    ) = ProposalListUIState.Proposal(
        session = session,
        proposerName = "You",
        owing = owing,
    )

    @Test
    fun `a session waiting on somebody offers the ask`() {
        assertTrue(proposal(owing = 1).canAskAgain)
        assertTrue(proposal(owing = 3).canAskAgain)
    }

    @Test
    fun `a session waiting on nobody offers nothing because there is nobody to ask`() {
        assertFalse(proposal(owing = 0).canAskAgain)
    }

    /**
     * Null is what the view model answers for a session this device did not
     * propose, and it must not read as "waiting on nobody" turning into a button
     * by some later arithmetic.
     */
    @Test
    fun `a session that is not ours to send offers nothing`() {
        assertFalse(proposal(session(coordinator = somebodyElse), owing = null).canAskAgain)
        assertNull(proposal(session(coordinator = somebodyElse)).owing)
    }

    @Test
    fun `a proposal says nothing about the cooldown until one has been sent`() {
        assertNull(proposal(owing = 1).askAgainAt)
    }

    @Test
    fun `an ask sent just now is still cooling down`() {
        val justNow = proposal(session(resentAt = Clock.System.now()), owing = 1)

        assertNotNull(justNow.askAgainAt, "the cooldown should still be running")
        assertTrue(
            justNow.canAskAgain,
            "the offer stays and the button goes quiet; a control that vanishes reads as one that failed"
        )
    }

    @Test
    fun `an ask sent longer ago than the cooldown may go again`() {
        val old = Clock.System.now() - FrostSigningManager.RESEND_COOLDOWN - kotlin.time.Duration.parse("1s")

        assertNull(proposal(session(resentAt = old), owing = 1).askAgainAt)
    }

    /** The boundary belongs to the manager, so the row reads it rather than guessing. */
    @Test
    fun `the cooldown the row honours is the one the manager enforces`() {
        val sentAt = Clock.System.now() - FrostSigningManager.RESEND_COOLDOWN / 2
        val halfway = proposal(session(resentAt = sentAt), owing = 1)

        assertEquals(
            sentAt + FrostSigningManager.RESEND_COOLDOWN,
            halfway.askAgainAt,
            "the row's cooldown drifted from the manager's"
        )
    }

    /**
     * The offer does not depend on the stage here, because the view model has
     * already decided which stages it asks about -- see `owingFor`. A row only
     * ever receives a count for a session that could be resent.
     */
    @Test
    fun `a count on any stage the view model asks about becomes an offer`() {
        for (stage in listOf(
            FrostSigningStage.COLLECTING_NONCES,
            FrostSigningStage.COLLECTING_PARTIAL_SIGNATURES,
        )) {
            assertTrue(
                proposal(session(stage = stage), owing = 1).canAskAgain,
                "$stage should be askable"
            )
        }
    }

    // --- asking about all of them at once -------------------------------------------

    private fun loaded(
        proposals: List<ProposalListUIState.Proposal>,
        filter: ProposalFilter = ProposalFilter.ALL,
    ) = ProposalListUIState.Loaded(
        localChatRoom = com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom(
            chatRoom = com.machankura.dedun.compose.database.model.ChatRoom(
                id = roomId,
                userPublicKey = me,
                subject = "#Ekklesia",
                description = null,
                mlsGroupState = null,
            )
        ),
        proposals = proposals,
        filter = filter,
    )

    private fun askable(id: String, owing: Int? = 1, resentAt: kotlin.time.Instant? = null) =
        ProposalListUIState.Proposal(
            session = session(resentAt = resentAt).copy(id = id),
            proposerName = "You",
            owing = owing,
        )

    @Test
    fun `two askable rows on screen earn the bulk offer`() {
        val state = loaded(listOf(askable("one"), askable("two")))

        assertTrue(state.canAskAll)
        assertEquals(listOf("one", "two"), state.askableVisible.map { it.session.id })
    }

    /** One is what the row's own button is for. */
    @Test
    fun `a single askable row does not get a second way to ask`() {
        assertFalse(loaded(listOf(askable("one"))).canAskAll)
    }

    /**
     * The offer is over what the filter put on screen. A bulk control that acted
     * on proposals the reader could not see would be the one thing it must never
     * do, so it is drawn from `visible` and not from the room.
     */
    @Test
    fun `the bulk offer covers what the filter shows and nothing behind it`() {
        val signing = askable("signing").let {
            it.copy(session = it.session.copy(stage = FrostSigningStage.COLLECTING_PARTIAL_SIGNATURES))
        }
        val alsoSigning = askable("also").let {
            it.copy(session = it.session.copy(stage = FrostSigningStage.COLLECTING_PARTIAL_SIGNATURES))
        }
        val gathering = askable("gathering").let {
            it.copy(session = it.session.copy(stage = FrostSigningStage.COLLECTING_NONCES))
        }

        val filtered = loaded(
            listOf(signing, alsoSigning, gathering),
            filter = ProposalFilter.BEING_SIGNED,
        )

        assertEquals(
            listOf("signing", "also"),
            filtered.askableVisible.map { it.session.id },
            "the bulk offer reached past the filter"
        )
        assertTrue(filtered.canAskAll)

        // The one left out is askable in its own right; it is simply not on screen.
        assertTrue(gathering.canAskAgain)
    }

    /**
     * A proposal inside its cooldown is left out rather than included and
     * skipped. `resend` would decline it either way, but the count is what the
     * button says out loud, and a button offering three when it can send two is
     * a button that lies before it is pressed.
     */
    @Test
    fun `a cooling-down row is left out of the count not skipped inside it`() {
        val state = loaded(
            listOf(
                askable("one"),
                askable("two"),
                askable("cooling", resentAt = Clock.System.now()),
            )
        )

        assertEquals(listOf("one", "two"), state.askableVisible.map { it.session.id })
        assertTrue(state.canAskAll)
    }

    @Test
    fun `cooling down to one leaves no bulk offer`() {
        val state = loaded(
            listOf(askable("one"), askable("cooling", resentAt = Clock.System.now()))
        )

        assertEquals(1, state.askableVisible.size)
        assertFalse(state.canAskAll)
    }

    @Test
    fun `rows that are not ours to send are not counted`() {
        val state = loaded(listOf(askable("one"), askable("theirs", owing = null)))

        assertEquals(listOf("one"), state.askableVisible.map { it.session.id })
        assertFalse(state.canAskAll)
    }

    @Test
    fun `a room with nothing askable offers nothing`() {
        assertFalse(loaded(listOf(askable("none", owing = 0))).canAskAll)
        assertTrue(loaded(emptyList()).askableVisible.isEmpty())
    }
}
