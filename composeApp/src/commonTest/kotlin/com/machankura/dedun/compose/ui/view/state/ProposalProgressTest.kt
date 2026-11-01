package com.machankura.dedun.compose.ui.view.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.types.FrostSigningStage

/**
 * How far along a proposal says it is, and when it says nothing.
 *
 * The denominators are the ones the protocol actually gates on, not the ones
 * that happen to be nearby. `FrostSigningManager.orderedNonces` returns null
 * while fewer than `threshold` nonces have arrived and then takes the first
 * `threshold` of them, so the threshold is both what is needed and what gets
 * used -- the room's size is a bigger number that nothing waits for. Once the
 * signer set exists it is the set that is being waited on, which is the same
 * count arrived at from the other side.
 *
 * A figure that read against the room's size would sit at "2 of 5" on a session
 * that was about to complete, and one that read ahead of the coordinator would
 * sit at "3 of 2" on a session that was not moving. Both are worse than no
 * figure, which is why there is a test for each.
 */
class ProposalProgressTest {
    private val roomId = "11".repeat(32)

    private fun session(
        stage: FrostSigningStage = FrostSigningStage.COLLECTING_NONCES,
        threshold: Int = 2,
        participantCount: Int = 5,
        signerIds: String? = null,
    ) = FrostSigningSession(
        id = "session",
        chatRoomId = roomId,
        coordinatorPublicKey = "a".repeat(64),
        userPublicKey = "b".repeat(64),
        dkgSessionId = "ceremony",
        threshold = threshold,
        participantCount = participantCount,
        signerId = 2,
        stage = stage,
        signerIds = signerIds,
        signApprovedAt = Clock.System.now(),
    )

    private fun proposal(
        session: FrostSigningSession,
        nonces: Int = 0,
        signatures: Int = 0,
        awaitsYou: Boolean = false,
    ) = ProposalListUIState.Proposal(
        session = session,
        proposerName = "Ada",
        awaitsYou = awaitsYou,
        noncesOffered = nonces,
        partialSignatures = signatures,
    )

    @Test
    fun `gathering nonces counts members against the threshold not the room`() {
        val progress = proposal(session(threshold = 2, participantCount = 5), nonces = 1).progress

        assertEquals(ProposalListUIState.Progress.Of.MEMBERS, progress?.of)
        assertEquals(1, progress?.have)
        assertEquals(
            2,
            progress?.need,
            "the room has five members but only the threshold is waited for"
        )
    }

    @Test
    fun `gathering signatures counts against the signer set once there is one`() {
        val progress = proposal(
            session(
                stage = FrostSigningStage.COLLECTING_PARTIAL_SIGNATURES,
                threshold = 2,
                participantCount = 5,
                signerIds = "1,3,4",
            ),
            signatures = 2,
        ).progress

        assertEquals(ProposalListUIState.Progress.Of.SIGNATURES, progress?.of)
        assertEquals(2, progress?.have)
        assertEquals(3, progress?.need, "the signer set is what this stage waits on")
    }

    /**
     * The signer set is written together with the aggregate, and this device may
     * reach the stage before that write lands. The threshold is the same number
     * the set will turn out to be, so it stands in rather than the row going
     * blank for a frame.
     */
    @Test
    fun `gathering signatures falls back to the threshold before the set is written`() {
        val progress = proposal(
            session(stage = FrostSigningStage.COLLECTING_PARTIAL_SIGNATURES, threshold = 2),
            signatures = 1,
        ).progress

        assertEquals(1, progress?.have)
        assertEquals(2, progress?.need)
    }

    @Test
    fun `the count each stage shows is the one that stage is waiting for`() {
        val collecting = proposal(session(), nonces = 1, signatures = 7).progress

        assertEquals(ProposalListUIState.Progress.Of.MEMBERS, collecting?.of)
        assertEquals(1, collecting?.have, "it read the signature count while gathering nonces")

        val signing = proposal(
            session(stage = FrostSigningStage.COLLECTING_PARTIAL_SIGNATURES, signerIds = "1,2"),
            nonces = 2,
            signatures = 1,
        ).progress

        assertEquals(ProposalListUIState.Progress.Of.SIGNATURES, signing?.of)
        assertEquals(1, signing?.have, "it read the nonce count while gathering signatures")
    }

    /**
     * A proposal can want the reader's answer on either gathering stage, so the
     * figure follows the stage rather than the classification.
     */
    @Test
    fun `a proposal waiting on you still shows the stage it is actually in`() {
        val needsYou = proposal(session(threshold = 3), nonces = 1, awaitsYou = true)

        assertEquals(ProposalState.NEEDS_YOU, needsYou.state)
        assertEquals(ProposalListUIState.Progress.Of.MEMBERS, needsYou.progress?.of)
        assertEquals(1, needsYou.progress?.have)
        assertEquals(3, needsYou.progress?.need)
    }

    @Test
    fun `a signed proposal counts nothing because there is nothing left to gather`() {
        assertNull(
            proposal(
                session(stage = FrostSigningStage.COMPLETE, signerIds = "1,2"),
                signatures = 2,
            ).progress
        )
    }

    /**
     * An abandoned one counts nothing either, and for a reason of its own: its
     * counts are frozen where they stopped, and "1 of 2" beside a failure invites
     * the reader to wait for a second that is never coming.
     */
    @Test
    fun `an abandoned proposal counts nothing so no row waits for a signature that is not coming`() {
        assertNull(proposal(session(stage = FrostSigningStage.FAILED), nonces = 1).progress)
    }

    /**
     * A nonce can land locally before this device has run the pass that acts on
     * it, so the raw count can momentarily exceed what is needed.
     */
    @Test
    fun `a count running ahead of the stage is clamped rather than shown`() {
        val ahead = proposal(session(threshold = 2), nonces = 3).progress

        assertEquals(2, ahead?.have, "a row read more than it needed")
        assertEquals(2, ahead?.need)
    }

    @Test
    fun `nothing gathered yet is zero rather than missing`() {
        val fresh = proposal(session(threshold = 2)).progress

        assertEquals(0, fresh?.have)
        assertEquals(2, fresh?.need)
    }

    /** Negative is not reachable, but the clamp is two-sided so it cannot be drawn. */
    @Test
    fun `a count below zero is clamped too`() {
        assertEquals(0, ProposalListUIState.Progress(-1, 2, ProposalListUIState.Progress.Of.MEMBERS).have)
    }
}
