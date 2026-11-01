package com.machankura.dedun.compose.ui.view.state

import kotlin.time.Clock
import kotlin.time.Instant
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.types.FrostSigningStage
import com.machankura.dedun.compose.managers.FrostSigningManager
import com.machankura.dedun.compose.text.ProposedEvent

sealed interface ProposalListUIState {
    data class Loaded(
        val localChatRoom: LocalChatRoom,

        /** Every proposal the room has made, newest first. */
        val proposals: List<Proposal> = emptyList(),

        /**
         * Which of them the reader has asked to see.
         *
         * Held on the state and not only on the view model, because the chips
         * have to draw the current selection and the counts beside it in the
         * same pass that draws the rows it selected -- a selection the row list
         * disagreed with would be a chip marked for a filter that is not applied.
         */
        val filter: ProposalFilter = ProposalFilter.ALL,
    ): ProposalListUIState {
        /**
         * The ones that cannot move until this member answers them.
         *
         * Split out rather than sorted to the top, because they are a different
         * kind of thing to read: the rest of the list is what the group has done,
         * these are what it is waiting on the reader for. A group can have several
         * at once -- proposing a chapter and its translation is two -- and burying
         * the second one under the first one's history is the whole reason this
         * screen exists.
         */
        val waitingForYou: List<Proposal> = proposals.filter { it.state == ProposalState.NEEDS_YOU }

        val rest: List<Proposal> = proposals.filterNot { it.state == ProposalState.NEEDS_YOU }

        /**
         * The rows [filter] actually asks for.
         *
         * [proposals] is left whole beside it, because the counts on the chips
         * are counts of the room and not of the selection -- a chip that read
         * its own number off the filtered list would say 0 for every filter but
         * the chosen one.
         */
        val visible: List<Proposal> = when (val only = filter.state) {
            null -> proposals
            else -> proposals.filter { it.state == only }
        }

        /**
         * How many proposals are in each state, for the number on each chip.
         *
         * Every state is present, zero included: a room that has never
         * abandoned a proposal still gets an "abandoned" chip reading 0, because
         * a chip row that gains and loses chips as sessions advance moves under
         * the thumb of the person reading it -- and sessions advance while this
         * screen is open, which is the whole reason the screen exists.
         */
        val counts: Map<ProposalState, Int> = ProposalState.entries.associateWith { state ->
            proposals.count { it.state == state }
        }

        /**
         * The rows on screen that one tap could ask again about.
         *
         * Drawn from [visible] and not from [proposals], because the offer is to
         * ask again about *these* -- the ones the filter put in front of the
         * reader. Asking about proposals they cannot see would be the bulk
         * control's one unforgivable move.
         *
         * A proposal inside its cooldown is left out rather than included and
         * skipped. `resend` would decline it harmlessly either way, but the
         * count is what the button says out loud, and a button offering three
         * when it can only send two is a button that lies before it is pressed.
         */
        val askableVisible: List<Proposal> =
            visible.filter { it.canAskAgain && it.askAgainAt == null }

        /**
         * Whether to offer asking about all of them at once.
         *
         * Two, because one is what the row's own button is for: a bulk control
         * over a single proposal is a second way to do the same thing, in a
         * place further from the row it affects.
         */
        val canAskAll: Boolean = askableVisible.size >= 2
    }

    /**
     * One proposal, as a row.
     *
     * Everything here is derived once per emission rather than at render: the
     * events are parsed out of stored JSON, which is not work to repeat on every
     * recomposition of a scrolling list.
     */
    data class Proposal(
        val session: FrostSigningSession,

        /** Whoever put it to the group, as a name to read. */
        val proposerName: String,

        /**
         * What it signs, in the order the batch fixed -- so the first is the one
         * the others hang off, and is what the row is named after.
         */
        val signs: List<ProposedEvent.Summary> = emptyList(),

        /**
         * How many of its events could not be read back.
         *
         * Shown rather than hidden. A batch is all-or-nothing, so an unreadable
         * item is a reason not to sign any of it, and a row that quietly listed
         * only the readable ones would be understating what is being asked.
         */
        val unreadable: Int = 0,

        /** Whether the session is still waiting on this device's owner. */
        val awaitsYou: Boolean = false,

        /**
         * How many share-holding members have offered a nonce, and how many
         * have published their part of the signature.
         *
         * Counts of members, not of messages, and only of members who hold a
         * share -- see `FrostSignerTally`, which is where both restrictions are
         * imposed and explained.
         */
        val noncesOffered: Int = 0,
        val partialSignatures: Int = 0,

        /**
         * How many share holders the session is still waiting on, or null when
         * asking them again is not this device's to offer.
         *
         * Asked of `FrostSigningManager.membersOwing` rather than worked out
         * from the counts above, because the two are not the same question and
         * the difference is a button that does nothing. Past the nonces a signer
         * who refused has answered and is owed nothing, while the signature
         * count is still short by one -- so a row reading `have < need` would
         * offer to knock on a door that has already been shut.
         *
         * Null rather than zero for a session this device did not propose: a
         * resend from anybody else would have a member who was missing the
         * session believe the resender coordinates. Zero means it is ours to
         * send and there is nobody left to send it to.
         */
        val owing: Int? = null,
    ) {
        /** The batch's size, readable or not. */
        val eventCount: Int get() = signs.size + unreadable

        /**
         * Where it has got to, in the terms the screen reads it in.
         *
         * Asked once here rather than at each of the four places that used to
         * work it out for themselves -- the section split, the row's icon, that
         * icon's description, and the status line. They were four `when` chains
         * over the same two facts, and the chips would have been a fifth; the
         * way a chip comes to count a proposal the row beneath it describes
         * differently is by asking the question again.
         *
         * [awaitsYou] is tried first and wins, because it is the one state that
         * is not a stage: an open session waiting on this member and an open
         * session waiting on everybody else are both `COLLECTING_NONCES`.
         *
         * The inner `when` is exhaustive on purpose, with no `else`. A stage
         * added to `FrostSigningStage` later should fail to compile here rather
         * than quietly land in whichever arm happened to be last, because what
         * the reader is waiting on is the one thing this file cannot guess.
         */
        val state: ProposalState get() = when {
            awaitsYou -> ProposalState.NEEDS_YOU

            else -> when (session.stage) {
                FrostSigningStage.COLLECTING_NONCES -> ProposalState.WAITING_ON_MEMBERS
                FrostSigningStage.COLLECTING_PARTIAL_SIGNATURES -> ProposalState.BEING_SIGNED
                FrostSigningStage.COMPLETE -> ProposalState.SIGNED
                FrostSigningStage.FAILED -> ProposalState.ABANDONED
            }
        }

        /**
         * How far the thing it is currently gathering has got, or null when it
         * is gathering nothing.
         *
         * Keyed on the stage rather than on [state], because the stage is what
         * decides *which* count is the live one and [ProposalState.NEEDS_YOU]
         * overlays both of the gathering stages. A proposal can need the
         * reader's answer while the group is on either, and in both cases the
         * figure it wants is the one for the stage it is actually in.
         *
         * Null once it has ended, which is both of the endings and not only the
         * happy one. A signed session has nothing left to gather; an abandoned
         * one has counts frozen where they stopped, and "2 of 3" beside a
         * failure invites the reader to wait for a third that is never coming.
         * The failure reason is what that row has to say.
         */
        val progress: Progress? get() = when (session.stage) {
            // Enough to aggregate is the threshold, not the whole room --
            // `orderedNonces` gates on exactly this and takes the first t of
            // them, so t is both what is needed and what will be used.
            FrostSigningStage.COLLECTING_NONCES ->
                Progress(noncesOffered, session.threshold, Progress.Of.MEMBERS)

            // The signer set once it exists, and the threshold until it does --
            // they are the same number, since the set is the first t offers, but
            // the set is the one this stage is actually waiting on.
            FrostSigningStage.COLLECTING_PARTIAL_SIGNATURES -> Progress(
                partialSignatures,
                session.signerIdList()?.size ?: session.threshold,
                Progress.Of.SIGNATURES
            )

            FrostSigningStage.COMPLETE, FrostSigningStage.FAILED -> null
        }

        /**
         * Whether to offer asking the quiet members to take another look.
         *
         * The same offer the signing screen makes, reached from the row rather
         * than from a tap into it -- a coordinator scanning a list for the one
         * that has stalled should not have to open each to find the button. It
         * is offered only when there is somebody to ask, because a control that
         * publishes nothing is worse than no control.
         */
        val canAskAgain: Boolean get() = (owing ?: 0) > 0

        /**
         * When the next ask may go out, or null when one may go out now.
         *
         * Read at composition rather than ticked, exactly as the signing screen
         * reads it. The cooldown is five minutes and every message the room
         * exchanges recomposes this screen, so a button left disabled a little
         * past its time is the worst this costs -- and that is the safe
         * direction for a control that publishes.
         */
        val askAgainAt: Instant? get() = session.resentAt
            ?.plus(FrostSigningManager.RESEND_COOLDOWN)
            ?.takeIf { it > Clock.System.now() }
    }

    /**
     * How much of what a proposal is waiting for has arrived.
     *
     * [have] can exceed [need] for a moment: a member's nonce lands locally
     * before this device has run `advance` and moved the session on, so a row
     * can read "3 of 2" between a delivery and the pass that acts on it. Clamped
     * rather than shown, because a fraction over one reads as a bug in the app
     * rather than as a session about to advance.
     */
    data class Progress(
        private val arrived: Int,
        val need: Int,
        val of: Of,
    ) {
        val have: Int = arrived.coerceIn(0, need)

        /** What is being counted, which decides the word the row uses. */
        enum class Of { MEMBERS, SIGNATURES }
    }

    data class Error(
        val message: String
    ): ProposalListUIState

    data object Loading: ProposalListUIState
}
