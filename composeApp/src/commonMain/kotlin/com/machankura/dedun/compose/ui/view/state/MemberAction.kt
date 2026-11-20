package com.machankura.dedun.compose.ui.view.state

import com.vitorpamplona.quartz.nip01Core.core.HexKey

/**
 * What can be done to one member of a room, as the member row's menu offers it.
 * Computed by `com.machankura.dedun.compose.managers.MemberActionPolicy` from the group's
 * state, so the screen never decides an action itself. See
 * docs/member-admin-promotion.md, Phase 11, and docs/member-row-actions.md.
 */
enum class MemberAction {
    MAKE_ADMIN,
    REMOVE_AS_ADMIN,
    /** Removing yourself as an admin, on your own row. */
    STEP_DOWN,
    REMOVE_FROM_GROUP,
}

/**
 * One item of a member row's menu: an action that applies to this member, and
 * what stands in its way. The menu lists it either way; one with [blockers]
 * opens a screen that explains them instead of acting. docs/member-row-actions.md, D3.
 */
data class MemberActionOption(
    val action: MemberAction,
    /** In the order the explanation leads with them; empty when the action is open. */
    val blockers: List<MemberActionBlocker> = emptyList(),
) {
    val isAvailable: Boolean get() = blockers.isEmpty()
}

/**
 * Why an action is not open to this device now. Room-wide reasons come first,
 * then the viewer's role, then the room's rules, then the member, then work in
 * flight -- the order `MemberActionPolicy` lists them in. docs/member-row-actions.md, D4 and D6.
 */
sealed interface MemberActionBlocker {
    /** A robust group's NIP-17 room: its members administer the room its key made. */
    data object NoAdminsInThisRoom : MemberActionBlocker

    /** A room on the legacy Marmot profile, whose admins the app does not change. */
    data object LegacyRoom : MemberActionBlocker

    data object Disbanded : MemberActionBlocker

    /** This device's account has left the room. */
    data object YouLeft : MemberActionBlocker

    /** The viewer is not an admin; [admins] are who to ask. */
    data class NotAnAdmin(val admins: Set<HexKey>) : MemberActionBlocker

    /**
     * A keyed room whose changes do not need the group's signature yet, which
     * every change to its admins has to (R2). [waitingOn] are the members whose
     * devices have not said they can follow; empty when it only needs switching on.
     */
    data class SignaturesNotRequired(val waitingOn: Set<HexKey>) : MemberActionBlocker

    /** A keyed room in a build without share rotation, which every admin change moves (R1). */
    data object ShareRotationUnavailable : MemberActionBlocker

    /** The member holds no leaf in the group: still being added, or already being removed. */
    data object NotInGroupYet : MemberActionBlocker

    /** The member is the only admin among the members; a group always keeps one. */
    data object OnlyAdmin : MemberActionBlocker

    /** It would leave a keyed room below its admin floor (R3). */
    data class BelowFloor(val floor: Int) : MemberActionBlocker

    /** An earlier change to the room is still waiting for a relay to accept it. */
    data object ChangePending : MemberActionBlocker

    /** The member has already been offered admin, and has not answered. */
    data object OfferPending : MemberActionBlocker
}

/**
 * How **Leave group** goes for an admin, who cannot simply leave: the library
 * refuses an admin's SelfRemove. docs/member-admin-promotion.md, Phase 10.
 */
enum class AdminLeave {
    /** Another admin remains: step down, then leave once that is accepted. */
    STEP_DOWN_FIRST,

    /** The only admin: make someone else an admin first, or disband instead. */
    PICK_HEIR,

    /**
     * Nothing this build can do: a keyed room, whose admins change only with the
     * group's signature, or a room with nobody else in it to hand over to.
     */
    CANNOT,
}
