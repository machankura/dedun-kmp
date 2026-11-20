package com.machankura.dedun.compose.managers

import com.vitorpamplona.marmot.groups.MarmotGroupPermissions
import com.vitorpamplona.marmot.groups.currentAdminIdentities
import com.vitorpamplona.mls.group.MlsGroup
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.machankura.dedun.compose.ExperimentalFeatures
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.types.ChatRoomType
import com.machankura.dedun.compose.database.model.types.DkgRitualStage
import com.machankura.dedun.compose.ui.view.state.AdminLeave
import com.machankura.dedun.compose.ui.view.state.MemberAction

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

    /**
     * The fewest admins [chatRoomId] may be left with: one in a convenient room,
     * where the library's depletion guard is the whole rule, and
     * [Quorum.minimumAdmins] of the room's quorum in a keyed one (R3). The
     * quorum is the completed ceremony's; a device that holds none -- a member
     * welcomed after the founding -- applies the size floor alone.
     */
    suspend fun adminFloor(database: DedunDatabase, chatRoomId: String, group: MlsGroup): Int {
        if (!isKeyedRoom(group)) return 1
        val key = GroupKeyStateManager.keyStateFor(database, chatRoomId)?.dkgSessionId
            ?.let { database.dkgSessionDao().getSessionById(it) }
            ?.takeIf { it.stage == DkgRitualStage.COMPLETE }
            ?: FrostSigningManager.completedKey(database, chatRoomId)
        return Quorum.minimumAdmins(key?.threshold)
    }

    /**
     * Whether taking [target]'s admin rights away -- by demotion or by removing
     * them -- leaves [group] at least [floor] admins among its members. True for
     * someone who is not an admin, whose going changes nothing.
     */
    fun keepsFloor(group: MlsGroup, target: HexKey, floor: Int): Boolean {
        val activeAdmins = group.currentAdminIdentities() intersect members(group)
        return target !in activeAdmins || activeAdmins.size - 1 >= floor
    }

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
        (!isKeyedRoom(group) || keyedChangesAllowed(group)) &&
            target in members(group) &&
            MarmotGroupPermissions.canPromote(group.currentAdminIdentities(), actor, target)

    /**
     * Whether [actor] may make [target] stop being an admin of [group] by a single
     * commit. The library's rule, which already refuses a demotion that would
     * leave no admin among the members; [actor] and [target] may be the same
     * account, which is stepping down.
     */
    fun canDemote(group: MlsGroup, actor: HexKey, target: HexKey): Boolean =
        (!isKeyedRoom(group) || (keyedChangesAllowed(group) && actor != target)) &&
            MarmotGroupPermissions.canDemote(group.currentAdminIdentities(), members(group), actor, target)

    /**
     * Whether a keyed room's admins can be changed at all from this build: the room
     * has to need the group's signature for its changes (R2), and share rotation and
     * enrollment have to be on, since every admin change moves a share (R1). Even
     * then an admin does not step down on their own in a keyed room: the rotation
     * that has to follow is the remaining holders', so another admin proposes it.
     * docs/member-admin-promotion.md, Phases 8 and 9.
     */
    fun keyedChangesAllowed(group: MlsGroup): Boolean =
        isKeyedRoom(group) && GovernanceGate.isGoverned(group) && ExperimentalFeatures.shareRefresh

    /**
     * What [me] may do to each member of [group], for the member rows' menus. Empty
     * when [me] does not govern the room. Removal stays offered in a keyed room,
     * where it always was, unless it would take the room below [floor] admins
     * ([adminFloor]); promotion and demotion are not, for [isKeyedRoom]'s reason.
     */
    fun memberActions(group: MlsGroup, me: HexKey, floor: Int = 1): Map<HexKey, Set<MemberAction>> {
        if (!MarmotGroupPermissions.canManage(group.currentAdminIdentities(), me)) return emptyMap()
        return members(group).associateWith { member ->
            buildSet {
                if (member == me) {
                    if (canDemote(group, me, me)) add(MemberAction.STEP_DOWN)
                } else {
                    if (canPromote(group, me, member)) add(MemberAction.MAKE_ADMIN)
                    if (canDemote(group, me, member) && keepsFloor(group, member, floor)) add(MemberAction.REMOVE_AS_ADMIN)
                    if (MarmotGroupPermissions.canRemove(group.currentAdminIdentities(), me, member) && keepsFloor(group, member, floor)) {
                        add(MemberAction.REMOVE_FROM_GROUP)
                    }
                }
            }
        }.filterValues { it.isNotEmpty() }
    }

    /** How **Leave group** goes for [me] in [group]: null when [me] is not an admin. */
    fun adminLeave(group: MlsGroup, me: HexKey): AdminLeave? = when {
        me !in group.currentAdminIdentities() -> null
        isKeyedRoom(group) -> AdminLeave.CANNOT
        canDemote(group, me, me) -> AdminLeave.STEP_DOWN_FIRST
        (members(group) - me).isEmpty() -> AdminLeave.CANNOT
        else -> AdminLeave.PICK_HEIR
    }
}
