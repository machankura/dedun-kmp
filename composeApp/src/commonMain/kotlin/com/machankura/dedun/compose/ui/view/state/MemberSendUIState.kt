package com.machankura.dedun.compose.ui.view.state

import com.machankura.dedun.compose.managers.MemberWalletManager
import com.machankura.dedun.compose.wallet.GroupWalletState
import fr.acinq.bitcoin.OutPoint

sealed interface MemberSendUIState {
    data object Loading : MemberSendUIState

    data class Error(val message: String) : MemberSendUIState

    /** A wallet this device can spend from: certified, its key here, and a node to see the coins with. */
    data object Unavailable : MemberSendUIState

    data class Ready(
        val wallet: MemberWalletManager.MemberWalletRow,
        /** Null until Electrum answers. */
        val walletState: GroupWalletState?,
        /** The coins the member's own unsettled spends hold, which a new one leaves alone. */
        val reserved: Set<OutPoint> = emptySet(),
        /** The change indices those spends pay back to, which a new one pays past. */
        val reservedChange: Set<Long> = emptySet(),
    ) : MemberSendUIState
}
