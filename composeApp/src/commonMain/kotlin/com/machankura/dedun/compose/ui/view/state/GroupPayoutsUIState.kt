package com.machankura.dedun.compose.ui.view.state

import com.machankura.dedun.compose.managers.GroupLightningWalletManager
import com.machankura.dedun.compose.managers.GroupWavelengthWalletManager
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.managers.MemberBalances
import com.machankura.dedun.compose.wallet.GroupWalletState
import com.machankura.dedun.compose.wallet.PayoutRule
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.OutPoint

sealed interface GroupPayoutsUIState {
    data object Loading : GroupPayoutsUIState

    data class Error(val message: String) : GroupPayoutsUIState

    data class Loaded(
        val localChatRoom: LocalChatRoom,
        /** Every member's balance and destination, and the coins nobody owns. */
        val balances: MemberBalances.Balances,
        /** The wallet as Electrum last said, or null before its first answer. */
        val walletState: GroupWalletState?,
        /** The coins the room's open spends hold, which no new payout may take. */
        val reserved: Set<OutPoint>,
        /** What a payout is checked against, read with [balances]. */
        val context: PayoutRule.Context,
        val names: Map<HexKey, String>,
        /** Whether this device holds a share, and so can propose a payout. */
        val canSign: Boolean,
        /** The active profile, whose own row asks for an address it has not given. */
        val self: HexKey?,
        /** The group's Lightning wallet, when it has one with an operator to pay from: Phase 5. */
        val lightningWallet: GroupLightningWalletManager.Wallet? = null,
        /** What the Lightning wallet's channels hold, as its operator last reported, or null before a report. */
        val lightningSpendableMsat: Long? = null,
        /** The group's Wavelength wallet, where it has one with someone running it and payouts are on for its network. */
        val wavelengthWallet: GroupWavelengthWalletManager.Wallet? = null,
    ) : GroupPayoutsUIState
}
