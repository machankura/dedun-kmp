package com.machankura.dedun.compose.ui.view.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.FrostSigningItem
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.Participant
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.intermdiate.LocalParticipant
import com.machankura.dedun.compose.database.model.types.FrostSigningStage
import com.machankura.dedun.compose.managers.FrostSigningManager

/**
 * When the signing screen offers to ask the members it is waiting on again.
 *
 * *Who* those members are is not decided here. That rule is the manager's, it is
 * not the same rule at every stage -- a nonce answers the question while nonces
 * are being collected and not after -- and the same reading decides whether a
 * resend publishes anything, so a second one here would be a second thing to
 * keep in step: a screen offering a knock the manager would refuse, or hiding
 * one it would make. See `FrostSigningManager.membersOwing` and the tests over
 * it in `FrostSigningResendJvmTest`.
 *
 * What is left here is when the offer stands at all, and the limits are what it
 * pins. The offer is the coordinator's and nobody else's: `acceptProposal` takes
 * a session's coordinator from the author of the proposal that opened it, so a
 * resend from anybody else would have a member who was missing the session
 * believe the resender coordinates -- and then drop the real coordinator's
 * signer set.
 */
class FrostSigningResendStateTest {
    private val roomId = "11".repeat(32)

    private val alice = "a".repeat(64)
    private val bob = "b".repeat(64)
    private val carol = "c".repeat(64)

    private val holders = setOf(alice, bob, carol)

    private fun room(owner: String) = LocalChatRoom(
        chatRoom = ChatRoom(
            id = roomId,
            userPublicKey = owner,
            subject = "#Ekklesia",
            description = null,
            mlsGroupState = null,
        ),
        localParticipants = holders.sorted().map { publicKey ->
            LocalParticipant(
                participant = Participant(
                    participantPublicKey = publicKey,
                    chatRoomId = roomId,
                    relayHint = null,
                ),
                profile = null,
            )
        },
    )

    private fun session(
        owner: String = alice,
        coordinator: String = alice,
        stage: FrostSigningStage = FrostSigningStage.COLLECTING_NONCES,
        resentAt: kotlin.time.Instant? = null,
    ) = FrostSigningSession(
        id = "s".repeat(64),
        chatRoomId = roomId,
        coordinatorPublicKey = coordinator,
        userPublicKey = owner,
        dkgSessionId = "ceremony".padEnd(64, '0'),
        threshold = 2,
        participantCount = 3,
        signerId = 0,
        resentAt = resentAt,
        stage = stage,
    )

    private val item = FrostSigningItem(
        sessionId = "s".repeat(64),
        itemIndex = 0,
        unsignedEventJson = "{}",
        eventId = "e".repeat(64),
        nonceRandom = "f".repeat(64),
    )

    private fun loaded(
        owner: String = alice,
        coordinator: String = alice,
        stage: FrostSigningStage = FrostSigningStage.COLLECTING_NONCES,
        resentAt: kotlin.time.Instant? = null,
        offeredNonce: Set<String> = emptySet(),
        signed: Set<String> = emptySet(),
        declined: Set<String> = emptySet(),
        items: List<FrostSigningItem> = listOf(item),
        silent: Set<String> = setOf(bob, carol),
    ) = FrostSigningUIState.Loaded(
        localChatRoom = room(owner),
        session = session(owner, coordinator, stage, resentAt),
        items = items,
        shareHolders = holders,
        offeredNonce = offeredNonce,
        signed = signed,
        declined = declined,
        silent = silent,
    )

    @Test
    fun `the offer follows the manager's answer rather than the rows`() {
        // Every share holder has answered as far as the rows above the button
        // can tell -- and the manager says Bob still owes a partial signature,
        // which is what a session past the nonces waits on. The screen takes
        // that answer; working one out from the rows is how the knock came to be
        // refused exactly when it was needed.
        val state = loaded(
            stage = FrostSigningStage.COLLECTING_PARTIAL_SIGNATURES,
            offeredNonce = setOf(bob, carol),
            silent = setOf(bob),
        )

        assertEquals(setOf(bob), state.silent)
        assertTrue(state.canResend)
    }

    @Test
    fun `the coordinator is offered the resend`() {
        assertTrue(loaded().canResend)
    }

    @Test
    fun `a member who did not propose is not offered it`() {
        // Correctness rather than etiquette -- see the class note.
        assertFalse(loaded(owner = bob, coordinator = alice).canResend)
    }

    @Test
    fun `a session waiting on nobody has nothing to resend`() {
        val state = loaded(offeredNonce = setOf(bob), declined = setOf(carol), silent = emptySet())

        assertFalse(state.canResend)
    }

    @Test
    fun `an abandoned session is not resent`() {
        // Its items' nonce seeds have been published against an aggregate
        // already. The retry is a fresh proposal, never this one again.
        assertFalse(loaded(stage = FrostSigningStage.FAILED).canResend)
    }

    @Test
    fun `a signed session can still be resent`() {
        // A member who never received the signature never applied the events.
        assertTrue(loaded(stage = FrostSigningStage.COMPLETE).canResend)
    }

    @Test
    fun `a session with no items yet is not resent`() {
        assertFalse(loaded(items = emptyList()).canResend)
    }

    @Test
    fun `a session not yet collected offers nothing`() {
        val state = FrostSigningUIState.Loaded(localChatRoom = room(alice))

        assertFalse(state.canResend)
        assertNull(state.resendAvailableAt)
    }

    @Test
    fun `a session never resent may be resent now`() {
        assertNull(loaded().resendAvailableAt)
    }

    @Test
    fun `a session just resent says when it may go again`() {
        val now = Clock.System.now()
        val state = loaded(resentAt = now)

        // Read by the screen to disable the button rather than hide the section:
        // a control that vanishes after a tap reads as one that failed.
        val availableAt = state.resendAvailableAt
        assertNotNull(availableAt)
        assertEquals(now + FrostSigningManager.RESEND_COOLDOWN, availableAt)
        assertTrue(state.canResend, "the offer stands; it is the button that goes quiet")
    }

    @Test
    fun `a session resent long ago may be resent again`() {
        val state = loaded(
            resentAt = Clock.System.now() - FrostSigningManager.RESEND_COOLDOWN * 2
        )

        assertNull(state.resendAvailableAt)
    }
}
