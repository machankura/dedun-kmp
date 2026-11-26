package com.machankura.dedun.compose.managers

import com.vitorpamplona.marmot.groups.MarmotGroupPermissions
import com.vitorpamplona.marmot.groups.currentAdminIdentities
import com.vitorpamplona.marmot.groups.currentGroupState
import com.vitorpamplona.mls.group.MlsGroup
import com.vitorpamplona.mls.tree.Credential
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
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
     * Whether [me] may ask to stop being an admin of keyed room [group]. They cannot
     * demote themselves: a demotion's proposer starts the rotation that takes their
     * share away, and the proposer has to keep a share. So they ask, and
     * [stepDownProposer]'s device proposes it. Never below [floor], and only while
     * another admin is there to ask.
     */
    fun canAskToStepDown(group: MlsGroup, me: HexKey, floor: Int): Boolean =
        keyedChangesAllowed(group) &&
            me in group.currentAdminIdentities() &&
            keepsFloor(group, me, floor) &&
            stepDownProposer(group, me) != null

    /**
     * The admin whose device proposes [requester]'s step-down: the one in the lowest
     * leaf, [requester] apart. Read off the group's state, so every device names the
     * same one and only one of them proposes -- two proposals at one epoch would be
     * two commits the library cannot settle for their authors (docs/marmot-v2.md,
     * Phase 8). Null when nobody else is an admin.
     */
    fun stepDownProposer(group: MlsGroup, requester: HexKey): HexKey? {
        val admins = group.currentAdminIdentities() - requester
        return group.members()
            .sortedBy { (leafIndex, _) -> leafIndex }
            .firstNotNullOfOrNull { (_, leaf) ->
                (leaf.credential as? Credential.Basic)?.identity?.toHexKey()?.takeIf { it in admins }
            }
    }

    /**
     * Why no change to who is an admin -- and no removal of a member who holds a
     * share -- may be proposed in [localChatRoom] now, or null when one may: a
     * rotation of the room's key is still under way, until the group has signed
     * the key state it ends in (docs/admin-change-rotation.md, A5). A change now
     * would be fixed against a roster the rotation is about to replace: a removed
     * admin dealt a fresh share, a new admin's binding failing the rotation, a
     * statement failed with the shares it was being signed with.
     *
     * The other direction, a rotation waiting on an admin change, is
     * [ShareRefreshManager.rotationHold]'s. Null in a room without a key.
     */
    suspend fun adminChangeHold(database: DedunDatabase, localChatRoom: com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom): RotationHold? {
        val key = keyOf(database, localChatRoom.chatRoom.id) ?: return null
        return ShareRefreshManager.rotationPending(database, localChatRoom, key)
    }

    /** What a change waits on while the room's shares are being rotated. */
    const val ROTATION_PENDING_REFUSAL = "The group's shares are still being rotated. Admins can change once the group has signed the new shares"

    /**
     * Whether [change] moves who holds a share of the room's key: it changes the
     * admins -- every admin holds a share (R1), so a promotion enrolls one and a
     * demotion rotates one away -- or removes a member who holds one of
     * [holders]. Inviting or removing anyone else leaves the roster alone, and
     * does not wait on a rotation (docs/admin-change-rotation.md, A6).
     */
    fun touchesShares(change: com.machankura.dedun.compose.nostr.frost.GovernanceChange, holders: Set<HexKey>): Boolean =
        change.admins != null || change.removes.any { it in holders }

    /**
     * Why [change] must not be proposed in [localChatRoom] now, as far as the
     * room's shares go, or null (docs/admin-change-rotation.md, Phase 3): a
     * change that moves who holds a share waits while a rotation is pending
     * ([adminChangeHold]), and one that takes a holder's share away is refused
     * when the rotation it owes could not run (A12) -- before anybody signs it,
     * since a change that lands with a rotation nobody can run leaves a
     * non-admin holding a live share.
     */
    suspend fun sharesRefusal(
        database: DedunDatabase,
        localChatRoom: com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom,
        change: com.machankura.dedun.compose.nostr.frost.GovernanceChange,
        currentAdmins: Set<HexKey>,
    ): String? {
        val key = keyOf(database, localChatRoom.chatRoom.id) ?: return null
        val shares = ShareGenerations.active(database, key)
        val holders = shares.roster.filter { it.standing == com.machankura.dedun.compose.database.model.types.ShareStanding.LIVE }.map { it.memberPublicKey }.toSet()
        if (!touchesShares(change, holders)) return null
        if (adminChangeHold(database, localChatRoom) != null) return ROTATION_PENDING_REFUSAL

        val adminsAfter = (change.admins ?: currentAdmins) - change.removes
        val retire = holders - adminsAfter
        // Only a change that itself takes a share away owes a rotation.
        if (retire.none { it in change.removes || it in currentAdmins }) return null
        return ShareRefreshManager.owedRotationRefusal(key, shares, retire)?.let {
            "This would owe a rotation of the shares the group can't run: $it. Rotate the shares, or bring stale members back, first"
        }
    }

    /**
     * The key [chatRoomId] signs with, as this device holds it -- whether or not it
     * can sign with it now: a member withheld from a rotation still follows it.
     */
    suspend fun keyOf(database: DedunDatabase, chatRoomId: String): com.machankura.dedun.compose.database.model.DkgSession? =
        GroupKeyStateManager.keyStateFor(database, chatRoomId)?.dkgSessionId
            ?.let { database.dkgSessionDao().getSessionById(it) }
            ?.takeIf { it.stage == DkgRitualStage.COMPLETE }
            ?: FrostSigningManager.completedKey(database, chatRoomId)

    /**
     * What [MemberActionPolicy] reads, from [group]'s state: its admins, its
     * members, whether it is keyed and needs the group's signature, and [floor].
     * What only the database knows -- having left, whose devices a keyed room
     * waits on, work in flight -- is the caller's to add.
     */
    fun context(group: MlsGroup, me: HexKey, floor: Int = 1): MemberActionContext = MemberActionContext(
        room = MemberActionRoom.CURRENT,
        me = me,
        admins = group.currentAdminIdentities().toSet(),
        members = members(group),
        isDisbanded = group.currentGroupState().isDisbanded,
        isKeyed = isKeyedRoom(group),
        governed = GovernanceGate.isGoverned(group),
        shareRefresh = ExperimentalFeatures.shareRefresh,
        floor = floor,
    )

    /**
     * What [me] may do now to each member of [group]: [MemberActionPolicy]'s
     * options that are open, and members with none left out. Empty when [me] does
     * not govern the room. Removal stays open in a keyed room, where it always was,
     * unless it would take the room below [floor] admins ([adminFloor]); promotion
     * and demotion are not, for [isKeyedRoom]'s reason.
     */
    fun memberActions(group: MlsGroup, me: HexKey, floor: Int = 1): Map<HexKey, Set<MemberAction>> =
        MemberActionPolicy.options(context(group, me, floor), members(group))
            .mapValues { (_, options) -> options.filter { it.isAvailable }.map { it.action }.toSet() }
            .filterValues { it.isNotEmpty() }

    /** How **Leave group** goes for [me] in [group]: null when [me] is not an admin. */
    fun adminLeave(group: MlsGroup, me: HexKey): AdminLeave? = when {
        me !in group.currentAdminIdentities() -> null
        isKeyedRoom(group) -> AdminLeave.CANNOT
        canDemote(group, me, me) -> AdminLeave.STEP_DOWN_FIRST
        (members(group) - me).isEmpty() -> AdminLeave.CANNOT
        else -> AdminLeave.PICK_HEIR
    }
}
