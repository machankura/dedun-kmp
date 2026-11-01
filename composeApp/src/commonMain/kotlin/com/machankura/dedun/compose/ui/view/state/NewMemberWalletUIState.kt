package com.machankura.dedun.compose.ui.view.state

import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.managers.MemberWalletManager

sealed interface NewMemberWalletUIState {
    data object Loading : NewMemberWalletUIState

    data class Error(val message: String) : NewMemberWalletUIState

    /**
     * No group chosen yet: every group this profile is in that guards members'
     * wallets, or may -- one whose key this device does not know yet -- each
     * with why it cannot guard a wallet now, if it cannot.
     */
    data class Picking(val groups: List<GroupChoice>) : NewMemberWalletUIState

    /**
     * A group chosen. [refusal] null means it can be asked, and [reach] is how
     * many groups the wallet would be guarded by -- the group and every group
     * above it whose key it passes down.
     */
    data class Chosen(
        val room: LocalChatRoom,
        val refusal: MemberWalletManager.Refusal?,
        val reach: Int?,
        /** Each guardian's delay, nearest first, as [reach] counts them; null while the group's key is unknown. */
        val delays: List<Long>? = null,
        /** The member already asked the group for its key, and is waiting on an answer. */
        val askedForKey: Boolean = false,
    ) : NewMemberWalletUIState
}

data class GroupChoice(
    val room: LocalChatRoom,
    val refusal: MemberWalletManager.Refusal?,
)
