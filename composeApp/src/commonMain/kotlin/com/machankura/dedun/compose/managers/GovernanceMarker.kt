package com.machankura.dedun.compose.managers

import com.vitorpamplona.marmot.groups.currentGroupState
import com.vitorpamplona.mls.group.MlsGroup

/**
 * The line in a keyed room's profile description that says its changes need the
 * group's signature, and how few admins it may ever have. docs/member-admin-promotion.md,
 * D4 and D8.
 *
 * In the group's state, so every device that applies the commit adding it enforces
 * the rule from the same epoch, and reads the same floor: a floor each device worked
 * out for itself from the ceremony it happens to hold would differ between a founder
 * and a member welcomed later, and the two would disagree about a commit. The
 * description is the one free text the current profile offers, which is why
 * [MarmotRoomIdentity] and [SharedKeyDerivation.describe] put their lines there too.
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

    /** [description] with the line set to [minimumAdmins], replacing any it already had. */
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
