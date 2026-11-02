package com.machankura.dedun.compose.ui.view.state

/**
 * What the nostr secret key screen is showing. The key is never held here longer than
 * the user is looking at it: [Revealed] is the only state that carries it, and leaving
 * the screen (or hiding it) drops back to [Hidden]. The shape of [RecoveryPhraseUIState],
 * for the identity kind that has a key and no phrase.
 */
sealed interface NostrSecretUIState {
    data object Hidden : NostrSecretUIState

    /** Reading and decrypting the key file. */
    data object Revealing : NostrSecretUIState

    data class Revealed(val nsec: String) : NostrSecretUIState

    sealed interface Error : NostrSecretUIState {
        /** No identity is active, so there is nothing to look up. */
        data object NoActiveIdentity : Error

        /** The key file was read, but holds no key for the active identity -- it is a mnemonic identity, or the key was forgotten. */
        data object NoKeyForThisIdentity : Error

        /** The key file could not be read or decrypted. */
        data object KeysUnreadable : Error
    }

    /** The forget flow's own states, alongside whatever the reveal is showing. */
    sealed interface Forgetting {
        data object Idle : Forgetting
        data object Confirming : Forgetting
        data object InProgress : Forgetting
        data object Failed : Forgetting

        /** Refused: the profile has an Ark wallet of its own (`docs/bark-wallet.md`, D13). */
        data object ArkWalletAttached : Forgetting
    }
}
