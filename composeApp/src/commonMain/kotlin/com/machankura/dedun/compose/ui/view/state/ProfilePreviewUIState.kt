package com.machankura.dedun.compose.ui.view.state

import com.machankura.dedun.compose.database.model.Profile

/**
 * The person behind a pasted npub, before a chat with them is started.
 *
 * `Loaded` needs a profile read off a kind 0 -- `Profile.isResolved()` -- and never
 * the "LOADING..." placeholder a pubkey gets on first sight, because the screen's one
 * job is to show who this is. See docs/npub-profile-preview.md.
 */
sealed interface ProfilePreviewUIState {
    data object Loading : ProfilePreviewUIState

    data class Loaded(
        val profile: Profile,
        val readiness: Readiness,
    ) : ProfilePreviewUIState

    /**
     * Every relay asked has had its time, and no kind 0 came. Not an error: a search
     * that found nothing, and one a late answer can still overturn.
     */
    data object NotFound : ProfilePreviewUIState

    /** The queue itself refused: nothing was asked, and asking again is the retry. */
    data object Error : ProfilePreviewUIState

    /**
     * Whether pressing the button would get anywhere, read here rather than twenty
     * seconds into the next screen.
     *
     * A fact about a *loaded* profile: a room does not make a person known, and a
     * member still "LOADING..." waits on their kind 0 here like anyone else.
     */
    sealed interface Readiness {
        /** A conversation with this person exists; the button opens it. */
        data class ExistingChat(
            val chatRoomId: String,
            val relayHint: String?,
        ) : Readiness

        /** Their key package is here; `initiateNewChat` will create the room without waiting. */
        data object CanStartChat : Readiness

        /** Asked, not answered yet. */
        data object Checking : Readiness

        /** The relays have had their time and no key package came. */
        data object NotYetOnDedun : Readiness
    }
}
