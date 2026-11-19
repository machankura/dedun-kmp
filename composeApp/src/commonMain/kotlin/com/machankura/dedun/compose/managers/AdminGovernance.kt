package com.machankura.dedun.compose.managers

import com.vitorpamplona.marmot.groups.MarmotGroupPermissions
import com.vitorpamplona.marmot.groups.currentAdminIdentities
import com.vitorpamplona.mls.group.MlsGroup
import com.vitorpamplona.quartz.nip01Core.core.HexKey

/**
 * Who may change who governs a room, in one place, so the screen that offers an
 * action and the code that commits it cannot disagree. See
 * docs/member-admin-promotion.md.
 *
 * Every answer is read off the group's state -- its admin set and its tree --
 * never off `Participant.adminAt`, which is a cache of it (D1). The permission
 * itself is the library's, [MarmotGroupPermissions], which is what every peer
 * enforces on a commit; this adds the two things it leaves to the application.
 */
object AdminGovernance {

    /**
     * A room around a group's threshold key: the admin room a robust group opens,
     * or a subgroup's. Its description records the path the key was walked to
     * ([SharedKeyDerivation.parsePath]), which a convenient room or a direct
     * message never has.
     *
     * Such a room's admins change only with the group's signature on the change,
     * and only alongside a share of the key (R1, R2), neither of which is built.
     * Until it is, a keyed room's admin list is changed by nothing here.
     */
    fun isKeyedRoom(group: MlsGroup): Boolean = SharedKeyDerivation.parsePath(group.roomDescription()) != null

    /** The accounts holding a leaf in [group] now. */
    fun members(group: MlsGroup): Set<HexKey> = group.currentMemberIdentities()

    /**
     * Whether [actor] may make [target] an admin of [group] by a single commit.
     *
     * The library's rule, plus that [target] holds a leaf: `canPromote` does not
     * ask, and an admin entry for an account with no leaf is the state
     * `AdminPolicyV1` says is never valid.
     */
    fun canPromote(group: MlsGroup, actor: HexKey, target: HexKey): Boolean =
        !isKeyedRoom(group) &&
            target in members(group) &&
            MarmotGroupPermissions.canPromote(group.currentAdminIdentities(), actor, target)

    /**
     * Whether [actor] may make [target] stop being an admin of [group] by a single
     * commit. The library's rule, which already refuses a demotion that would
     * leave no admin among the members; [actor] and [target] may be the same
     * account, which is stepping down.
     */
    fun canDemote(group: MlsGroup, actor: HexKey, target: HexKey): Boolean =
        !isKeyedRoom(group) &&
            MarmotGroupPermissions.canDemote(group.currentAdminIdentities(), members(group), actor, target)
}
