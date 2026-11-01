package com.machankura.dedun.compose.database.model.types

/**
 * What a replay of a room's stored kind:445 events did.
 *
 * Nothing here is persisted -- it exists so the screen that asked for the replay
 * can say what happened rather than leaving the user to guess from the message
 * list whether anything moved.
 *
 * @param stored every kind:445 held locally for the room, [predatingMembership]
 *  included. They are held, so they are counted.
 * @param predatingMembership how many of [stored] the group published before this
 *  device joined, which a replay does not touch -- see
 *  `ChatRoom.predatesMembership`. Reported rather than folded into [stored]
 *  silently, because "20 events, all read" is not true of a room where 15 of them
 *  were never this device's to read, and a member who was invited into an old
 *  room deserves the difference said out loud rather than left as a discrepancy.
 * @param unresolved how many of the rest had nothing to show for them, or only a
 *  placeholder line -- the ones a replay was allowed to touch.
 * @param recovered how many of [unresolved] came out with something to show:
 *  a message, an applied commit, an entity added to the group's library.
 * @param failed how many threw while being replayed.
 */
data class MarmotReindexReport(
    val stored: Int = 0,
    val predatingMembership: Int = 0,
    val unresolved: Int = 0,
    val recovered: Int = 0,
    val failed: Int = 0,
) {
    /** Nothing was left to try, so the replay was a no-op. */
    val isNoOp: Boolean get() = unresolved == 0
}
