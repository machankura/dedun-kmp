package com.machankura.dedun.compose.ui.view.state

import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.nostr.wallet.MemberWalletPolicyEvent

sealed interface MemberWalletPolicyUIState {
    data object Loading : MemberWalletPolicyUIState

    data class Error(val message: String) : MemberWalletPolicyUIState

    /**
     * The group, and what it says about its members' wallets now: [policy] is
     * its newest, open or closed, or null for one it never signed. [canSign] is
     * whether this device can put a change to the group.
     */
    data class Loaded(
        val localChatRoom: LocalChatRoom,
        val policy: MemberWalletPolicyEvent.Policy?,
        val canSign: Boolean,
    ) : MemberWalletPolicyUIState {
        val isOpen: Boolean get() = policy is MemberWalletPolicyEvent.Policy.Open
    }
}
