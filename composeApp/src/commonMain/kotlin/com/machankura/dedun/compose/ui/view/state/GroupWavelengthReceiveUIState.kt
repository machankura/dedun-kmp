package com.machankura.dedun.compose.ui.view.state

import com.machankura.dedun.compose.wallet.wavelength.group.GroupWavelengthTerms
import com.machankura.dedun.compose.wallet.wavelength.group.PaidRequest

/** A member getting an address to pay the wallet at, with what it takes to get one. */
sealed interface GroupWavelengthReceiveUIState {
    data object Loading : GroupWavelengthReceiveUIState

    data class Error(val message: String) : GroupWavelengthReceiveUIState

    /** Only a member with a depositor statement gets an address, or nobody could say whose a deposit was. */
    data object NotADepositor : GroupWavelengthReceiveUIState

    /** Whoever runs the wallet makes the address, so there's nothing to ask while nobody does. */
    data object NobodyRunsIt : GroupWavelengthReceiveUIState

    /** On mainnet, at the cap every member's device holds it to. Asking would only lead to coins nobody signs for. */
    data object AtTheCap : GroupWavelengthReceiveUIState

    /** What this member has asked for and been given, on each rail. Both come back only once this device has checked them. */
    data class Loaded(
        /** An address this device recomputed and believes is the group's, or null. */
        val address: String? = null,
        /** Asked for an address, and the operator's device hasn't answered. */
        val addressAsked: Boolean = false,
        /** The newest invoice it's been given, which it can't prove pays the group, and what it's for. */
        val invoice: String? = null,
        val invoiceAmountSat: Long? = null,
        val invoiceAsked: Boolean = false,
        val terms: GroupWavelengthTerms.Terms? = null,
        val maxRoundFeeSat: Long = 0,
        val roomLeftSat: Long? = null,
        /** Whether enough of the group's devices have answered lately. A payment fails on the payer's side if they can't claim it in time. */
        val enoughSeen: Boolean = true,
        /**
         * What has arrived against [address] and [invoice], or null while nothing has. Set means
         * the request stops being a QR code (`docs/paid-requests.md`, D1); the amount inside can
         * still be null where this device knows a payment happened but not how much.
         */
        val addressPaid: PaidRequest? = null,
        val invoicePaid: PaidRequest? = null,
        /** Whoever runs the wallet, named so that a payment only they vouch for says whose word it is (D4). */
        val operatorName: String? = null,
    ) : GroupWavelengthReceiveUIState {
        /** The most an invoice can be for: a coin's most, and what's left under the cap. */
        val maxInvoiceSat: Long? get() = listOfNotNull(terms?.maxVtxoSat, roomLeftSat).minOrNull()
    }
}
