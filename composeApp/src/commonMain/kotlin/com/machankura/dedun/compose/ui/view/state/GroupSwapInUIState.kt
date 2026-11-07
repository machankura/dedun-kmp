package com.machankura.dedun.compose.ui.view.state

import com.machankura.dedun.compose.wallet.SwapInAddress
import com.machankura.dedun.compose.wallet.SwapInCoin

sealed interface GroupSwapInUIState {
    /** Until Electrum has said which of the member's addresses are used, and the tip is known. */
    data object Loading : GroupSwapInUIState

    /** No Lightning wallet, or one on a network whose LSP has no swap-in: nothing to deposit to. */
    data object Unavailable : GroupSwapInUIState

    /** A wallet, but no address can be this profile's yet: they are not a depositor (docs/member-deposits.md). */
    data object NotDepositor : GroupSwapInUIState

    data class Loaded(
        /** The address to hand out: the member's lowest unused one. */
        val current: SwapInAddress,
        /** Every coin on the member's addresses, the mempool first. Empty is the screen's empty state. */
        val coins: List<SwapInCoin>,
        /** The member's addresses that have been paid, newest first: to check, never to hand out. */
        val used: List<SwapInAddress>,
        /** Every other depositor's used addresses, by name: there to check, never to hand out. */
        val others: List<Pair<String, List<SwapInAddress>>>,
        val minConfirmations: Int,
        val refundDelay: Int,
        /**
         * The address this screen was handing out, once it has been paid: the wallet has already
         * moved on to [current], and this is what says so (`docs/paid-requests.md`, D7).
         */
        val justPaid: SwapInAddress? = null,
    ) : GroupSwapInUIState
}
