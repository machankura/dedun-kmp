package com.machankura.dedun.compose.managers

import com.vitorpamplona.quartz.nip01Core.core.HexKey

/**
 * How a current-profile room tells its joiners which room it is.
 *
 * A legacy room's id travels in its `0xF2EE` extension as the `nostr_group_id`, and
 * a joiner keys the room on it -- which for a room built around a threshold key is
 * the derived key every member can compute and the room signs as. A current-profile
 * room's `nostr_group_id` is a routing address minted at random (see `MarmotRouting`),
 * so a joiner reading only that would key the room on the address and disagree with
 * the creator, with its own DKG session and with every chronicle, all of which know
 * the room by its derived id.
 *
 * The components carry no field for it, so it rides in the group profile's
 * description, the way [SharedKeyDerivation.describe] already carries the path there:
 * one line, `Room id: <64 hex>`, which [parse] reads back. It is a claim by the
 * creator, exactly as the legacy `nostr_group_id` was; nothing here trusts it further
 * than the tables it keys, and a room with key state behind it checks the derivation
 * for itself in `GroupKeyStateManager.adopt`. See docs/marmot-v2.md, Phase 4.
 */
object MarmotRoomIdentity {
    private const val MARKER = "Room id: "
    private val HEX_64 = Regex("^[0-9a-f]{64}$")

    /** [description] with the room-id line added, unless it already names [roomId]. */
    fun describe(description: String?, roomId: HexKey): String {
        val base = description.orEmpty()
        if (parse(base) == roomId) return base
        return if (base.isBlank()) "$MARKER$roomId" else "$base\n\n$MARKER$roomId"
    }

    /** The room id a description names, or null when it names none or one that is not hex. */
    fun parse(description: String?): HexKey? {
        val line = description
            ?.lineSequence()
            ?.firstOrNull { it.trimStart().startsWith(MARKER) }
            ?: return null
        return line.trimStart().removePrefix(MARKER).trim().lowercase().takeIf { HEX_64.matches(it) }
    }
}
