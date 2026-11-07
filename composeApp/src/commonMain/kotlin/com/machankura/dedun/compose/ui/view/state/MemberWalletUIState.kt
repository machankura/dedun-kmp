package com.machankura.dedun.compose.ui.view.state

import com.machankura.dedun.compose.database.model.MemberWalletSpend
import com.machankura.dedun.compose.managers.MemberWalletManager
import com.machankura.dedun.compose.wallet.GroupAddress
import com.machankura.dedun.compose.wallet.GroupWalletState
import fr.acinq.bitcoin.Satoshi

sealed interface MemberWalletUIState {
    data object Loading : MemberWalletUIState

    data class Error(val message: String) : MemberWalletUIState

    data class Loaded(
        val wallet: MemberWalletManager.MemberWalletRow,
        /** Null before Electrum's first answer, and for a wallet no group has certified. */
        val walletState: GroupWalletState? = null,
        /** What the wallet's guardians took from it, read off the chain: never stored, found again on each look. */
        val taken: List<TakenLine> = emptyList(),
        /** The spends the member signed from it, newest first. */
        val spends: List<MemberWalletSpend> = emptyList(),
        /** How many groups guard it, for the line under the title. */
        val reach: Int = wallet.account.guardians.size,
        /** Each guardian's delay, nearest first: the leaves the wallet's addresses carry. */
        val delays: List<Long> = wallet.account.guardians.map { it.delay },
        /**
         * The address the receive card was handing out, once it has been paid: the wallet has
         * already moved on to the next one, and this is what says so (`docs/paid-requests.md`, D7).
         */
        val justPaid: GroupAddress? = null,
    ) : MemberWalletUIState
}

/** One guardian spend from a member's wallet, named for the group that made it. */
data class TakenLine(
    val txid: String,
    /** The guardian's room name on this device, or null when this device is not in it. */
    val guardianName: String?,
    val amount: Satoshi,
)
