package com.machankura.dedun.compose.ui.view.state

/**
 * What this device may do to one member of a room, as the member row's menu offers
 * it. Computed by the view model from the group's state and
 * `com.machankura.dedun.compose.managers.AdminGovernance`, so the screen never decides an
 * action itself. See docs/member-admin-promotion.md, Phase 11.
 */
enum class MemberAction {
    MAKE_ADMIN,
    REMOVE_AS_ADMIN,
    /** Removing yourself as an admin, on your own row. */
    STEP_DOWN,
    REMOVE_FROM_GROUP,
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
