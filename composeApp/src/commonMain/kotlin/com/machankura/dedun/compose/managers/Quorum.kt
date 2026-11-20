package com.machankura.dedun.compose.managers

/**
 * How many of a group's members have to agree, and how big a group has to be for
 * that to mean anything.
 *
 * Every group a member makes is run on a t-of-n key its members generate together
 * (docs/remove-convenient-groups.md), and these are the rules for `t` and `n`.
 * [ChillDkgRitualManager] enforces them, on the proposing side and again on every
 * device a proposal reaches; the screens that ask for a quorum offer only what it
 * will accept.
 */
object Quorum {
    /**
     * Fewest people a group can be made with, its creator included.
     *
     * Below three a quorum is not a check. Two means every change needs both of
     * them, so either can freeze the group by going quiet; one means the creator
     * is deciding alone. Two people who want to talk have a direct message.
     */
    const val MINIMUM_GROUP_SIZE = 3

    /**
     * Fewest approvals a change can be made on. One approval is not a quorum -- it
     * is one person acting alone.
     */
    const val MINIMUM = 2

    /**
     * The quorum a group of [memberCount] starts on: a simple majority, so no half
     * of the group can move without the other. The member making it can move it
     * anywhere in [range] from there.
     */
    fun default(memberCount: Int): Int = maxOf(MINIMUM, memberCount / 2 + 1)

    /**
     * Quorums a group of [memberCount] can be run on: never fewer than [MINIMUM],
     * never more than the members who exist to approve.
     */
    fun range(memberCount: Int): IntRange = MINIMUM..memberCount

    /** Whether a group of [memberCount] people, its creator included, can be made. */
    fun isPossible(memberCount: Int): Boolean = memberCount >= MINIMUM_GROUP_SIZE

    /**
     * The fewest admins a group may ever have: [MINIMUM_GROUP_SIZE], or its quorum
     * [threshold] when that is higher. In a group around a key the admins are its
     * share holders, so fewer than [threshold] of them is a key that can never sign
     * again -- and nothing about the group could change after that. Null [threshold]
     * means this device does not know it, and the size floor alone applies.
     * docs/member-admin-promotion.md, R3 and D8.
     */
    fun minimumAdmins(threshold: Int?): Int = maxOf(MINIMUM_GROUP_SIZE, threshold ?: 0)
}
