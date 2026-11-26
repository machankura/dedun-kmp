package com.machankura.dedun.compose.ui.view.state

import com.machankura.dedun.compose.identity.recoverycode.RecoveryCode

/**
 * What the recovery code screen is showing. As with the phrase, the code is held only while
 * the person is looking at it: [Revealed] is the only state that carries it.
 */
sealed interface RecoveryCodeUIState {
    /** Nothing secret on screen. */
    data object Hidden : RecoveryCodeUIState

    /** Reading and decrypting the codes file. */
    data object Revealing : RecoveryCodeUIState

    /**
     * The code, and -- once asked for, behind a reveal of their own -- the 24 words it is,
     * for apps that take a recovery phrase.
     */
    data class Revealed(val code: RecoveryCode, val words: List<String>? = null) : RecoveryCodeUIState {
        override fun toString(): String = "Revealed(***)"
    }

    sealed interface Error : RecoveryCodeUIState {
        /** No profile is open, so there is no code to read for it. */
        data object NoActiveProfile : Error

        /** The codes file could not be read or decrypted. The phrase in seed.dat still can be. */
        data object CodesUnreadable : Error

        /** The codes file holds no code for the open profile. */
        data object NoCodeForThisProfile : Error
    }
}

/** Where the two-block check of a written-down code stands. */
sealed interface BackupCheckState {
    data object Idle : BackupCheckState
    data object Checking : BackupCheckState

    /** The blocks typed are not the code's. */
    data object Mismatch : BackupCheckState

    /** They are; the backup is recorded as done. */
    data object Matched : BackupCheckState

    /** The code could not be read to check against. */
    data object Unreadable : BackupCheckState
}
