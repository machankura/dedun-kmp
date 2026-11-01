package com.machankura.dedun.compose.ui.view.state

/**
 * Where a proposal has got to, as a reader scanning a list of them cares.
 *
 * Deliberately not [com.machankura.dedun.compose.database.model.types.FrostSigningStage],
 * although it has come to be the stage plus one. The one is the point: a
 * proposal waiting on *this* member is `COLLECTING_NONCES` like every other open
 * one, and what sets it apart is `FrostSigningSession.signApprovedAt` and
 * `signDeclinedAt` being null. So [NEEDS_YOU] cannot be read off the stage at
 * all, and a filter keyed on the enum would be a filter missing its reason to
 * exist.
 *
 * Exhaustive and in lifecycle order, which is the order the chips are offered
 * in.
 */
enum class ProposalState {
    /**
     * Waiting on this member to agree or refuse, and going nowhere until they do.
     *
     * The same question the signing screen gates its buttons on -- see
     * `ProposalListUIState.Proposal.awaitsYou`, which is
     * `FrostSigningManager.isAwaitingApproval` and not a second opinion of it.
     */
    NEEDS_YOU,

    /**
     * Open, answered by this member, and short of members willing to sign it.
     *
     * The stage where a proposal can still die of indifference. Refusals are
     * counted against `FrostSigningSession.declinesThatAbandon`, so one more may
     * end it; and a member who is simply offline is indistinguishable from one
     * who has not looked. It is also the only stage the coordinator's resend is
     * for, which is the practical reason this is not merged with
     * [BEING_SIGNED]: these are the proposals somebody may have to do something
     * about.
     */
    WAITING_ON_MEMBERS,

    /**
     * The signer set is fixed, and the chosen signers are producing signatures.
     *
     * Nothing anybody can be asked here. `signerIds` is written once and never
     * revised, so no further refusal is counted against it -- what is left is
     * the signers' devices finishing arithmetic they have already committed to.
     * Where [WAITING_ON_MEMBERS] asks whether this will happen, this one says it
     * is happening.
     *
     * Whether *this* device was among the chosen is not a state of the proposal
     * but a fact about the reader, and the row's status line says it. Splitting
     * on it would be splitting every state by who is looking.
     */
    BEING_SIGNED,

    /** Aggregated and verified. The group signed it. */
    SIGNED,

    /** Abandoned. `FrostSigningSession.failureReason` says why, when it knows. */
    ABANDONED,
}
