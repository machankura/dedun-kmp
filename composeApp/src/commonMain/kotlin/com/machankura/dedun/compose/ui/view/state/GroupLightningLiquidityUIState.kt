package com.machankura.dedun.compose.ui.view.state

import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.managers.GroupLightningWalletManager
import com.machankura.dedun.compose.wallet.lightning.LiquidityPrice
import com.machankura.dedun.compose.wallet.lightning.WalletWire

/** Asking the group to buy inbound liquidity (`docs/group-lightning-liquidity.md`, Phase 7). */
sealed class GroupLightningLiquidityUIState {
    data object Loading : GroupLightningLiquidityUIState()

    data class Error(val message: String) : GroupLightningLiquidityUIState()

    /** Nothing can be asked for now, and [why]. */
    data class Unavailable(val why: LiquidityPrice.Availability) : GroupLightningLiquidityUIState()

    /** What can be bought, priced from the operator's [snapshot] of the term [term]. */
    data class Form(
        val localChatRoom: LocalChatRoom,
        val wallet: GroupLightningWalletManager.Wallet,
        val snapshot: WalletWire.Snapshot,
        val available: LiquidityPrice.Availability.Available,
    ) : GroupLightningLiquidityUIState() {
        val term: Long get() = snapshot.term
    }
}
