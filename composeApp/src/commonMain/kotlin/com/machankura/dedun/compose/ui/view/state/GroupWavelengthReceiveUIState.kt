package com.machankura.dedun.compose.ui.view.state

import com.machankura.dedun.compose.wallet.wavelength.group.GroupWavelengthTerms

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

    data object CanAsk : GroupWavelengthReceiveUIState

    /** Asked: the operator's device answers while it's running. */
    data object Waiting : GroupWavelengthReceiveUIState

    /** An address this device recomputed and believes is the group's. */
    data class Ready(val address: String, val terms: GroupWavelengthTerms.Terms?, val maxRoundFeeSat: Long, val roomLeftSat: Long?) : GroupWavelengthReceiveUIState
}
