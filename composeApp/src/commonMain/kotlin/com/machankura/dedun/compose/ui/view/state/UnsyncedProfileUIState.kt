package com.machankura.dedun.compose.ui.view.state

/** What the screen waiting on a sign-in sync is showing. */
sealed interface UnsyncedProfileUIState {
    /** At least one relay has not finished answering, or something has arrived and the route is about to move. */
    data object Searching : UnsyncedProfileUIState

    /** Every relay asked has finished, and none had a profile for this key. */
    data class NotFound(val unsignedNostrEventId: Long) : UnsyncedProfileUIState
}
