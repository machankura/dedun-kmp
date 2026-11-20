package com.machankura.dedun.compose.ui.composable.navigation.routes

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlinx.serialization.Serializable
import com.machankura.dedun.compose.ui.view.state.MemberAction

/**
 * Why [memberAction] on [memberPublicKey] is not open in [chatRoomId] now. Carries
 * what was asked, not the answer: the screen works the reasons out again, so they
 * cannot be stale and cannot be anything but the rules'. See
 * docs/member-row-actions.md, D5.
 *
 * The action travels by its name: the multiplatform navigation-compose has no
 * `NavType` for an enum, and a route whose argument it cannot type fails the
 * moment it is navigated to. A name no build knows reads as null, which the
 * screen says no longer applies.
 */
@Serializable
data class MemberActionUnavailableRoute(
    val activeUserPublicKey: String,
    val chatRoomId: String,
    val memberPublicKey: HexKey,
    val action: String,
) : Route() {
    constructor(activeUserPublicKey: String, chatRoomId: String, memberPublicKey: HexKey, action: MemberAction) :
        this(activeUserPublicKey, chatRoomId, memberPublicKey, action.name)

    val memberAction: MemberAction? get() = MemberAction.entries.firstOrNull { it.name == action }
}
