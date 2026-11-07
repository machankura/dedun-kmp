package com.machankura.dedun.compose.ui.view.state

import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.managers.GroupLightningWalletManager
import com.machankura.dedun.compose.repository.DepositRepository
import fr.acinq.bitcoin.Satoshi

/** Asking the operator's node for an invoice: the form, the wait for the operator's device, the invoice. */
sealed class GroupLightningReceiveUIState {
    data object Loading : GroupLightningReceiveUIState()

    data class Error(val message: String) : GroupLightningReceiveUIState()

    /** No wallet, or no operator to run its node. */
    data object Unavailable : GroupLightningReceiveUIState()

    /** The form, for a depositor: every invoice is issued against their statement. No offer -- BOLT12 is off. */
    data class Form(val localChatRoom: LocalChatRoom, val wallet: GroupLightningWalletManager.Wallet) : GroupLightningReceiveUIState()

    /**
     * A wallet and an operator, but no invoice can be this profile's yet: the step before it, a payout
     * destination or the group opening its deposits. `docs/member-deposits.md`, Phase 6.
     */
    data class NotDepositor(val localChatRoom: LocalChatRoom, val standing: DepositRepository.Standing) : GroupLightningReceiveUIState()

    data class Waiting(val requestId: String) : GroupLightningReceiveUIState()

    /** No reply from the operator's device within [GroupLightningReceiveViewModel.REPLY_WAIT]: its device may be off. */
    data class NoReply(val requestId: String) : GroupLightningReceiveUIState()

    /** An invoice the operator made, already checked to pay the group's node. */
    data class Invoice(val bolt11: String) : GroupLightningReceiveUIState()

    /**
     * That invoice, paid. It stops being a QR code here and nothing shows it as one again:
     * whoever was scanning it has already paid, and a bolt11 paid twice is the second
     * payer's money gone rather than a second credit (`docs/paid-requests.md`, D1).
     *
     * [onTheWordOf] is the operator's name, because on this wallet it always is: a payment
     * is known only from the snapshot the operator sends, so the panel says whose word it
     * is (D4). [amount] is what that snapshot says arrived, which can differ from what was
     * asked for.
     */
    data class Paid(val bolt11: String, val amount: Satoshi?, val onTheWordOf: String?) : GroupLightningReceiveUIState()
}
