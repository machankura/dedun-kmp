package com.machankura.dedun.compose.ui.view.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.FrostSigningItem
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.types.FrostSigningStage
import com.machankura.dedun.compose.managers.FrostSigningManager

/**
 * How a proposal is classified for the list, and what the classification is
 * allowed to disagree with.
 *
 * The screen asks this question in four places -- the section split, the row's
 * icon, that icon's description and its status line -- and the filter chips make
 * five. They used to each work it out from the stage and the approval
 * timestamps, which is how a chip comes to count a proposal the row beneath it
 * describes differently. So the classification is pinned here, before anything
 * renders it.
 *
 * What it must agree with is `FrostSigningManager.isAwaitingApproval`. That is
 * what the signing screen gates its two buttons on, so a list calling a
 * proposal settled while the screen behind it still offers a decision would be
 * hiding a decision rather than filtering one. [awaitsYou] is fed from it here
 * exactly as the view model feeds it, rather than being asserted by hand.
 */
class ProposalStateTest {
    private val roomId = "11".repeat(32)
    private val ada = "a".repeat(64)
    private val bob = "b".repeat(64)

    /** A 2-of-3 session as the proposer's second member sees it, before they answer. */
    private fun session(
        id: String = "session",
        stage: FrostSigningStage = FrostSigningStage.COLLECTING_NONCES,
    ) = FrostSigningSession(
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

    /** The room the proposals belong to. Nothing here reads anything off it. */
    private fun room() = LocalChatRoom(
        chatRoom = ChatRoom(
            id = roomId,
            userPublicKey = bob,
            subject = "#Ekklesia",
            description = null,
            mlsGroupState = null,
        )
    )

    private fun item(
        sessionId: String = "session",
        itemIndex: Int = 0,
        eventId: String = "e".repeat(64),
        signature: String? = null,
    ) = FrostSigningItem(
        sessionId = sessionId,
        itemIndex = itemIndex,
        eventId = eventId,
        unsignedEventJson = """{"id":"$eventId"}""",
        nonceRandom = "00".repeat(32),
        signature = signature,
    )

    /**
     * One proposal, built the way the view model builds it.
     *
     * [awaitsYou] through the manager rather than passed in, so a change to what
     * the signing screen considers answered lands in these expectations too.
     */
    private fun proposal(
        session: FrostSigningSession,
        items: List<FrostSigningItem> = listOf(item(sessionId = session.id)),
    ) = ProposalListUIState.Proposal(
        session = session,
        proposerName = "Ada",
        awaitsYou = FrostSigningManager.isAwaitingApproval(session, items),
    )

    @Test
    fun `an open session nobody has answered needs you`() {
        assertEquals(ProposalState.NEEDS_YOU, proposal(session()).state)
    }

    @Test
    fun `agreeing moves it out of your queue still short of members`() {
        val agreed = session().copy(signApprovedAt = Clock.System.now())

        assertEquals(ProposalState.WAITING_ON_MEMBERS, proposal(agreed).state)
    }

    @Test
    fun `refusing moves it out of your queue too because either answer is an answer`() {
        val declined = session().copy(signDeclinedAt = Clock.System.now())

        assertEquals(ProposalState.WAITING_ON_MEMBERS, proposal(declined).state)
    }

    /**
     * The two open stages are two states, and this is the pair that carries the
     * distinction.
     *
     * Short of members, the proposal can still die of indifference: refusals are
     * counted against `declinesThatAbandon`, and an offline member is
     * indistinguishable from one who has not looked. It is also the only stage
     * the coordinator's resend is for. Once the signer set is written neither is
     * true -- `signerIds` is written once and never revised -- so what is left is
     * arithmetic the chosen devices have already committed to.
     *
     * Collapsing the two would file "somebody may have to chase this" and
     * "nothing to do but wait" under one chip.
     */
    @Test
    fun `fixing the signer set is what moves it from waiting on members to being signed`() {
        val answered = session().copy(signApprovedAt = Clock.System.now())

        assertEquals(ProposalState.WAITING_ON_MEMBERS, proposal(answered).state)

        val fixed = answered.copy(
            stage = FrostSigningStage.COLLECTING_PARTIAL_SIGNATURES,
            signerIds = "1,2",
        )

        assertEquals(ProposalState.BEING_SIGNED, proposal(fixed).state)
    }

    /**
     * Whether this device was picked is a fact about the reader, not a state of
     * the proposal, so it does not move it between chips. The row's status line
     * says which it is -- "you are signing" against "enough members took part
     * without you" -- and that is where a fact about the reader belongs.
     */
    @Test
    fun `being signed does not depend on whether you were picked to sign it`() {
        val collecting = session(stage = FrostSigningStage.COLLECTING_PARTIAL_SIGNATURES)
            .copy(signApprovedAt = Clock.System.now())

        val picked = collecting.copy(signerIds = "1,2")
        val passedOver = collecting.copy(signerIds = "1,3")

        assertTrue(picked.isSigner(), "the fixture should have this device signing")
        assertFalse(passedOver.isSigner(), "the fixture should have this device passed over")

        assertEquals(ProposalState.BEING_SIGNED, proposal(picked).state)
        assertEquals(ProposalState.BEING_SIGNED, proposal(passedOver).state)
    }

    @Test
    fun `a complete session is signed`() {
        assertEquals(
            ProposalState.SIGNED,
            proposal(session(stage = FrostSigningStage.COMPLETE)).state
        )
    }

    @Test
    fun `a failed session is abandoned`() {
        assertEquals(
            ProposalState.ABANDONED,
            proposal(session(stage = FrostSigningStage.FAILED)).state
        )
    }

    /**
     * An ended session is never in the reader's queue, whatever they did or did
     * not answer. This is the rule that lets the row's failure colour be chosen
     * by the classification alone: `error` is 2.67:1 on the highlighted card, and
     * the highlighted card is [ProposalState.NEEDS_YOU] -- so the two can never
     * meet.
     */
    @Test
    fun `an ended session never needs you even unanswered`() {
        for (stage in listOf(FrostSigningStage.COMPLETE, FrostSigningStage.FAILED)) {
            val ended = proposal(session(stage = stage))

            assertEquals(false, ended.awaitsYou, "$stage should not await an answer")
            assertTrue(
                ended.state != ProposalState.NEEDS_YOU,
                "$stage should not be in the reader's queue"
            )
        }
    }

    /**
     * The group signed it without needing this member.
     *
     * `isAwaitingApproval` stops asking once every item carries a signature,
     * because `advance` closes the session on its next pass and the only answers
     * left to give are a nonce nobody is waiting for or a refusal nobody counts.
     * The stage has not caught up yet, so it is still being signed -- which is
     * also what the row says: "enough members took part without you".
     */
    @Test
    fun `a fully signed batch is still being signed until advance closes it`() {
        val signed = session(stage = FrostSigningStage.COLLECTING_PARTIAL_SIGNATURES)
        val items = listOf(item(signature = "ab".repeat(32)))

        assertEquals(ProposalState.BEING_SIGNED, proposal(signed, items).state)
    }

    /**
     * A session that exists before its proposal has arrived.
     *
     * It classifies [ProposalState.NEEDS_YOU] although there is nothing in it to
     * read -- the card says "nothing has arrived to sign yet" -- and that is
     * deliberate parity rather than an oversight: `isAwaitingApproval` only
     * short-circuits on a *non-empty* batch that is fully signed, so the signing
     * screen still offers the decision. A filter that classified it any other way
     * would hide a row that screen would act on.
     */
    @Test
    fun `a session with nothing in it yet still needs you matching the signing screen`() {
        val empty = proposal(session(), items = emptyList())

        assertEquals(0, empty.eventCount)
        assertEquals(ProposalState.NEEDS_YOU, empty.state)
    }

    /** Every proposal lands in exactly one state, so counting them cannot double. */
    @Test
    fun `the split covers every proposal exactly once`() {
        val proposals = listOf(
            proposal(session(id = "needs")),
            proposal(session(id = "open").copy(signApprovedAt = Clock.System.now())),
            proposal(
                session(id = "signing", stage = FrostSigningStage.COLLECTING_PARTIAL_SIGNATURES)
                    .copy(signApprovedAt = Clock.System.now(), signerIds = "1,2")
            ),
            proposal(session(id = "done", stage = FrostSigningStage.COMPLETE)),
            proposal(session(id = "gone", stage = FrostSigningStage.FAILED)),
        )

        val loaded = ProposalListUIState.Loaded(
            localChatRoom = room(),
            proposals = proposals,
        )

        assertEquals(1, loaded.waitingForYou.size)
        assertEquals(4, loaded.rest.size)
        assertEquals(proposals.size, loaded.waitingForYou.size + loaded.rest.size)

        // One of each, and every state reached: five proposals, five states.
        assertEquals(ProposalState.entries.toSet(), proposals.map { it.state }.toSet())
        assertEquals(proposals.size, proposals.map { it.state }.distinct().size)
    }

    /** The split is the classification, not a second reading of `awaitsYou`. */
    @Test
    fun `the waiting section is exactly the proposals in your queue`() {
        val proposals = listOf(
            proposal(session(id = "needs")),
            proposal(session(id = "also")),
            proposal(session(id = "done", stage = FrostSigningStage.COMPLETE)),
        )

        val loaded = ProposalListUIState.Loaded(localChatRoom = room(), proposals = proposals)

        assertEquals(
            proposals.filter { it.state == ProposalState.NEEDS_YOU },
            loaded.waitingForYou
        )
        assertTrue(loaded.rest.none { it.state == ProposalState.NEEDS_YOU })
    }
}
