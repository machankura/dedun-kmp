package com.machankura.dedun.compose.ui.view.state

import com.machankura.dedun.compose.wallet.FirstDeposit
import com.machankura.dedun.compose.wallet.SwapInAddress
import com.machankura.dedun.compose.wallet.SwapInCoin

sealed interface SwapInWalletUIState {
    /** Until Electrum has said which addresses are used and the node has heard of a block. */
    data object Loading : SwapInWalletUIState

    /** A profile with no seed has no node, and so no swap-in wallet: a bare nsec or an npub. */
    data object NoNode : SwapInWalletUIState

    /** Reading the node failed; a retry collects again. */
    data object Error : SwapInWalletUIState

    data class Loaded(
        /** The address to hand out. */
        val current: SwapInAddress,
        /** Every address that has been paid, newest first, the legacy one last: to check, never to hand out. */
        val used: List<SwapInAddress>,
        /** Every coin on them, the mempool first. Empty is the screen's empty state. */
        val coins: List<SwapInCoin>,
        /** How many confirmations a deposit waits for before it swaps in. */
        val minConfirmations: Int,
        /** What a first deposit costs, while the node has no channel; see [FirstDeposit]. */
        val firstDeposit: FirstDeposit? = null,
    ) : SwapInWalletUIState
}
