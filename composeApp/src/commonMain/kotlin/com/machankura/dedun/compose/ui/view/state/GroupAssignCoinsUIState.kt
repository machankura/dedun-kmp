package com.machankura.dedun.compose.ui.view.state

import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.managers.MemberBalances
import com.vitorpamplona.quartz.nip01Core.core.HexKey

sealed interface GroupAssignCoinsUIState {
    data object Loading : GroupAssignCoinsUIState

    data class Error(val message: String) : GroupAssignCoinsUIState

    data class Loaded(
        val localChatRoom: LocalChatRoom,
        /** The coins nobody owns, as the wallet holds them now. */
        val unowned: List<MemberBalances.UnownedCoin>,
        /** Whom a coin can be given to: the members the group has opened deposits for. */
        val depositors: List<HexKey>,
        val names: Map<HexKey, String>,
        /** Whether this device holds a share, and so can propose an assignment. */
        val canSign: Boolean,
    ) : GroupAssignCoinsUIState
}
