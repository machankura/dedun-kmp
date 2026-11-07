package com.machankura.dedun.compose.managers

/** What this device's daemon for a group's Wavelength wallet is doing, for the screen. Only ever about this device. */
sealed interface GroupWavelengthDaemonStatus {
    data object NotOnThisDevice : GroupWavelengthDaemonStatus

    /** Elected, and waiting to hear from the room before starting: a device back after a week catches up first. */
    data object CatchingUp : GroupWavelengthDaemonStatus

    data object WaitingForSigners : GroupWavelengthDaemonStatus

    data object Opening : GroupWavelengthDaemonStatus

    /** A first open on this device, which rebuilds from the chain at a cost in the group's signatures (D14). */
    data object Recovering : GroupWavelengthDaemonStatus

    data class Running(val term: Long) : GroupWavelengthDaemonStatus

    /** The person is in their own wallet, which has the daemon until they leave it (D13). */
    data object PausedForPersonal : GroupWavelengthDaemonStatus

    /** Elected here too, but the one daemon is running [group]'s wallet (D13). */
    data class BusyWithAnother(val group: String) : GroupWavelengthDaemonStatus

    data class Refused(val reason: GroupWavelengthDaemon.Refusal) : GroupWavelengthDaemonStatus

    data object Failed : GroupWavelengthDaemonStatus
}
