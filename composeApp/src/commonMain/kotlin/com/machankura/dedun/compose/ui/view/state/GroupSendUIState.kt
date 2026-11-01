package com.machankura.dedun.compose.ui.view.state

import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.managers.GuardianManager
import com.machankura.dedun.compose.wallet.GroupAddress
import com.machankura.dedun.compose.nostr.wallet.GroupWallet
import com.machankura.dedun.compose.wallet.GroupSpend
import com.machankura.dedun.compose.wallet.GroupWalletState
import fr.acinq.bitcoin.OutPoint

sealed interface GroupSendUIState {
    /**
     * A wallet this device can propose a spend from: it holds a share, and it has
     * a node to see the coins with. [walletState] is null until Electrum answers.
     */
    data class Ready(
        val localChatRoom: LocalChatRoom,
        val wallet: GroupWallet,
        val walletState: GroupWalletState?,
        /** The coins open spends already hold, which a new one leaves alone. */
        val reserved: Set<OutPoint>,
        /** The change indices open spends already pay back to, which a new one pays past. */
        val reservedChange: Set<Long> = emptySet(),
        /**
         * Who would sign: the room's own group, or -- spending from a descendant's
         * guarded wallet -- the group as that wallet's guardian. [wallet] is then
         * the descendant's.
         */
        val spender: GroupSpend.Spender = GroupSpend.Spender.Owner,
        /** The descendant's guarded wallet this send pays, or null for a send to a typed address. */
        val funding: FundingTarget? = null,
    ): GroupSendUIState

    /** A room this device cannot propose from -- no wallet, no share, or no node. */
    data object Unavailable: GroupSendUIState

    data class Error(val message: String): GroupSendUIState

    data object Loading: GroupSendUIState
}

/**
 * A descendant's guarded wallet a send funds, with where it is up to: its chain
 * state as this device watches it, and the receive indices the room's open
 * fundings already hold. See `docs/guarded-wallet-funding.md`.
 */
data class FundingTarget(
    val wallet: GuardianManager.GuardedWallet,
    /** Null until Electrum has answered for the descendant's addresses. */
    val walletState: GroupWalletState?,
    val reserved: Set<Long> = emptySet(),
) {
    /** The address the send pays, moving on whenever the one before it is paid or held. */
    val address: GroupAddress? get() = walletState?.fundingAddress(reserved)

    /** Electrum has answered, and open fundings hold every address the wallet watches. */
    val allHeld: Boolean get() = walletState?.receiveAddress != null && address == null
}
