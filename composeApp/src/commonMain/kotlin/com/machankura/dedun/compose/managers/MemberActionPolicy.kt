package com.machankura.dedun.compose.managers

import com.vitorpamplona.marmot.groups.MarmotGroupPermissions
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.machankura.dedun.compose.ui.view.state.MemberAction
import com.machankura.dedun.compose.ui.view.state.MemberActionBlocker
import com.machankura.dedun.compose.ui.view.state.MemberActionOption

/**
 * What kind of room a member row is in, as far as its admins go.
 */
enum class MemberActionRoom {
    /** A robust group's NIP-17 room, which has no admins of its own. */
    NIP17,

    /** A Marmot room on the legacy profile, which the app does not govern. */
    LEGACY,

    /** A Marmot room on the current profile, whose admin policy is the group's state. */
    CURRENT,
}

/**
 * Everything [MemberActionPolicy] reads, as plain values, so every reason can be
 * decided -- and tested -- without an MLS group. [AdminGovernance.context] builds
 * one from a group; `ChatRepository.memberActionOptions` adds what only the
 * database knows.
 */
data class MemberActionContext(
    val room: MemberActionRoom,
    val me: HexKey,
    /** The group's admins; off the current profile, the participants' `adminAt`. */
    val admins: Set<HexKey>,
    /** Who holds a leaf now; empty off the current profile. */
    val members: Set<HexKey>,
    val isDisbanded: Boolean = false,
    val hasLeft: Boolean = false,
    val isKeyed: Boolean = false,
    /** Whether a keyed room's changes need the group's signature. */
    val governed: Boolean = false,
    /** Whose devices a keyed room is waiting on before it can need that signature. */
    val waitingOn: Set<HexKey> = emptySet(),
    /** `ExperimentalFeatures.shareRefresh`, without which a keyed room's admins cannot change. */
    val shareRefresh: Boolean = false,
    /** [AdminGovernance.adminFloor]: the fewest admins the room may be left with. */
    val floor: Int = 1,
    /** An earlier change is still waiting for a relay; docs/member-row-actions.md, Phase 5. */
    val changePending: Boolean = false,
    /** Members this account has offered admin and who have not answered. */
    val offeredAdmin: Set<HexKey> = emptySet(),
)

/**
 * What a member row's menu offers, and why each item is or is not open. The rules
 * are [MarmotGroupPermissions]', which every peer enforces on a commit, and
 * [AdminGovernance]'s two additions to them -- the keyed room and the floor -- so
 * the menu, the explanation screen and the commit cannot disagree.
 * [AdminGovernance.memberActions] is this, filtered to what is open.
 * docs/member-row-actions.md, D2 and D4.
 */
object MemberActionPolicy {

    /**
     * The options for each of [rows]. Every other member gets the action that fits
     * their role -- **Make admin** or **Remove as admin** -- and **Remove from
     * group**, open or not. [MemberActionContext.me] gets **Step down as admin**
     * only, and only when it is open: the viewer's own row lists nothing it cannot
     * do (D1).
     */
    fun options(context: MemberActionContext, rows: Collection<HexKey>): Map<HexKey, List<MemberActionOption>> =
        rows.distinct().mapNotNull { member ->
            if (member == context.me) {
                stepDown(context)?.let { member to listOf(it) }
            } else {
                member to forMember(context, member)
            }
        }.toMap()

    private fun stepDown(context: MemberActionContext): MemberActionOption? {
        val open = context.room == MemberActionRoom.CURRENT &&
            !context.isDisbanded &&
            !context.hasLeft &&
            if (context.isKeyed) {
                // [AdminGovernance.canAskToStepDown]: a request another admin's device
                // proposes, because a rotation's proposer has to keep a share. So the
                // room's rules, the floor, and another admin with a leaf to ask.
                context.governed && context.shareRefresh &&
                    context.me in context.admins &&
                    keepsFloor(context, context.me) &&
                    context.admins.any { it != context.me && it in context.members }
            } else {
                MarmotGroupPermissions.canDemote(context.admins, context.members, context.me, context.me)
            }
        return if (open) MemberActionOption(MemberAction.STEP_DOWN) else null
    }

    private fun forMember(context: MemberActionContext, member: HexKey): List<MemberActionOption> {
        val isAdmin = member in context.admins
        val roleAction = if (isAdmin) MemberAction.REMOVE_AS_ADMIN else MemberAction.MAKE_ADMIN
        val room = roomBlockers(context)
        // Off the current profile the room is the whole answer: there are no rules to read.
        if (context.room != MemberActionRoom.CURRENT) {
            return listOf(MemberActionOption(roleAction, room), MemberActionOption(MemberAction.REMOVE_FROM_GROUP, room))
        }

        val actor = listOfNotNull(
            MemberActionBlocker.NotAnAdmin(context.admins).takeUnless { MarmotGroupPermissions.canManage(context.admins, context.me) },
        )
        val keyed = if (context.isKeyed) {
            listOfNotNull(
                MemberActionBlocker.SignaturesNotRequired(context.waitingOn).takeUnless { context.governed },
                MemberActionBlocker.ShareRotationUnavailable.takeUnless { context.shareRefresh },
            )
        } else {
            emptyList()
        }
        val inGroup = member in context.members
        // The only admin is the floor of one said plainly; a higher floor names its number.
        val floor = when {
            isAdmin && context.admins.none { it != member && it in context.members } -> MemberActionBlocker.OnlyAdmin
            !keepsFloor(context, member) -> MemberActionBlocker.BelowFloor(context.floor)
            else -> null
        }
        val pending = listOfNotNull(MemberActionBlocker.ChangePending.takeIf { context.changePending })

        val role = if (isAdmin) {
            room + actor + keyed + listOfNotNull(floor) + pending
        } else {
            room + actor + keyed + listOfNotNull(
                MemberActionBlocker.NotInGroupYet.takeUnless { inGroup },
            ) + pending + listOfNotNull(
                MemberActionBlocker.OfferPending.takeIf { member in context.offeredAdmin },
            )
        }
        val removal = room + actor + listOfNotNull(
            MemberActionBlocker.NotInGroupYet.takeUnless { inGroup },
            floor,
        ) + pending

        return listOf(MemberActionOption(roleAction, role), MemberActionOption(MemberAction.REMOVE_FROM_GROUP, removal))
    }

    private fun roomBlockers(context: MemberActionContext): List<MemberActionBlocker> = listOfNotNull(
        MemberActionBlocker.NoAdminsInThisRoom.takeIf { context.room == MemberActionRoom.NIP17 },
        MemberActionBlocker.LegacyRoom.takeIf { context.room == MemberActionRoom.LEGACY },
        MemberActionBlocker.Disbanded.takeIf { context.isDisbanded },
        MemberActionBlocker.YouLeft.takeIf { context.hasLeft },
    )

    /** [AdminGovernance.keepsFloor], over the context's sets. */
    private fun keepsFloor(context: MemberActionContext, target: HexKey): Boolean {
        val activeAdmins = context.admins intersect context.members
        return target !in activeAdmins || activeAdmins.size - 1 >= context.floor
    }
}
