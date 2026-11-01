package com.machankura.dedun.compose.ui.view.state

import com.machankura.dedun.compose.database.model.DepositRow
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.managers.DepositLedger
import com.vitorpamplona.quartz.nip01Core.core.HexKey

sealed interface DepositsUIState {
    data object Loading : DepositsUIState

    data class Error(val message: String) : DepositsUIState

    data class Loaded(
        val localChatRoom: LocalChatRoom,
        val deposits: List<DepositRow>,
        val tallies: DepositLedger.Tallies,
        /** Each member's name, for saying whose a deposit is. */
        val names: Map<HexKey, String>,
        /** Whether this device holds a share, and so can propose an attribution (Phase 9). */
        val canSign: Boolean = false,
        /** Whom a deposit can be given to: the members the group has opened deposits for. */
        val depositors: List<HexKey> = emptyList(),
    ) : DepositsUIState
}
