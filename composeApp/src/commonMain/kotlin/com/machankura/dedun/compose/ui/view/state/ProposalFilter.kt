package com.machankura.dedun.compose.ui.view.state

/**
 * Which proposals the reader has asked to see.
 *
 * A flat enum rather than `All` plus a [ProposalState], because every filter
 * this screen has is a state and the wrapper would be a type to unwrap at every
 * call site for no fact it could hold. [state] is null on [ALL] and that is the
 * whole of the difference.
 *
 * Offered in this order, which is [ProposalState]'s order behind [ALL]: most
 * asked of the reader first, because the reason to come here with a filter in
 * mind is almost always [NEEDS_YOU].
 */
enum class ProposalFilter(val state: ProposalState?) {
    ALL(null),
    NEEDS_YOU(ProposalState.NEEDS_YOU),
    WAITING_ON_MEMBERS(ProposalState.WAITING_ON_MEMBERS),
    BEING_SIGNED(ProposalState.BEING_SIGNED),
    SIGNED(ProposalState.SIGNED),
    ABANDONED(ProposalState.ABANDONED),
    ;

    companion object {
        /** The filter showing exactly one state, for deep links and tests. */
        fun of(state: ProposalState): ProposalFilter = entries.first { it.state == state }
    }
}
