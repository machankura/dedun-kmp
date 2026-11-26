package com.machankura.dedun.compose.repository

/**
 * One group a NIP-17 room's members have made in it, as the room's details list it.
 *
 * The same people share one NIP-17 room however many groups they make -- its id is
 * derived from them -- so the room is where each group's ceremony was held rather
 * than the group itself. Each of these is one ceremony, and once it has a key, the
 * room that key makes (docs/nip17-multiple-ceremonies.md).
 *
 * [name] is the name the ceremony was opened under, or the room's for a ceremony
 * from before names were carried.
 */
data class GroupMadeHere(
    val dkgSessionId: String,
    val name: String?,
    val status: Status,
) {
    sealed interface Status {
        /** Its ceremony is still running. */
        data object KeyCeremonyRunning : Status

        /** It has its key, and the room the key makes is not on this device yet. */
        data object WaitingForRoom : Status

        /** Its room, [chatRoomId], is on this device. */
        data class Ready(val chatRoomId: String) : Status
    }
}
