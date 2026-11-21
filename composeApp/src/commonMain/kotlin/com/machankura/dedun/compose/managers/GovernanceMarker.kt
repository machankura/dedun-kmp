package com.machankura.dedun.compose.managers

import com.vitorpamplona.quartz.marmot.mls.group.MlsGroup

/**
 * The line in a keyed room's profile description that says its changes need the
 * group's signature, and how few admins it may ever have.
 *
 * Giza writes it into the epoch-0 description of every keyed room it makes, and from
 * then on its devices apply a commit to that room only from inside a group-signed
 * envelope: a bare commit is dropped. Dedun cannot yet make or unwrap those envelopes
 * (docs/giza-into-dedun.md, Phase 8), so a bare commit from a Dedun device in such a
 * room moves Dedun's devices one epoch and leaves Giza's behind -- the room forks,
 * and the members on each side stop hearing the other.
 *
 * Until Phase 8, Dedun reads the line and stops making commits in a room that carries
 * it: [MarmotOutboundDao][com.machankura.dedun.compose.database.dao.MarmotOutboundDao]
 * refuses an invite, a removal and a disband there, and does not commit a leaver's
 * proposal. Dedun never writes the line.
 *
 * The wording is Giza's, byte for byte (Giza's `GovernanceMarker`), because it is the
 * only thing on the wire that says a room is governed.
 */
object GovernanceMarker {
    private const val PREFIX = "Changes need the group's signature; at least "
    private const val SUFFIX = " admins."
    private val LINE = Regex("^" + Regex.escape(PREFIX) + "(\\d{1,3})" + Regex.escape(SUFFIX) + "$")

    /** The floor [description] sets, or null when it does not carry the line. */
    fun minimumAdmins(description: String?): Int? =
        description?.lineSequence()
            ?.firstNotNullOfOrNull { LINE.find(it.trim())?.groupValues?.get(1)?.toIntOrNull() }
            ?.takeIf { it >= 1 }

    /** [description] with the line set to [minimumAdmins]. Only tests write it here. */
    fun describe(description: String?, minimumAdmins: Int): String {
        val kept = description.orEmpty().lines().filterNot { LINE.matches(it.trim()) }.joinToString("\n").trimEnd()
        val line = "$PREFIX$minimumAdmins$SUFFIX"
        return if (kept.isBlank()) line else "$kept\n\n$line"
    }
}

/**
 * Whether this group's changes need the group's signature, as Giza reads it: a
 * current-profile room keyed to a group's threshold key, whose description carries
 * [GovernanceMarker]'s line. Giza checks the line only on current-profile rooms, so a
 * legacy room is never governed, whatever its description says.
 */
fun MlsGroup.isGoverned(): Boolean {
    if (!currentGroupState().isCurrentProfile) return false
    val description = roomDescription()
    return SharedKeyDerivation.parsePath(description) != null &&
        GovernanceMarker.minimumAdmins(description) != null
}

/**
 * Whether a commit by this device would fork the room: it is governed, and it has
 * other members to fork from. A governed room's founding invite is its creator's
 * commit while it is the only member, which no other device processes; Giza makes it
 * bare too.
 */
fun MlsGroup.refusesBareCommits(): Boolean = isGoverned() && members().size > 1
