package com.machankura.dedun.compose.repository

/**
 * A room a group's ceremony made, as a NIP-17 room's members find it: where they are
 * admins of one of the groups they have made together.
 *
 * The same people share one NIP-17 room however many groups they make -- its id is
 * derived from them -- so a room can have any number of these
 * (docs/nip17-multiple-ceremonies.md).
 *
 * [name] is the admin room's own, as this device holds it, or the name its ceremony
 * was opened under when the room has none.
 */
data class AdminRoom(
    val name: String?,
    val chatRoomId: String,
)
