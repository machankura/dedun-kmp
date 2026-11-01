package com.machankura.dedun.compose.ui.view.state

import fr.acinq.phoenix.data.WalletId
import com.machankura.dedun.compose.identity.CredentialProblem
import com.machankura.dedun.compose.identity.SignInCredential

/**
 * The sign-in screen's four states. Problems with the *input* are not one of them: those
 * stay on the field as supporting text while the prompt is still showing. [Error] is for
 * what happens after the user has confirmed -- the write failed, or the key was already
 * here -- where there is no field to put the message under.
 */
sealed interface SignInToProfileUIState {
    /** The field, waiting for a recovery phrase or an nsec. */
    data object InputPrompt : SignInToProfileUIState

    /**
     * What was recognised, and the npub it signs as, before anything is written. This is
     * the step that catches a paste of the wrong key; for a recovery phrase it is where
     * the user learns which profile the words open. The secret itself is never shown.
     */
    data class Confirm(val credential: SignInCredential) : SignInToProfileUIState

    /** Writing the secret and planting the account. */
    data object Committing : SignInToProfileUIState

    /**
     * @param alreadyHere for [CredentialProblem.AlreadyOnThisDevice], the id the key is
     *   listed under: "already on this device" is a sentence with an obvious next step,
     *   and from inside the app the screen offers it.
     */
    data class Error(val problem: CredentialProblem, val alreadyHere: WalletId? = null) : SignInToProfileUIState
}
