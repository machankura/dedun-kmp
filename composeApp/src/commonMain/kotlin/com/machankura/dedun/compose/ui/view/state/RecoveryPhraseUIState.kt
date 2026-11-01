package com.machankura.dedun.compose.ui.view.state

/**
 * What the recovery phrase screen is showing.
 *
 * The phrase is never held here longer than the user is looking at it:
 * [Revealed] is the only state that carries words, and leaving the screen (or
 * hiding the phrase) drops it back to [Hidden].
 */
sealed interface RecoveryPhraseUIState {
    /** Nothing secret on screen; the user has not asked to see the phrase yet. */
    data object Hidden: RecoveryPhraseUIState

    /** Reading and decrypting the seed file. */
    data object Revealing: RecoveryPhraseUIState

    data class Revealed(
        val words: List<String>
    ): RecoveryPhraseUIState

    sealed interface Error: RecoveryPhraseUIState {
        /** No wallet has been started, so there is no seed to read for it. */
        data object NoActiveWallet: Error

        /** The seed file could not be read or decrypted. */
        data object SeedUnreadable: Error

        /** The seed file was read, but holds no words for the active wallet. */
        data object NoPhraseForThisWallet: Error
    }
}
